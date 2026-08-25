/**
 * Detects conversations that died on a failed model call.
 *
 * When the provider keeps 503ing, the agent retries internally and appends
 * one assistant record per failed attempt (`stopReason:"error"`, empty
 * content), then gives up: the transcript just stops growing with an error
 * record last. herdr then reports the pane idle — indistinguishable from a
 * clean finish without reading that tail.
 */
import type { SessionSnapshot } from "../herdr/types.js";
import type { TranscriptEntry } from "../transcript.js";
import { resolveBackendForPane } from "../agents/registry.js";

/** Source of live panes; satisfied by HerdrEventFeed. */
export interface PaneSnapshotSource {
  readonly snapshot: SessionSnapshot | null;
}

/**
 * Returns true when [paneId]'s session transcript ends in a failed model
 * call. Best effort by contract: any resolution failure reads as false, so a
 * missing backend or unreadable file can never turn into a false alarm.
 */
export type ErrorStopProbe = (paneId: string) => Promise<boolean>;

/** Tail window: enough to step back over trailing tool results. */
const TAIL_ENTRIES = 8;

export function makeErrorStopInspector(source: PaneSnapshotSource): ErrorStopProbe {
  return async (paneId) => {
    const snapshot = source.snapshot;
    const pane = snapshot?.panes.find((candidate) => candidate.pane_id === paneId);
    if (!pane) return false;
    const backend = resolveBackendForPane(snapshot, paneId);
    if (!backend || !pane.agent_session) return false;
    try {
      const path = await backend.resolveSessionPath(
        pane.agent_session,
        pane.cwd ?? pane.foreground_cwd ?? undefined,
      );
      // No path yet (agent never wrote a transcript) is a quiet finish, not an error.
      if (!path) return false;
      const transcript = await backend.readTranscript(path, { tail: TAIL_ENTRIES });
      return endsInFailedModelCall(transcript.entries);
    } catch {
      return false;
    }
  };
}

/**
 * True when the last speaking entry of the transcript is an assistant record
 * that ended in `stopReason:"error"`. Trailing tool results and system
 * records are skipped: neither is anyone speaking — they mean work continued
 * past them. A user or bash-execution entry means the conversation moved on
 * normally.
 */
export function endsInFailedModelCall(entries: readonly TranscriptEntry[]): boolean {
  for (let i = entries.length - 1; i >= 0; i -= 1) {
    const entry = entries[i];
    if (entry === undefined) continue;
    if (entry.role === "toolResult" || entry.role === "system") continue;
    return entry.role === "assistant" && entry.stopReason === "error";
  }
  return false;
}
