import { basename, isAbsolute, relative, resolve } from "node:path";
import { canonicalPath } from "../dirs.js";
import { entryText, inspectSessionFile, type SessionFileInfo, type Transcript, type TranscriptEntry } from "../transcript.js";
import type { QuestionEntry } from "../questions.js";
import { backendForSessionPath } from "../agents/registry.js";
import type { ControlAction } from "../agents/types.js";
import { createSession, controlSession, SessionsError } from "../sessions.js";
import type { Route, RouteContext, RouteResult } from "./types.js";
import * as v from "valibot";
export const sessionsRoutes: Route[] = [
  { method: "GET", path: "/api/sessions", handle: readSessionRoute },
  { method: "POST", path: "/api/sessions", handle: createSessionRoute },
  { method: "POST", path: "/api/sessions/:paneId/control", handle: controlRoute },
];

interface SessionReadResult {
  path: string;
  agentKind: string;
  name: string;
  exists: boolean;
  since: string | null;
  entries: TranscriptEntry[];
  /** Structured question cards, derived from the same entries. */
  questions: QuestionEntry[];
  model: string | null;
  thinkingLevel: string | null;
  preview?: string;
  lastEntryId: string | null;
  mtimeMs: number;
  size: number;
}

/**
 * Memo of parsed transcripts keyed by (mtimeMs, size) — the same shape as
 * BoardDetailCache. The chat poll runs every 2.5s while a session is open; in
 * the steady state (file unchanged) a request now costs one stat instead of
 * a full read and parse of a multi-MB file. Entries are never mutated by
 * callers: cursor slicing creates new arrays and extractQuestions only reads.
 */
interface TranscriptMemoEntry {
  mtimeMs: number;
  size: number;
  transcript: Transcript;
}

interface MemoizedTranscript {
  transcript: Transcript;
  info: SessionFileInfo;
}

const TRANSCRIPT_MEMO_CAP = 8;
const transcriptMemo = new Map<string, TranscriptMemoEntry>();

/**
 * The wire-valid control vocabulary, kept in lockstep with the
 * `ControlAction` union — an unknown action must be rejected before it can
 * reach a backend switch.
 */
const CONTROL_ACTIONS = [
  "abort",
  "retry",
  "compact",
  "fork",
  "rename",
  "close",
  "set_model",
  "set_thinking",
] as const satisfies readonly ControlAction[];

const createSessionBodySchema = v.looseObject({
  cwd: v.optional(v.string()),
  model: v.optional(v.string()),
  name: v.optional(v.string()),
  thinkingLevel: v.optional(v.string()),
  initialPrompt: v.optional(v.string()),
  agent: v.optional(v.string()),
});

const controlBodySchema = v.looseObject({
  action: v.picklist(CONTROL_ACTIONS),
  text: v.optional(v.string()),
});

async function readTranscriptMemoized(
  target: string,
  backend: NonNullable<ReturnType<typeof backendForSessionPath>>,
  info: SessionFileInfo,
): Promise<MemoizedTranscript> {
  const cached = transcriptMemo.get(target);
  if (cached && cached.mtimeMs === info.mtimeMs && cached.size === info.size) {
    return { transcript: cached.transcript, info };
  }
  let before = info;
  let lastTranscript: Transcript | null = null;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    if (attempt > 0) before = await inspectSessionFile(target);
    lastTranscript = await backend.readTranscript(target);
    const after = await inspectSessionFile(target);
    if (before.mtimeMs === after.mtimeMs && before.size === after.size) {
      transcriptMemo.set(target, { mtimeMs: after.mtimeMs, size: after.size, transcript: lastTranscript });
      if (transcriptMemo.size > TRANSCRIPT_MEMO_CAP) {
        const oldest = transcriptMemo.keys().next().value;
        if (oldest !== undefined) transcriptMemo.delete(oldest);
      }
      return { transcript: lastTranscript, info: after };
    }
  }
  // A continuously growing transcript cannot be made perfectly stable; keep
  // the read tied to the revision from immediately before its parse and do
  // not memoize it, so the next poll will retry.
  // SAFETY: readTranscript returns a Transcript on success; a null here means
  // every attempt failed to parse, and we surface the last attempt as best
  // effort rather than retry-and-stall the 2.5s poll.
  return { transcript: lastTranscript as Transcript, info: before };
}

async function readSessionRoute(ctx: RouteContext): Promise<RouteResult> {
  const pathParam = ctx.query.get("path");
  const agentKind = ctx.query.get("agentKind");
  const since = ctx.query.get("since") ?? null;
  if (!pathParam || !agentKind) {
    return { status: 400, body: { ok: false, error: "missing session key query parameters" } };
  }
  // Session reads are file-bound, not herdr-bound: the deliberate
  // outside-the-store rejection surfaces as its 403 via the dispatcher, and
  // unexpected failures are server faults (502 via the dispatcher's
  // catch-all).
  const result = await readSession(pathParam, since, agentKind);
  return { status: 200, body: { ok: true, ...result } };
}

export async function readSession(pathParam: string, since: string | null, agentKind?: string): Promise<SessionReadResult> {
  // Only allow absolute paths claimed by a registered backend (read-only data).
  const target = canonicalPath(resolve(pathParam));
  const backend = backendForSessionPath(target);
  if (!backend) {
    throw new SessionsError("session path is outside a registered session store", 403);
  }
  if (agentKind !== undefined && backend.id !== agentKind) {
    throw new SessionsError("session key backend does not own path", 403);
  }
  const info = await inspectSessionFile(target);
  if (!info.exists) {
    return { path: target, agentKind: backend.id, name: basename(target), exists: false, since, entries: [], questions: [], model: null, thinkingLevel: null, lastEntryId: null, mtimeMs: 0, size: 0 };
  }
  const read = await readTranscriptMemoized(target, backend, info);
  const session = read.transcript;
  let entries = session.entries;
  let cursor: string | null = since;
  if (since) {
    // Compare by file position, not lexically: pi ids are random hex, so
    // lexical order re-sends loaded entries and the app appends duplicate
    // LazyColumn keys (Compose crashes on those).
    const cursorIndex = session.entries.findIndex((entry) => entry.entryId === since);
    if (cursorIndex === -1) {
      // Cursor no longer in the file (rotated/compacted): full snapshot; the
      // app replaces its list when since comes back null.
      cursor = null;
    } else {
      entries = session.entries.slice(cursorIndex + 1);
    }
  }
  const lastEntry = entries[entries.length - 1];
  return {
    path: target,
    agentKind: backend.id,
    name: basename(target),
    exists: true,
    since: cursor,
    entries,
    questions: backend.extractQuestions(session),
    model: session.model,
    thinkingLevel: session.thinkingLevel,
    preview: lastEntry ? entryText(lastEntry, 120) : undefined,
    lastEntryId: session.lastEntryId,
    mtimeMs: read.info.mtimeMs,
    size: read.info.size,
  };
}

async function createSessionRoute(ctx: RouteContext): Promise<RouteResult> {
  const parsed = v.safeParse(createSessionBodySchema, ctx.body);
  if (!parsed.success) {
    return { status: 400, body: { ok: false, error: parsed.issues[0]?.message ?? "invalid create-session body" } };
  }
  const body = parsed.output;
  // createSession validates domain rules (length, control chars, thinking
  // level); the schema above guarantees every field is a string or absent.
  const created = await createSession(ctx.deps.herdr, {
    cwd: body.cwd ?? "",
    model: body.model ?? "",
    name: body.name,
    thinkingLevel: body.thinkingLevel,
    initialPrompt: body.initialPrompt,
    agent: body.agent,
  });
  return { status: 200, body: { ok: true, ...created } };
}

async function controlRoute(ctx: RouteContext): Promise<RouteResult> {
  let paneId: string;
  try {
    paneId = decodeURIComponent(ctx.params.paneId ?? "");
  } catch {
    return { status: 400, body: { ok: false, error: "invalid pane id" } };
  }
  const parsed = v.safeParse(controlBodySchema, ctx.body);
  if (!parsed.success) {
    return { status: 400, body: { ok: false, error: "unknown control action" } };
  }
  const body = parsed.output;
  await controlSession(ctx.deps.herdr, { paneId, action: body.action, text: body.text });
  return { status: 200, body: { ok: true } };
}
