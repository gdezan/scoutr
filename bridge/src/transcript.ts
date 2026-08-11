import { appendFile, open, readFile, stat } from "node:fs/promises";
import { randomBytes } from "node:crypto";

/**
 * The one parser for pi session files (JSONL, version 3).
 *
 * Every consumer — the chat transcript, the session catalog list, the board
 * card — reads through this module, so a new record type is understood by all
 * three in one edit. `Transcript` is deliberately format-neutral: it names what
 * a transcript *is*, not what pi's JSONL calls it, so a second agent backend
 * can produce one without reshaping its data into pi's vocabulary.
 *
 * Format observed on this machine (~/.pi/agent/sessions/<project>/<ts>_<uuid>.jsonl):
 *   {"type":"session","version":3,"id":"...","timestamp":"...","cwd":"..."}
 *   {"type":"session_info","id":"...","parentId":null,"timestamp":"...","name":"..."}
 *   {"type":"model_change","id":"...","parentId":null,"timestamp":"...","provider":"...","modelId":"..."}
 *   {"type":"thinking_level_change",...}
 *   {"type":"custom","customType":"...","data":{...}}
 *   {"type":"message","id":"...","parentId":"...","timestamp":"...","message":{role, content, ...}}
 *
 * Reads are read-only; the single write is [writeSessionTitle], which appends
 * the `session_info` record this parser reads back as [Transcript.title].
 */

export type TranscriptRole = "user" | "assistant" | "toolResult" | "system" | "bashExecution" | string;

export interface TextBlock {
  type: "text";
  text: string;
}

export interface ThinkingBlock {
  type: "thinking";
  thinking: string;
}

export interface ToolCallBlock {
  type: "toolCall";
  id: string;
  name: string;
  arguments: unknown;
}

export type ContentBlock = TextBlock | ThinkingBlock | ToolCallBlock | { type: string; [key: string]: unknown };

export interface TokenUsage {
  input?: number;
  output?: number;
  cacheRead?: number;
  cacheWrite?: number;
  totalTokens?: number;
  cost?: Record<string, number>;
}

export interface TranscriptEntry {
  /** Entry id in the session file (stable, append-only cursor). */
  entryId: string;
  parentId: string | null;
  timestamp: string;
  role: TranscriptRole;
  content: ContentBlock[];
  /** Tool call id for toolResult entries. */
  toolCallId?: string;
  toolName?: string;
  isError?: boolean;
  /** Structured tool-result details (e.g. ask_user_question answers). */
  details?: unknown;
  stopReason?: string;
  model?: string;
  usage?: TokenUsage;
}

export interface Transcript {
  version: number;
  id: string;
  cwd: string;
  timestamp: string;
  entries: TranscriptEntry[];
  /** Active provider-qualified model, updated by every model_change record. */
  model: string | null;
  thinkingLevel: string | null;
  /** Last entry id — usable as an incremental cursor. Set in every read mode. */
  lastEntryId: string | null;
  /** User-assigned name, from the newest session_info record; null if unnamed. */
  title: string | null;
  /** Single-line text of the first user turn; "" when the transcript has none. */
  preview: string;
}

export interface TranscriptReadOpts {
  /**
   * Keep only the last N entries. [readTranscript] serves this from a bounded
   * window at the end of the file, so the cost does not grow with the file.
   * Records before that window are unseen, so `id`/`cwd` may be empty.
   */
  tail?: number;
  /**
   * Metadata only: no entries are retained. [readTranscript] serves this from
   * bounded windows at each end of the file — the header and first turn live in
   * the head, the title and active model in the tail.
   */
  metadataOnly?: boolean;
}

/** Bytes read from the start of the file in metadataOnly mode. */
export const HEAD_WINDOW_BYTES = 128 * 1024;
/** Bytes read from the end of the file in tail and metadataOnly modes. */
export const TAIL_WINDOW_BYTES = 64 * 1024;

const MAX_PREVIEW_LENGTH = 240;
/** Longest stored session title; the catalog validates renames against it too. */
export const MAX_SESSION_TITLE_LENGTH = 100;

export function parseTranscript(text: string, opts: TranscriptReadOpts = {}): Transcript {
  const transcript: Transcript = {
    version: 3,
    id: "",
    cwd: "",
    timestamp: "",
    entries: [],
    model: null,
    thinkingLevel: null,
    lastEntryId: null,
    title: null,
    preview: "",
  };
  const keepEntries = opts.metadataOnly !== true;

  for (const rawLine of text.split("\n")) {
    const line = rawLine.trim();
    if (line.length === 0) continue;
    let record: Record<string, unknown>;
    try {
      record = JSON.parse(line) as Record<string, unknown>;
    } catch {
      continue; // tolerate stray lines in a live-growing file
    }

    const type = record.type;
    if (type === "session") {
      transcript.version = (record.version as number) ?? transcript.version;
      transcript.id = (record.id as string) ?? "";
      transcript.cwd = (record.cwd as string) ?? "";
      transcript.timestamp = (record.timestamp as string) ?? "";
      continue;
    }
    if (type === "session_info") {
      if (typeof record.name === "string") {
        transcript.title = collapse(record.name).slice(0, MAX_SESSION_TITLE_LENGTH) || null;
      }
      continue;
    }
    if (type === "message") {
      const entry = parseMessageRecord(record);
      if (entry) {
        if (keepEntries) transcript.entries.push(entry);
        transcript.lastEntryId = entry.entryId;
        if (!transcript.preview && entry.role === "user") {
          transcript.preview = collapse(joinContentBlocks(entry)).slice(0, MAX_PREVIEW_LENGTH);
        }
      }
      continue;
    }
    if (type === "model_change") {
      const provider = typeof record.provider === "string" ? record.provider : "";
      const modelId = typeof record.modelId === "string" ? record.modelId : "";
      transcript.model = provider && modelId ? `${provider}/${modelId}` : null;
      continue;
    }
    if (type === "thinking_level_change") {
      transcript.thinkingLevel = typeof record.thinkingLevel === "string" ? record.thinkingLevel : null;
      continue;
    }
    // Custom records are handled by feature-specific parsers, not the transcript.
  }

  if (opts.tail !== undefined && transcript.entries.length > opts.tail) {
    transcript.entries = transcript.entries.slice(-opts.tail);
  }
  return transcript;
}

function parseMessageRecord(record: Record<string, unknown>): TranscriptEntry | null {
  const entryId = typeof record.id === "string" ? record.id : "";
  if (!entryId) return null;
  const parentId = typeof record.parentId === "string" ? record.parentId : null;
  const timestamp = typeof record.timestamp === "string" ? record.timestamp : "";
  const message = record.message;
  if (!message || typeof message !== "object" || Array.isArray(message)) return null;
  const msg = message as Record<string, unknown>;

  const role = typeof msg.role === "string" ? msg.role : "unknown";
  const entry: TranscriptEntry = {
    entryId,
    parentId,
    timestamp,
    role,
    content: normalizeContent(msg.content),
  };

  if (typeof msg.toolCallId === "string") entry.toolCallId = msg.toolCallId;
  if (typeof msg.toolName === "string") entry.toolName = msg.toolName;
  if (typeof msg.isError === "boolean") entry.isError = msg.isError;
  if (msg.details && typeof msg.details === "object") entry.details = msg.details;
  if (typeof msg.stopReason === "string") entry.stopReason = msg.stopReason;
  if (typeof msg.model === "string") entry.model = msg.model;
  if (msg.usage && typeof msg.usage === "object") {
    const usage = msg.usage as Record<string, unknown>;
    entry.usage = {};
    for (const key of ["input", "output", "cacheRead", "cacheWrite", "totalTokens"] as const) {
      const value = usage[key];
      if (typeof value === "number") entry.usage[key] = value;
    }
    if (usage.cost && typeof usage.cost === "object") {
      entry.usage.cost = usage.cost as Record<string, number>;
    }
  }
  return entry;
}

function normalizeContent(content: unknown): ContentBlock[] {
  if (typeof content === "string") {
    return content.length > 0 ? [{ type: "text", text: content }] : [];
  }
  if (!Array.isArray(content)) return [];
  return content
    .filter((block): block is Record<string, unknown> => !!block && typeof block === "object")
    .map((block) => {
      const type = typeof block.type === "string" ? block.type : "unknown";
      if (type === "text" && typeof block.text === "string") {
        return { type, text: block.text };
      }
      if (type === "thinking" && typeof block.thinking === "string") {
        return { type, thinking: block.thinking };
      }
      if (type === "toolCall") {
        return {
          type,
          id: typeof block.id === "string" ? block.id : "",
          name: typeof block.name === "string" ? block.name : "",
          arguments: block.arguments,
        };
      }
      return block as ContentBlock;
    });
}

export interface SessionFileInfo {
  path: string;
  exists: boolean;
  size: number;
  mtimeMs: number;
}

export async function inspectSessionFile(path: string): Promise<SessionFileInfo> {
  try {
    const info = await stat(path);
    // stat.mtimeMs is fractional; round to an integer millis for JSON consumers.
    return { path, exists: true, size: info.size, mtimeMs: Math.round(info.mtimeMs) };
  } catch {
    return { path, exists: false, size: 0, mtimeMs: 0 };
  }
}

/**
 * Read a session file in one of three modes (see [TranscriptReadOpts]). Only the default
 * mode reads the whole file; `tail` and `metadataOnly` read fixed byte windows,
 * so they stay cheap on multi-megabyte transcripts and on directory-wide scans.
 */
export async function readTranscript(path: string, opts: TranscriptReadOpts = {}): Promise<Transcript> {
  if (opts.metadataOnly) return parseTranscript(await readWindows(path, { includeHead: true }), opts);
  if (opts.tail !== undefined) return parseTranscript(await readWindows(path, { includeHead: false }), opts);
  return parseTranscript(await readFile(path, "utf8"), opts);
}

/**
 * The tail window, optionally preceded by the head window. A window that does
 * not start at a line boundary begins with a partial record; that fragment is
 * dropped rather than left to fail JSON parsing by luck. When the two windows
 * meet — a file barely larger than the head window — they are joined seam to
 * seam instead, so no record is lost to the boundary.
 */
async function readWindows(path: string, { includeHead }: { includeHead: boolean }): Promise<string> {
  const handle = await open(path, "r");
  try {
    const info = await handle.stat();
    const size = Math.max(0, Number(info.size));
    const headSize = includeHead ? Math.min(size, HEAD_WINDOW_BYTES) : 0;
    const tailStart = Math.max(headSize, size - TAIL_WINDOW_BYTES);
    const tailSize = Math.max(0, size - tailStart);

    const head = includeHead ? await readSlice(handle, 0, headSize) : "";
    const tail = await readSlice(handle, tailStart, tailSize);
    if (tailStart === headSize) return `${head}${tail}`; // contiguous: the whole file
    return `${head}\n${dropPartialFirstLine(tail)}`;
  } finally {
    await handle.close();
  }
}

async function readSlice(
  handle: Awaited<ReturnType<typeof open>>,
  start: number,
  length: number,
): Promise<string> {
  if (length <= 0) return "";
  const buffer = Buffer.alloc(length);
  await handle.read(buffer, 0, length, start);
  return buffer.toString("utf8");
}

function dropPartialFirstLine(text: string): string {
  const newline = text.indexOf("\n");
  return newline === -1 ? "" : text.slice(newline + 1);
}

/**
 * Name a session. pi stores the name as an appended `session_info` record
 * rather than a rewrite, so this never races a live agent writing the file.
 */
export async function writeSessionTitle(path: string, title: string): Promise<void> {
  const record = {
    type: "session_info",
    id: randomBytes(4).toString("hex"),
    parentId: null,
    timestamp: new Date().toISOString(),
    name: title,
  };
  await appendFile(path, `${JSON.stringify(record)}\n`, "utf8");
}

/** Extract plain text from a parsed entry for previews/notifications. */
export function entryText(entry: TranscriptEntry, maxLength = 280): string {
  const text = collapse(joinContentBlocks(entry));
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

function joinContentBlocks(entry: TranscriptEntry): string {
  const parts: string[] = [];
  for (const block of entry.content) {
    if (block.type === "text" && "text" in block) parts.push((block as TextBlock).text);
    if (block.type === "thinking") continue;
    if (block.type === "toolCall" && "name" in block) {
      parts.push(`[${(block as ToolCallBlock).name}]`);
    }
  }
  return parts.join("\n");
}

function collapse(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}
