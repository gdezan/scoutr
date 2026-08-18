import { basename, isAbsolute, relative, resolve } from "node:path";
import { canonicalPath } from "../dirs.js";
import { entryText, inspectSessionFile, type SessionFileInfo, type Transcript, type TranscriptEntry } from "../transcript.js";
import type { QuestionEntry } from "../questions.js";
import { backendForSessionPath } from "../agents/registry.js";
import type { ControlAction } from "../agents/types.js";
import { createSession, controlSession, SessionsError } from "../sessions.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

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

async function readTranscriptMemoized(
  target: string,
  backend: NonNullable<ReturnType<typeof backendForSessionPath>>,
  info: SessionFileInfo,
): Promise<Transcript> {
  const cached = transcriptMemo.get(target);
  if (cached && cached.mtimeMs === info.mtimeMs && cached.size === info.size) {
    return cached.transcript;
  }
  const transcript = await backend.readTranscript(target);
  transcriptMemo.set(target, { mtimeMs: info.mtimeMs, size: info.size, transcript });
  if (transcriptMemo.size > TRANSCRIPT_MEMO_CAP) {
    const oldest = transcriptMemo.keys().next().value;
    if (oldest !== undefined) transcriptMemo.delete(oldest);
  }
  return transcript;
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
    return { path: target, agentKind: backend.id, name: basename(target), exists: false, since, entries: [], questions: [], model: null, thinkingLevel: null, lastEntryId: null, mtimeMs: 0 };
  }
  const session = await readTranscriptMemoized(target, backend, info);
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
    mtimeMs: info.mtimeMs,
  };
}

async function createSessionRoute(ctx: RouteContext): Promise<RouteResult> {
  const body = ctx.body;
  // createSession validates; one authenticated call creates the pane and
  // delivers the first prompt (optional initialPrompt, thinkingLevel).
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
  const body = ctx.body;
  if (typeof body.action !== "string" || !(CONTROL_ACTIONS as readonly string[]).includes(body.action)) {
    return { status: 400, body: { ok: false, error: `unknown control action: ${String(body.action)}` } };
  }
  await controlSession(ctx.deps.herdr, { paneId, action: body.action as ControlAction, text: body.text });
  return { status: 200, body: { ok: true } };
}
