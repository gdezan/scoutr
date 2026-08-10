import { readFile, stat } from "node:fs/promises";

/**
 * Parser for pi session files (JSONL, version 3).
 *
 * Format observed on this machine (~/.pi/agent/sessions/<project>/<ts>_<uuid>.jsonl):
 *   {"type":"session","version":3,"id":"...","timestamp":"...","cwd":"..."}
 *   {"type":"model_change","id":"...","parentId":null,"timestamp":"...","provider":"...","modelId":"..."}
 *   {"type":"thinking_level_change",...}
 *   {"type":"custom","customType":"...","data":{...}}
 *   {"type":"message","id":"...","parentId":"...","timestamp":"...","message":{role, content, ...}}
 *
 * The bridge reads these files read-only to power the chat view.
 */

export type PiRole = "user" | "assistant" | "toolResult" | "system" | "bashExecution" | string;

export interface PiTextBlock {
  type: "text";
  text: string;
}

export interface PiThinkingBlock {
  type: "thinking";
  thinking: string;
}

export interface PiToolCallBlock {
  type: "toolCall";
  id: string;
  name: string;
  arguments: unknown;
}

export type PiContentBlock = PiTextBlock | PiThinkingBlock | PiToolCallBlock | { type: string; [key: string]: unknown };

export interface PiMessageEntry {
  /** Entry id in the session file (stable, append-only cursor). */
  entryId: string;
  parentId: string | null;
  timestamp: string;
  role: PiRole;
  content: PiContentBlock[];
  /** Tool call id for toolResult messages. */
  toolCallId?: string;
  toolName?: string;
  isError?: boolean;
  stopReason?: string;
  model?: string;
  usage?: {
    input?: number;
    output?: number;
    cacheRead?: number;
    cacheWrite?: number;
    totalTokens?: number;
    cost?: Record<string, number>;
  };
}

export interface PiSession {
  version: number;
  id: string;
  cwd: string;
  timestamp: string;
  entries: PiMessageEntry[];
  /** Active provider-qualified model, updated by every model_change record. */
  model: string | null;
  thinkingLevel: string | null;
  /** Last entry id — usable as an incremental cursor. */
  lastEntryId: string | null;
}

export function parsePiSession(text: string): PiSession {
  const session: PiSession = {
    version: 3,
    id: "",
    cwd: "",
    timestamp: "",
    entries: [],
    model: null,
    thinkingLevel: null,
    lastEntryId: null,
  };

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
      session.version = (record.version as number) ?? session.version;
      session.id = (record.id as string) ?? "";
      session.cwd = (record.cwd as string) ?? "";
      session.timestamp = (record.timestamp as string) ?? "";
      continue;
    }
    if (type === "message") {
      const entry = parseMessageRecord(record);
      if (entry) {
        session.entries.push(entry);
        session.lastEntryId = entry.entryId;
      }
      continue;
    }
    if (type === "model_change") {
      const provider = typeof record.provider === "string" ? record.provider : "";
      const modelId = typeof record.modelId === "string" ? record.modelId : "";
      session.model = provider && modelId ? `${provider}/${modelId}` : null;
      continue;
    }
    if (type === "thinking_level_change") {
      session.thinkingLevel = typeof record.thinkingLevel === "string" ? record.thinkingLevel : null;
      continue;
    }
    // Custom records are handled by feature-specific parsers, not the chat transcript.
  }

  return session;
}

function parseMessageRecord(record: Record<string, unknown>): PiMessageEntry | null {
  const entryId = typeof record.id === "string" ? record.id : "";
  if (!entryId) return null;
  const parentId = typeof record.parentId === "string" ? record.parentId : null;
  const timestamp = typeof record.timestamp === "string" ? record.timestamp : "";
  const message = record.message;
  if (!message || typeof message !== "object" || Array.isArray(message)) return null;
  const msg = message as Record<string, unknown>;

  const role = typeof msg.role === "string" ? msg.role : "unknown";
  const entry: PiMessageEntry = {
    entryId,
    parentId,
    timestamp,
    role,
    content: normalizeContent(msg.content),
  };

  if (typeof msg.toolCallId === "string") entry.toolCallId = msg.toolCallId;
  if (typeof msg.toolName === "string") entry.toolName = msg.toolName;
  if (typeof msg.isError === "boolean") entry.isError = msg.isError;
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

function normalizeContent(content: unknown): PiContentBlock[] {
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
      return block as PiContentBlock;
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

export async function readPiSessionFile(path: string): Promise<PiSession> {
  const text = await readFile(path, "utf8");
  return parsePiSession(text);
}

/** Extract plain text from a parsed entry for previews/notifications. */
export function entryText(entry: PiMessageEntry, maxLength = 280): string {
  const parts: string[] = [];
  for (const block of entry.content) {
    if (block.type === "text" && "text" in block) parts.push((block as PiTextBlock).text);
    if (block.type === "thinking") continue;
    if (block.type === "toolCall" && "name" in block) {
      const call = block as PiToolCallBlock;
      parts.push(`[${call.name}]`);
    }
  }
  const text = parts.join("\n").replace(/\s+/g, " ").trim();
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}
