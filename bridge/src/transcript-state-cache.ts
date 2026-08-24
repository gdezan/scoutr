import type { AgentBackend } from "./agents/types.js";
import type { Transcript } from "./transcript.js";

/**
 * Exact model/thinking state for a transcript, memoized by file revision.
 *
 * Both the board card and a bounded Chat page show display entries from a
 * tail window, which can sit past the model_change record that is still in
 * force. The state read is metadata-only — no entry is normalized — so it can
 * afford to be exact, and a growing file is advanced by reading only the
 * appended range instead of the whole file again.
 */

/** Re-read this overlap when a growing file is scanned incrementally. */
const STATE_OVERLAP_BYTES = 64 * 1024;
const STATE_MEMO_CAP = 128;

export interface TranscriptState {
  mtimeMs: number;
  size: number;
  model: string | null;
  thinkingLevel: string | null;
  /** A model_change record was actually seen; otherwise `model` is only a guess. */
  modelObservationSeen: boolean;
  thinkingLevelObservationSeen: boolean;
}

export class TranscriptStateCache {
  private readonly memo = new Map<string, TranscriptState>();

  /** Exact state at revision [info], read incrementally when the file only grew. */
  async read(
    path: string,
    backend: AgentBackend,
    info: { mtimeMs: number; size: number },
  ): Promise<TranscriptState> {
    const cached = this.memo.get(path);
    if (cached && cached.mtimeMs === info.mtimeMs && cached.size === info.size) return cached;

    let state: Transcript;
    let modelObservationSeen: boolean;
    let thinkingLevelObservationSeen: boolean;
    if (cached && info.size > cached.size && info.mtimeMs >= cached.mtimeMs) {
      state = await backend.readTranscriptState(path, Math.max(0, cached.size - STATE_OVERLAP_BYTES));
      modelObservationSeen = state.modelObservationSeen === true;
      thinkingLevelObservationSeen = state.thinkingLevelObservationSeen === true;
      if (!modelObservationSeen) state.model = cached.model;
      if (!thinkingLevelObservationSeen) state.thinkingLevel = cached.thinkingLevel;
      modelObservationSeen ||= cached.modelObservationSeen;
      thinkingLevelObservationSeen ||= cached.thinkingLevelObservationSeen;
    } else {
      state = await backend.readTranscriptState(path);
      modelObservationSeen = state.modelObservationSeen === true;
      thinkingLevelObservationSeen = state.thinkingLevelObservationSeen === true;
    }
    const next: TranscriptState = {
      mtimeMs: info.mtimeMs,
      size: info.size,
      model: state.model,
      thinkingLevel: state.thinkingLevel,
      modelObservationSeen,
      thinkingLevelObservationSeen,
    };
    this.memo.set(path, next);
    if (this.memo.size > STATE_MEMO_CAP) {
      const oldest = this.memo.keys().next().value;
      if (oldest !== undefined) this.memo.delete(oldest);
    }
    return next;
  }

  delete(path: string): void {
    this.memo.delete(path);
  }

  /** Forget every memo entry for a path outside [knownPaths]. */
  prune(knownPaths: ReadonlySet<string>): void {
    for (const path of this.memo.keys()) {
      if (!knownPaths.has(path)) this.memo.delete(path);
    }
  }
}
