import { basename } from "node:path";
import { open } from "node:fs/promises";

/**
 * Bounded per-agent board detail: the active model and the latest meaningful
 * transcript line. Reads only the tail of each session file (capped bytes),
 * never the full transcript, and is memoized by (path, mtime) so the 3s board
 * poll does not re-read unchanged files.
 */

const MAX_TAIL_BYTES = 64 * 1024;
const MAX_ACTIVITY_LENGTH = 160;
const MEMO_CAP = 128;

export interface BoardDetail {
  model: string | null;
  /** Latest meaningful line (user/agent text, tool use, or status). */
  latestActivity: string;
  /** Epoch ms of the record that produced [latestActivity]. */
  latestActivityAtMs: number | null;
}

export class BoardDetailCache {
  private readonly memo = new Map<string, { mtimeMs: number; size: number; detail: BoardDetail }>();

  /** Read the bounded tail of [path]; unknown or unreadable files return null. */
  async detailFor(path: string): Promise<BoardDetail | null> {
    const entry = await readBoundedTail(path).catch(() => null);
    if (!entry) return null;
    const cached = this.memo.get(path);
    if (cached && cached.mtimeMs === entry.mtimeMs && cached.size === entry.size) {
      return cached.detail;
    }
    const detail = deriveBoardDetail(entry.text, entry.mtimeMs);
    this.memo.set(path, { mtimeMs: entry.mtimeMs, size: entry.size, detail });
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

interface TailEntry {
  path: string;
  mtimeMs: number;
  size: number;
  text: string;
}

async function readBoundedTail(path: string): Promise<TailEntry> {
  const handle = await open(path, "r");
  try {
    const info = await handle.stat();
    const size = Math.max(0, Number(info.size));
    const start = Math.max(0, size - MAX_TAIL_BYTES);
    const length = size - start;
    const buffer = Buffer.alloc(length);
    if (length > 0) await handle.read(buffer, 0, length, start);
    return { path, mtimeMs: info.mtimeMs, size, text: buffer.toString("utf8") };
  } finally {
    await handle.close();
  }
}

/** Extract model + latest meaningful line from a bounded session tail. */
export function deriveBoardDetail(text: string, mtimeMs: number): BoardDetail {
  let model: string | null = null;
  let latestActivity = "";
  let latestActivityAtMs: number | null = null;

  for (const rawLine of text.split("\n")) {
    let record: Record<string, unknown>;
    try {
      record = JSON.parse(rawLine) as Record<string, unknown>;
    } catch {
      continue;
    }
    const timestamp = typeof record.timestamp === "string" ? Date.parse(record.timestamp) : Number.NaN;
    const at = Number.isFinite(timestamp) ? timestamp : null;

    if (record.type === "model_change") {
      const provider = typeof record.provider === "string" ? record.provider : "";
      const modelId = typeof record.modelId === "string" ? record.modelId : "";
      if (provider && modelId) model = `${provider}/${modelId}`;
      continue;
    }
    if (record.type === "message") {
      const text = messageText(record.message);
      if (text && isMeaningful(text)) {
        latestActivity = cleanActivity(text);
        latestActivityAtMs = at;
      }
      continue;
    }
    if (record.type === "tool_use" || record.type === "tool_result") {
      const text = toolText(record);
      if (text && isMeaningful(text)) {
        latestActivity = cleanActivity(text);
        latestActivityAtMs = at;
      }
    }
  }

  if (!latestActivity) {
    // Fall back to the file mtime so cards still show recency.
    latestActivityAtMs = mtimeMs;
  }
  return { model, latestActivity, latestActivityAtMs };
}

function messageText(message: unknown): string {
  if (!message || typeof message !== "object" || Array.isArray(message)) return "";
  const value = message as Record<string, unknown>;
  const role = typeof value.role === "string" ? value.role : "";
  if (role === "user" && typeof value.content === "string") return value.content;
  if (role === "assistant" && typeof value.content === "string") return value.content;
  if (!Array.isArray(value.content)) return "";
  const parts: string[] = [];
  for (const block of value.content) {
    if (!block || typeof block !== "object" || Array.isArray(block)) continue;
    const b = block as Record<string, unknown>;
    if (b.type === "text" && typeof b.text === "string") parts.push(b.text);
    if (b.type === "tool_use" && typeof b.name === "string") {
      parts.push(`[tool: ${b.name}]`);
    }
  }
  return parts.join(" ");
}

function toolText(record: Record<string, unknown>): string {
  const name = typeof record.name === "string" ? record.name : "";
  const input = record.input;
  if (name) return `[tool: ${name}]`;
  if (input && typeof input === "string") return input;
  return "";
}

/** Skip control/streaming noise like bare "Enter" or single-char echoes. */
function isMeaningful(text: string): boolean {
  const trimmed = text.replace(/\s+/g, " ").trim();
  if (!trimmed) return false;
  if (trimmed.length < 4 && !trimmed.startsWith("[tool")) return false;
  // pi often appends "…" while streaming; still useful as activity.
  return true;
}

/** Clean single-line preview of the activity (also used by tests). */
export function cleanActivity(text: string, limit = MAX_ACTIVITY_LENGTH): string {
  const cleaned = text.replace(/\s+/g, " ").trim();
  return cleaned.length > limit ? `${cleaned.slice(0, limit - 1)}…` : cleaned;
}

export function fileName(path: string): string {
  return basename(path);
}
