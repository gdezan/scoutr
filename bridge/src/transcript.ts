import { open, stat } from "node:fs/promises";
import { skillInvocationPreview } from "./skill-invocation.js";

/** Shared transcript types and bounded JSONL file-reading utilities. */

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

/** One run of diff lines, each prefixed with a unified marker (` `, `+`, `-`). */
export interface FileEditHunk {
  /** `@@ -a,b +c,d @@`, or null when the agent reports no line numbers. */
  header: string | null;
  lines: string[];
}

/**
 * A file change the agent made, normalized from whatever patch its CLI wrote.
 * Emitted on the tool-result entry, because that is where every agent records
 * what the edit actually did. See `agents/file-edit.ts`.
 */
export interface FileEditBlock {
  type: "fileEdit";
  path: string;
  changeKind: "create" | "edit" | "delete";
  /** Counted over the whole diff, so they stay truthful when `truncated`. */
  added: number;
  removed: number;
  hunks: FileEditHunk[];
  /** The diff exceeded the inline caps; `hunks` holds its head. */
  truncated: boolean;
}

export interface SkillBlock {
  type: "skill";
  name: string;
  /** The skill body the harness injected; omitted from previews. */
  text: string;
  /**
   * The slash command that re-invokes this, spelled the way its agent expects
   * (`/skill:name` on pi, `/name` on Claude Code). Chat shows it on the chip
   * and Retry sends it, so neither has to know which agent wrote the turn.
   */
  command: string;
}

export type ContentBlock =
  | TextBlock
  | ThinkingBlock
  | ToolCallBlock
  | FileEditBlock
  | SkillBlock
  | { type: string; [key: string]: unknown };
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
  /** Internal parser signal used to merge incremental metadata scans. */
  modelObservationSeen?: boolean;
  /** Internal parser signal used to merge incremental metadata scans. */
  thinkingLevelObservationSeen?: boolean;
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
  /** Read all metadata records instead of only bounded head/tail windows. */
  exactMetadata?: boolean;
  /** Read an append-only byte range, dropping a partial first JSONL record. */
  fromByte?: number;
}

/** Bytes read from the start of the file in metadataOnly mode. */
export const HEAD_WINDOW_BYTES = 128 * 1024;
/** Bytes read from the end of the file in tail and metadataOnly modes. */
export const TAIL_WINDOW_BYTES = 64 * 1024;

const MAX_PREVIEW_LENGTH = 240;
export const MAX_TRANSCRIPT_PREVIEW_LENGTH = MAX_PREVIEW_LENGTH;
/** Longest stored session title. */
export const MAX_SESSION_TITLE_LENGTH = 100;


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
 * Read transcript JSONL in bounded, exact-metadata, or append-range mode.
 * Parsing belongs to the selected agent backend because each agent owns a
 * different record format.
 */
export async function readTranscriptText(path: string, opts: TranscriptReadOpts = {}): Promise<string> {
  if (opts.fromByte !== undefined) return readFromByte(path, opts.fromByte);
  if (opts.metadataOnly && !opts.exactMetadata) return readWindows(path, { includeHead: true });
  if (opts.tail !== undefined) return readWindows(path, { includeHead: false });
  const handle = await open(path, "r");
  try {
    const info = await handle.stat();
    return readSlice(handle, 0, Number(info.size));
  } finally {
    await handle.close();
  }
}

/** Read a recent append-only range while preserving complete JSONL records. */
async function readFromByte(path: string, requestedStart: number): Promise<string> {
  const handle = await open(path, "r");
  try {
    const info = await handle.stat();
    const size = Math.max(0, Number(info.size));
    const start = Math.min(size, Math.max(0, Math.floor(requestedStart)));
    const text = await readSlice(handle, start, size - start);
    if (start === 0 || text.length === 0) return text;
    const previous = await readSlice(handle, start - 1, 1);
    return previous === "\n" ? text : dropPartialFirstLine(text);
  } finally {
    await handle.close();
  }
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
  // A read may return fewer bytes than requested (short read); loop until the
  // window is full or EOF, then slice off any unused tail so no NUL padding
  // reaches the JSON parser.
  let total = 0;
  while (total < length) {
    const { bytesRead } = await handle.read(buffer, total, length - total, start + total);
    if (bytesRead === 0) break;
    total += bytesRead;
  }
  return buffer.subarray(0, total).toString("utf8");
}

function dropPartialFirstLine(text: string): string {
  const newline = text.indexOf("\n");
  return newline === -1 ? "" : text.slice(newline + 1);
}


/** Extract plain text from a parsed entry for previews/notifications. */
export function entryText(entry: TranscriptEntry, maxLength = 280): string {
  const text = collapseTranscriptText(joinContentBlocks(entry));
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

export function joinContentBlocks(entry: TranscriptEntry): string {
  const parts: string[] = [];
  for (const block of entry.content) {
    if (block.type === "skill" && "name" in block && typeof block.name === "string") {
      parts.push(skillInvocationPreview(block.name));
      continue;
    }
    if (block.type === "text" && "text" in block) parts.push((block as TextBlock).text);
    if (block.type === "thinking") continue;
    if (block.type === "toolCall" && "name" in block) {
      parts.push(`[${(block as ToolCallBlock).name}]`);
    }
  }
  return parts.join("\n");
}

export function collapseTranscriptText(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}
