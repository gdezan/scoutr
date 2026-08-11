import { basename } from "node:path";
import { entryText, inspectSessionFile, readTranscript, type Transcript } from "./transcript.js";

/**
 * Bounded per-agent board detail: the active model and the latest meaningful
 * transcript line. Reads through [readTranscript]'s tail mode so only the end
 * of each session file is touched, never the full transcript, and is memoized
 * by (path, mtime, size) so the 3s board poll does not re-read unchanged files.
 */

/** Entries kept from the tail window — the card shows one, with room to skip noise. */
const TAIL_ENTRIES = 40;
const MAX_ACTIVITY_LENGTH = 160;
const MEMO_CAP = 128;

export interface BoardDetail {
  model: string | null;
  /** Latest meaningful line (user/agent text or tool call). */
  latestActivity: string;
  /** Epoch ms of the entry that produced [latestActivity]. */
  latestActivityAtMs: number | null;
}

export class BoardDetailCache {
  private readonly memo = new Map<string, { mtimeMs: number; size: number; detail: BoardDetail }>();

  /** Read the bounded tail of [path]; unknown or unreadable files return null. */
  async detailFor(path: string): Promise<BoardDetail | null> {
    const info = await inspectSessionFile(path);
    if (!info.exists) return null;
    const cached = this.memo.get(path);
    if (cached && cached.mtimeMs === info.mtimeMs && cached.size === info.size) {
      return cached.detail;
    }
    const transcript = await readTranscript(path, { tail: TAIL_ENTRIES }).catch(() => null);
    if (!transcript) return null;
    const detail = deriveBoardDetail(transcript, info.mtimeMs);
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

/** Model + latest meaningful line from a transcript tail. */
export function deriveBoardDetail(transcript: Transcript, mtimeMs: number): BoardDetail {
  for (const entry of [...transcript.entries].reverse()) {
    // entryText's own cap is well above MAX_ACTIVITY_LENGTH, so cleanActivity
    // is what actually bounds the card line.
    const text = cleanActivity(entryText(entry));
    if (!isMeaningful(text)) continue;
    const at = Date.parse(entry.timestamp);
    return {
      model: transcript.model,
      latestActivity: text,
      latestActivityAtMs: Number.isFinite(at) ? at : null,
    };
  }
  // No meaningful entry in the window: fall back to the file mtime so cards
  // still show recency.
  return { model: transcript.model, latestActivity: "", latestActivityAtMs: mtimeMs };
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

export function fileName(path: string): string {
  return basename(path);
}
