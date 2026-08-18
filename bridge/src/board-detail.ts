import { basename } from "node:path";
import { inspectSessionFile, entryText, type Transcript } from "./transcript.js";
import { backendForSessionPath } from "./agents/registry.js";

/**
 * Bounded per-agent board detail: the session title, active model, and latest meaningful
 * transcript line. Reads bounded head/tail windows and is memoized
 * by (path, mtime, size) so the 3s board poll does not re-read unchanged files.
 */

/** Entries kept from the tail window — the card shows one, with room to skip noise. */
const TAIL_ENTRIES = 40;
const MAX_ACTIVITY_LENGTH = 160;
const MEMO_CAP = 128;

export interface BoardDetail {
  /** User-assigned session name, when the transcript has one. */
  title: string | null;
  model: string | null;
  thinkingLevel: string | null;
  /** Latest meaningful line (user/agent text or tool call). */
  latestActivity: string;
  /** Epoch ms of the entry that produced [latestActivity]. */
  latestActivityAtMs: number | null;
}

export class BoardDetailCache {
  private readonly memo = new Map<string, { mtimeMs: number; size: number; detail: BoardDetail }>();

  /** Read bounded transcript windows for [path]; unknown or unreadable files return null. */
  async detailFor(path: string): Promise<BoardDetail | null> {
    const info = await inspectSessionFile(path);
    if (!info.exists) return null;
    const cached = this.memo.get(path);
    if (cached && cached.mtimeMs === info.mtimeMs && cached.size === info.size) {
      return cached.detail;
    }
    const backend = backendForSessionPath(path);
    if (!backend) return null;
    const transcript = await backend.readTranscript(path, { tail: TAIL_ENTRIES }).catch(() => null);
    if (!transcript) return null;
    const metadata = await backend.readTranscript(path, { metadataOnly: true }).catch(() => null);
    // The tail window alone misses a model recorded only near the top of a long
    // session (pi writes one `model_change` at launch), which is why some cards
    // showed no model at all. The metadata read spans both ends, so it answers
    // when the tail cannot.
    const detail = deriveBoardDetail(
      transcript,
      info.mtimeMs,
      metadata?.title,
      metadata?.model ?? null,
      metadata?.thinkingLevel ?? null,
    );
    this.memo.set(path, { mtimeMs: info.mtimeMs, size: info.size, detail });
    if (this.memo.size > MEMO_CAP) {
      const oldest = this.memo.keys().next().value;
      if (oldest !== undefined) this.memo.delete(oldest);
    }
    return detail;
  }

  /** Forget memo entries for paths that no longer exist (called on pane close). */
  prune(knownPaths: ReadonlySet<string>): void {
    for (const path of this.memo.keys()) {
      if (!knownPaths.has(path)) this.memo.delete(path);
    }
  }

  get size(): number {
    return this.memo.size;
  }
}

/** Session title, model, and latest meaningful line from a transcript tail. */
export function deriveBoardDetail(
  transcript: Transcript,
  mtimeMs: number,
  title = transcript.title,
  fallbackModel: string | null = null,
  fallbackThinkingLevel: string | null = null,
): BoardDetail {
  const model = transcript.model ?? fallbackModel;
  const thinkingLevel = transcript.thinkingLevel ?? fallbackThinkingLevel;
  for (const entry of [...transcript.entries].reverse()) {
    // entryText's own cap is well above MAX_ACTIVITY_LENGTH, so cleanActivity
    // is what actually bounds the card line.
    const text = cleanActivity(entryText(entry));
    if (!isMeaningful(text)) continue;
    const at = Date.parse(entry.timestamp);
    return {
      title,
      model,
      thinkingLevel,
      latestActivity: text,
      latestActivityAtMs: Number.isFinite(at) ? at : null,
    };
  }
  // No meaningful entry in the window: fall back to the file mtime so cards
  // still show recency.
  return { title, model, thinkingLevel, latestActivity: "", latestActivityAtMs: mtimeMs };
}

/** Skip control/streaming noise like bare "Enter" or single-char echoes. */
function isMeaningful(text: string): boolean {
  if (!text) return false;
  // Tool calls render as "[name]" and are meaningful however short.
  return text.length >= 4 || text.startsWith("[");
}

/** Clean single-line preview of the activity (also used by the board endpoint). */
export function cleanActivity(text: string, limit = MAX_ACTIVITY_LENGTH): string {
  const cleaned = text.replace(/\s+/g, " ").trim();
  return cleaned.length > limit ? `${cleaned.slice(0, limit - 1)}…` : cleaned;
}
