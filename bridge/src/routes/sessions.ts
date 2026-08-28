import { basename, resolve } from "node:path";
import { canonicalPath } from "../dirs.js";
import {
  entryText,
  inspectSessionFile,
  TAIL_WINDOW_BYTES,
  type SessionFileInfo,
  type Transcript,
  type TranscriptEntry,
} from "../transcript.js";
import type { AgentTask } from "../agent-tasks.js";
import { SessionDerivedStateCache, type SessionDerivedState } from "../session-derived-state.js";
import type { QuestionEntry } from "../questions.js";
import { backendForSessionPath } from "../agents/registry.js";
import type { ControlAction } from "../agents/types.js";
import { createSession, controlSession, SessionsError } from "../sessions.js";
import { buildScoutrContext } from "../agents/scoutr-context.js";
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
  /** Current implementation tasks confirmed by this session. */
  tasks: AgentTask[];
  /** Structured question cards confirmed by this session. */
  questions: QuestionEntry[];
  model: string | null;
  thinkingLevel: string | null;
  preview?: string;
  lastEntryId: string | null;
  mtimeMs: number;
  size: number;
  /** Opaque reverse-history cursor immediately before the oldest returned entry. */
  beforeCursor: string | null;
  /** True when older transcript entries exist before this page. */
  hasMoreBefore: boolean;
}

interface ReverseHistoryCursors {
  beforeCursor: string | null;
  hasMoreBefore: boolean;
}

/** Inclusive page-size bound for GET /api/sessions `limit`. */
const SESSION_PAGE_LIMIT_MIN = 1;
const SESSION_PAGE_LIMIT_MAX = 200;

export interface SessionReadPagination {
  before?: string | null;
  limit?: number | null;
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

/** Model, thinking level, and question state for bounded initial pages. */
const sessionDerivedState = new SessionDerivedStateCache();

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
  const before = ctx.query.get("before") ?? null;
  const limit = parseSessionPageLimit(ctx.query.get("limit"));
  if (!pathParam || !agentKind) {
    return { status: 400, body: { ok: false, error: "missing session key query parameters" } };
  }
  // Session reads are file-bound, not herdr-bound: the deliberate
  // outside-the-store rejection surfaces as its 403 via the dispatcher, and
  // unexpected failures are server faults (502 via the dispatcher's
  // catch-all).
  const result = await readSession(pathParam, since, agentKind, { before, limit });
  return { status: 200, body: { ok: true, ...result } };
}

/**
 * Parse the optional `limit` query. Absent stays null so a request with no
 * pagination parameters remains the legacy full snapshot.
 */
export function parseSessionPageLimit(raw: string | null): number | null {
  if (raw == null) return null;
  if (!/^[1-9]\d*$/.test(raw)) {
    throw new SessionsError("limit must be an integer between 1 and 200", 400);
  }
  const value = Number(raw);
  if (value < SESSION_PAGE_LIMIT_MIN || value > SESSION_PAGE_LIMIT_MAX) {
    throw new SessionsError("limit must be an integer between 1 and 200", 400);
  }
  return value;
}

function emptyHistoryPage(): ReverseHistoryCursors {
  return { beforeCursor: null, hasMoreBefore: false };
}

/**
 * Reverse-history cursors for one chronological page. `beforeCursor` is the
 * oldest returned entry id when older file-order entries exist; Android
 * treats it as opaque.
 */
function reverseHistoryCursors(
  all: TranscriptEntry[],
  page: TranscriptEntry[],
): ReverseHistoryCursors {
  if (page.length === 0) return emptyHistoryPage();
  const oldestId = page[0]!.entryId;
  const oldestIndex = all.findIndex((entry) => entry.entryId === oldestId);
  if (oldestIndex <= 0) return emptyHistoryPage();
  return { beforeCursor: oldestId, hasMoreBefore: true };
}

export async function readSession(
  pathParam: string,
  since: string | null,
  agentKind?: string,
  pagination: SessionReadPagination = {},
): Promise<SessionReadResult> {
  const before = pagination.before ?? null;
  const limit = pagination.limit ?? null;
  if (since && before) {
    throw new SessionsError("since and before cannot be combined", 400);
  }
  if (before && limit == null) {
    throw new SessionsError("before requires limit", 400);
  }
  if (limit != null && (limit < SESSION_PAGE_LIMIT_MIN || limit > SESSION_PAGE_LIMIT_MAX)) {
    throw new SessionsError("limit must be an integer between 1 and 200", 400);
  }
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
    return {
      path: target,
      agentKind: backend.id,
      name: basename(target),
      exists: false,
      since,
      entries: [],
      tasks: [],
      questions: [],
      model: null,
      thinkingLevel: null,
      lastEntryId: null,
      mtimeMs: 0,
      size: 0,
      ...emptyHistoryPage(),
    };
  }
  // The first page of a session is the one read that cannot afford the whole
  // file: nothing is loaded yet, so the newest entries come from a bounded
  // tail window instead. Forward `since`, reverse `before`, and the legacy
  // full snapshot keep reading through the memo below.
  if (!since && !before && limit != null) {
    return readBoundedInitialPage(target, backend, info, limit);
  }
  const read = await readTranscriptMemoized(target, backend, info);
  const session = read.transcript;
  let entries = session.entries;
  let cursor: string | null = since;
  let history = emptyHistoryPage();
  if (before) {
    // Compare by file position, not lexically: pi ids are random hex.
    const cursorIndex = session.entries.findIndex((entry) => entry.entryId === before);
    if (cursorIndex === -1) {
      throw new SessionsError("reverse cursor is no longer in the transcript", 409);
    }
    const start = Math.max(0, cursorIndex - limit!);
    entries = session.entries.slice(start, cursorIndex);
    history = reverseHistoryCursors(session.entries, entries);
  } else if (since) {
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
    tasks: backend.extractTasks(session),
    questions: backend.extractQuestions(session),
    model: session.model,
    thinkingLevel: session.thinkingLevel,
    preview: lastEntry ? entryText(lastEntry, 120) : undefined,
    lastEntryId: session.lastEntryId,
    mtimeMs: read.info.mtimeMs,
    size: read.info.size,
    ...history,
  };
}

/**
 * The newest page of a session, read without parsing the whole transcript.
 *
 * Display entries come from the bounded tail window; the response fields that
 * describe the whole session — model, thinking level, question cards — come
 * from their own cheap scans (see `session-derived-state.ts`). Both are tied
 * to one validated file revision, and a file that moves under the composite
 * read is retried the same finite number of times the memo path uses.
 */
async function readBoundedInitialPage(
  target: string,
  backend: NonNullable<ReturnType<typeof backendForSessionPath>>,
  info: SessionFileInfo,
  limit: number,
): Promise<SessionReadResult> {
  let revision = info;
  let tail!: Transcript;
  let derived!: SessionDerivedState;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    if (attempt > 0) revision = await inspectSessionFile(target);
    // One entry past the page: holding it is what proves older history exists
    // without reading any more of the file.
    tail = await backend.readTranscript(target, { tail: limit + 1 });
    derived = await sessionDerivedState.stateFor(target, backend, revision);
    const after = await inspectSessionFile(target);
    if (revision.mtimeMs === after.mtimeMs && revision.size === after.size) break;
    // A transcript growing through every attempt gets a coherent best effort
    // tied to the revision its last read started from, and is not memoized.
  }
  const droppedFromWindow = tail.entries.length > limit;
  const entries = droppedFromWindow ? tail.entries.slice(tail.entries.length - limit) : tail.entries;
  const lastEntry = entries[entries.length - 1];
  return {
    path: target,
    agentKind: backend.id,
    name: basename(target),
    exists: true,
    since: null,
    entries,
    tasks: derived.tasks,
    questions: derived.questions,
    // The tail window can open after the model_change still in force, so the
    // exact scan wins whenever it observed one.
    model: derived.modelObservationSeen ? derived.model : tail.model,
    thinkingLevel: derived.thinkingLevelObservationSeen ? derived.thinkingLevel : tail.thinkingLevel,
    preview: lastEntry ? entryText(lastEntry, 120) : undefined,
    lastEntryId: tail.lastEntryId,
    mtimeMs: revision.mtimeMs,
    size: revision.size,
    ...boundedHistoryCursors(entries, droppedFromWindow, revision.size),
  };
}

/**
 * Reverse-history cursors for a page cut from a bounded tail window. Older
 * history is proven two ways, neither of which reads more of the file: an
 * entry the window held and the page dropped, or a file too large for the
 * window to have reached its first byte. The size test can claim history that
 * an outsized header alone accounts for; the reverse page that follows comes
 * back empty and corrects it.
 */
function boundedHistoryCursors(
  page: TranscriptEntry[],
  droppedFromWindow: boolean,
  size: number,
): ReverseHistoryCursors {
  const oldest = page[0];
  if (!oldest) return emptyHistoryPage();
  if (!droppedFromWindow && size <= TAIL_WINDOW_BYTES) return emptyHistoryPage();
  return { beforeCursor: oldest.entryId, hasMoreBefore: true };
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
    scoutrContext: await buildScoutrContext(ctx.deps.config),
  }, ctx.deps.workspaceRoots);
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
