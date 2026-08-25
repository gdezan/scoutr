import { mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

/**
 * Claude's pending AskUserQuestion asks, recorded by a hook.
 *
 * Claude Code does not write the assistant record that holds an
 * `AskUserQuestion` tool call until the ask is answered — verified live
 * against 2.1.232: while the questionnaire is on screen the session JSONL
 * still ends at the user's prompt. A transcript-only card could therefore
 * only ever appear after someone answered in the terminal, which is exactly
 * when it is no longer useful.
 *
 * The `PreToolUse` hook does fire while the ask is open, and carries the
 * session id, the `tool_use_id`, and the full questions — the same ids the
 * transcript will later use — so a sidecar written there becomes the very
 * same card once the answers land. `scoutr-bridge hook claude` writes it;
 * `PostToolUse` removes it; see `docs/adr/0006`.
 */
export interface PendingAsk {
  sessionId: string;
  /** The tool call id; question card ids are `${toolUseId}#${index}`. */
  toolUseId: string;
  /** Hook time, ISO — the card's timestamp until the transcript has one. */
  timestamp: string;
  transcriptPath: string;
  /** Raw `tool_input.questions`, parsed by the same reader as the transcript. */
  questions: unknown[];
  /**
   * The prose Claude wrote above the ask, read off the pane (see
   * `ask-preamble.ts`); "" when the pane held none to read.
   */
  preamble?: string;
  /**
   * Whether the pane has been read for a preamble. One attempt per ask: the
   * read is a herdr round trip on a poll path, and a pane that cannot answer
   * it once will not answer it three seconds later either.
   */
  preambleCaptured?: boolean;
}

/**
 * Where sidecars live. Derived from XDG the same way the bridge config is, so
 * the hook process and the daemon agree without sharing any state.
 */
export function pendingAsksDir(): string {
  const configHome = process.env.XDG_CONFIG_HOME?.trim() || join(homedir(), ".config");
  return join(configHome, "scoutr", "pending-asks");
}

/** A sidecar is stale long after any human would have answered it. */
export const PENDING_ASK_MAX_AGE_MS = 24 * 60 * 60 * 1000;

function sidecarPath(sessionId: string): string {
  // Session ids are uuids; anything else could escape the directory.
  if (!/^[A-Za-z0-9._-]{1,128}$/.test(sessionId)) throw new Error("invalid session id");
  return join(pendingAsksDir(), `${sessionId}.json`);
}

export function writePendingAsk(ask: PendingAsk): void {
  const path = sidecarPath(ask.sessionId);
  mkdirSync(pendingAsksDir(), { recursive: true });
  writeFileSync(path, JSON.stringify(ask), "utf8");
}

/**
 * Record the pane-read preamble on the open ask, and that the read happened.
 *
 * The sidecar is re-read first: an ask answered while the pane was being read
 * has already had its file removed, and recreating it here would put the card
 * back on screen. Writing also moves the sidecar's stamp, which is what makes
 * the next Chat read notice there is something new to serve.
 */
export function attachPendingAskPreamble(sessionId: string, preamble: string): void {
  const ask = readPendingAsk(sessionId);
  if (!ask) return;
  try {
    writePendingAsk({ ...ask, preamble, preambleCaptured: true });
  } catch {
    // Background is a nicety; the card it belongs to must still be answerable.
  }
}

export function clearPendingAsk(sessionId: string): void {
  try {
    rmSync(sidecarPath(sessionId), { force: true });
  } catch {
    // The hook must never fail the tool call it is reporting on.
  }
}

/** The open ask of a session, or null when there is none (or it is stale). */
export function readPendingAsk(sessionId: string): PendingAsk | null {
  if (!sessionId) return null;
  let raw: string;
  try {
    raw = readFileSync(sidecarPath(sessionId), "utf8");
  } catch {
    return null; // no open ask — the common case, one failed stat
  }
  let ask: PendingAsk;
  try {
    // SAFETY: the sidecar is written by writePendingAsk with a validated
    // PendingAsk shape, so the cast back to PendingAsk is sound.
    ask = JSON.parse(raw) as PendingAsk;
  } catch {
    return null;
  }
  if (!ask || !ask.toolUseId || !Array.isArray(ask.questions)) return null;
  const age = Date.now() - Date.parse(ask.timestamp);
  if (Number.isFinite(age) && age > PENDING_ASK_MAX_AGE_MS) {
    clearPendingAsk(sessionId);
    return null;
  }
  return ask;
}

/**
 * Cheap fingerprint of a session's sidecar state: absent, or its mtime/size.
 *
 * A sidecar appears and disappears without the transcript file ever changing,
 * so anything memoized by transcript stat alone (the board detail cache) would
 * keep serving an ask that is already answered, or miss one that just opened.
 * One stat per poll is the whole cost of noticing.
 */
export function pendingAskStamp(sessionId: string): string {
  if (!sessionId) return "none";
  try {
    const info = statSync(sidecarPath(sessionId));
    return `${Math.round(info.mtimeMs)}:${info.size}`;
  } catch {
    return "none";
  }
}

/**
 * Drop sidecars left behind by a session that died mid-ask (the PostToolUse
 * hook never ran). Called when the daemon starts, so a phantom card cannot
 * outlive the agent that asked.
 */
export function pruneStalePendingAsks(now = Date.now()): number {
  let removed = 0;
  let names: string[];
  try {
    names = readdirSync(pendingAsksDir());
  } catch {
    return 0;
  }
  for (const name of names) {
    if (!name.endsWith(".json")) continue;
    try {
      // SAFETY: each pending-ask file is written by writePendingAsk with a
      // validated PendingAsk shape, so the cast back to PendingAsk is sound.
      const ask = JSON.parse(readFileSync(join(pendingAsksDir(), name), "utf8")) as PendingAsk;
      const age = now - Date.parse(ask.timestamp);
      if (!Number.isFinite(age) || age <= PENDING_ASK_MAX_AGE_MS) continue;
    } catch {
      // Unreadable sidecar: nothing can use it.
    }
    rmSync(join(pendingAsksDir(), name), { force: true });
    removed += 1;
  }
  return removed;
}
