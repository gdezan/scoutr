import { inspectSessionFile } from "./transcript.js";
import { TranscriptStateCache } from "./transcript-state-cache.js";
import type { AgentBackend } from "./agents/types.js";
import type { QuestionEntry } from "./questions.js";

/**
 * Session state a bounded Chat page cannot derive from the entries it shows.
 *
 * A `limit`-only read serves its display entries from a tail window, but the
 * response still has to be truthful about the whole session: the model in
 * force may have been set thousands of entries ago, and an ask the user
 * escaped in the terminal keeps Chat's composer locked from wherever it sits
 * in the file. Both are read through their own cheap seams — a metadata-only
 * state scan and an ask-record scan — and memoized by file revision, so the
 * display read stays independently bounded and a poll on an unchanged file
 * costs one stat.
 */

export interface SessionDerivedState {
  /** Model observed over the whole file; meaningful only when [modelObservationSeen]. */
  model: string | null;
  thinkingLevel: string | null;
  /** False leaves whatever the display read itself observed in force. */
  modelObservationSeen: boolean;
  thinkingLevelObservationSeen: boolean;
  /** Every question card of the session, answered and open. */
  questions: QuestionEntry[];
}

interface DerivedMemoEntry {
  mtimeMs: number;
  size: number;
  /** Out-of-transcript question state (Claude's sidecar); "" when the backend has none. */
  questionStamp: string;
  state: SessionDerivedState;
}

/** Open chats, not every known session — the board keeps its own cache. */
const DERIVED_MEMO_CAP = 8;

export class SessionDerivedStateCache {
  private readonly memo = new Map<string, DerivedMemoEntry>();
  private readonly stateCache = new TranscriptStateCache();

  /** Derived state at revision [info]. Rejects when question state cannot be read. */
  async stateFor(
    path: string,
    backend: AgentBackend,
    info: { mtimeMs: number; size: number },
  ): Promise<SessionDerivedState> {
    // Stamped before the reads: a sidecar that changes mid-derivation then
    // mismatches on the next poll instead of being memoized as current.
    const stamp = questionStamp(backend, path);
    const cached = this.memo.get(path);
    if (
      cached
      && cached.mtimeMs === info.mtimeMs
      && cached.size === info.size
      && cached.questionStamp === stamp
    ) {
      return cached.state;
    }
    // Question state is not best-effort: a swallowed failure would return a
    // page that unlocks a composer the agent is still blocking on, so the
    // read failure surfaces as the session read's failure instead.
    const questions = await backend.readQuestions(path);
    // Model and thinking level are: a null field is honest, a stale one is not.
    const exact = await this.stateCache.read(path, backend, info).catch(() => null);
    const state: SessionDerivedState = {
      model: exact?.model ?? null,
      thinkingLevel: exact?.thinkingLevel ?? null,
      modelObservationSeen: exact?.modelObservationSeen === true,
      thinkingLevelObservationSeen: exact?.thinkingLevelObservationSeen === true,
      questions,
    };
    const after = await inspectSessionFile(path);
    // Never memoize against a revision this derivation did not actually read.
    if (after.exists && after.mtimeMs === info.mtimeMs && after.size === info.size) {
      this.memo.set(path, { mtimeMs: info.mtimeMs, size: info.size, questionStamp: stamp, state });
      if (this.memo.size > DERIVED_MEMO_CAP) {
        const oldest = this.memo.keys().next().value;
        if (oldest !== undefined) {
          this.memo.delete(oldest);
          this.stateCache.delete(oldest);
        }
      }
    }
    return state;
  }
}

/** Out-of-transcript question state, for backends that keep any. */
function questionStamp(backend: AgentBackend, path: string): string {
  try {
    return backend.questionStateStamp?.(path) ?? "";
  } catch {
    return "";
  }
}
