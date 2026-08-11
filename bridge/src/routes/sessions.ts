import { basename, isAbsolute, relative, resolve } from "node:path";
import { canonicalPath } from "../dirs.js";
import { entryText, inspectSessionFile, readTranscript, type TranscriptEntry } from "../transcript.js";
import { extractQuestions, type QuestionEntry } from "../questions.js";
import { createSession, controlSession } from "../sessions.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const sessionsRoutes: Route[] = [
  { method: "GET", path: "/api/sessions", handle: readSessionRoute },
  { method: "POST", path: "/api/sessions", handle: createSessionRoute },
  { method: "POST", path: "/api/sessions/:paneId/control", handle: controlRoute },
];

interface SessionReadResult {
  path: string;
  name: string;
  exists: boolean;
  since: string | null;
  entries: TranscriptEntry[];
  /** Structured ask_user_question cards, derived from the same entries. */
  questions: QuestionEntry[];
  model: string | null;
  thinkingLevel: string | null;
  preview?: string;
  lastEntryId: string | null;
  mtimeMs: number;
}

async function readSessionRoute(ctx: RouteContext): Promise<RouteResult> {
  const pathParam = ctx.query.get("path");
  const since = ctx.query.get("since") ?? null;
  if (!pathParam) {
    return { status: 400, body: { ok: false, error: "missing path query parameter" } };
  }
  try {
    const result = await readSession(pathParam, since);
    return { status: 200, body: { ok: true, ...result } };
  } catch (error) {
    // Session reads are file-bound, not herdr-bound: unexpected failures are
    // server faults (500), not upstream (502).
    return {
      status: 500,
      body: { ok: false, error: error instanceof Error ? error.message : String(error) },
    };
  }
}

export async function readSession(pathParam: string, since: string | null): Promise<SessionReadResult> {
  // Only allow absolute paths under the user's pi agent directory (read-only data).
  const agentRoot = canonicalPath(resolve(process.env.PI_CODING_AGENT_DIR?.trim() || `${process.env.HOME}/.pi/agent`));
  const target = canonicalPath(resolve(pathParam));
  const pathFromRoot = relative(agentRoot, target);
  if (!pathFromRoot || pathFromRoot.startsWith("..") || isAbsolute(pathFromRoot)) {
    throw new Error("session path must live under the pi agent directory");
  }
  const info = await inspectSessionFile(target);
  if (!info.exists) {
    return { path: target, name: basename(target), exists: false, since, entries: [], questions: [], model: null, thinkingLevel: null, lastEntryId: null, mtimeMs: 0 };
  }
  const session = await readTranscript(target);
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
    name: basename(target),
    exists: true,
    since: cursor,
    entries,
    questions: extractQuestions(entries),
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
  await controlSession(ctx.deps.herdr, { paneId, action: body.action as never, text: body.text });
  return { status: 200, body: { ok: true } };
}

