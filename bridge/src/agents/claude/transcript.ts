import {
  collapseTranscriptText,
  MAX_SESSION_TITLE_LENGTH,
  type TextBlock,
  type ThinkingBlock,
  type Transcript,
  type TranscriptEntry,
  type TranscriptReadOpts,
} from "../../transcript.js";

/**
 * The Claude Code JSONL parser (~/.claude/projects/<encoded-cwd>/<session-uuid>.jsonl).
 *
 * Claude's format is a flat append-only event log; the record `type` discriminates:
 *   - `user`        — a real prompt (message.content is a string) or a tool result
 *                     (message.content is an array with a `tool_result` block).
 *   - `assistant`   — API response blocks: `text`, `thinking`, `tool_use`
 *                     (with `id`, `name`, `input`). `message.model` is the model id.
 *   - `system`      — hook output / notices (subtype), no conversation content.
 *   - `attachment`, `file-history-snapshot`, `permission-mode`, `mode`,
 *     `queue-operation`, `custom-title`, ... — metadata; skipped.
 *
 * Unlike pi there is no session header record; identity comes from per-record
 * `sessionId`/`cwd` fields (first record wins for the transcript envelope).
 */
export function parseClaudeTranscript(text: string, opts: TranscriptReadOpts = {}): Transcript {
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
    const timestamp = typeof record.timestamp === "string" ? record.timestamp : "";

    if (!transcript.timestamp && timestamp) transcript.timestamp = timestamp;
    if (!transcript.id && typeof record.sessionId === "string") transcript.id = record.sessionId;
    if (!transcript.cwd && typeof record.cwd === "string") transcript.cwd = record.cwd;

    // Session display names arrive as `custom-title` / `aiTitle` records.
    if (type === "custom-title" || (type === "user" && record.aiTitle)) {
      const title = typeof record.aiTitle === "string"
        ? record.aiTitle
        : typeof (record as { customTitle?: unknown }).customTitle === "string"
          ? ((record as { customTitle: string }).customTitle)
          : "";
      if (title) {
        transcript.title = collapseTranscriptText(title).slice(0, MAX_SESSION_TITLE_LENGTH) || null;
      }
      continue;
    }

    if (type === "user") {
      const entry = parseUserRecord(record);
      if (entry) {
        if (keepEntries) transcript.entries.push(entry);
        transcript.lastEntryId = entry.entryId;
        if (!transcript.preview && entry.role === "user") {
          transcript.preview = collapseTranscriptText(entryTextOf(entry)).slice(0, 240);
        }
      }
      continue;
    }

    if (type === "assistant") {
      const entry = parseAssistantRecord(record);
      if (entry) {
        if (keepEntries) transcript.entries.push(entry);
        transcript.lastEntryId = entry.entryId;
        // Assistant records carry the active model; the newest one wins.
        if (entry.model) transcript.model = entry.model;
      }
      continue;
    }

    // Everything else — system, attachment, file-history-snapshot,
    // permission-mode, queue-operation — is not a conversation turn.
  }

  if (opts.tail !== undefined && transcript.entries.length > opts.tail) {
    transcript.entries = transcript.entries.slice(-opts.tail);
  }
  return transcript;
}

/** A real user prompt (content is a string) or a tool result (tool_result block). */
function parseUserRecord(record: Record<string, unknown>): TranscriptEntry | null {
  const entryId = typeof record.uuid === "string" ? record.uuid : "";
  if (!entryId) return null;
  const parentId = typeof record.parentUuid === "string" ? record.parentUuid : null;
  const timestamp = typeof record.timestamp === "string" ? record.timestamp : "";
  const message = record.message;
  if (!message || typeof message !== "object" || Array.isArray(message)) return null;
  const content = (message as Record<string, unknown>).content;

  // Real prompt: bare string.
  if (typeof content === "string") {
    if (!content.trim()) return null;
    return { entryId, parentId, timestamp, role: "user", content: [{ type: "text", text: content }] };
  }

  // Tool result: array with a tool_result block.
  if (Array.isArray(content)) {
    const blocks = normalizeBlocks(content);
    const result = blocks.find((block) => block.type === "toolResult") as
      | { type: "toolResult"; toolCallId?: string; content?: unknown; isError?: boolean }
      | undefined;
    if (result) {
      const entry: TranscriptEntry = {
        entryId,
        parentId,
        timestamp,
        role: "toolResult",
        content: [],
        toolCallId: result.toolCallId,
        isError: result.isError ?? false,
        details: result.content,
      };
      const text = blockText(result.content);
      if (text) entry.content = [{ type: "text", text }];
      return entry;
    }
  }
  return null;
}

function parseAssistantRecord(record: Record<string, unknown>): TranscriptEntry | null {
  const entryId = typeof record.uuid === "string" ? record.uuid : "";
  if (!entryId) return null;
  const parentId = typeof record.parentUuid === "string" ? record.parentUuid : null;
  const timestamp = typeof record.timestamp === "string" ? record.timestamp : "";
  const message = record.message;
  if (!message || typeof message !== "object" || Array.isArray(message)) return null;
  const msg = message as Record<string, unknown>;
  if (!Array.isArray(msg.content)) return null;

  const entry: TranscriptEntry = {
    entryId,
    parentId,
    timestamp,
    role: "assistant",
    content: normalizeBlocks(msg.content),
    model: typeof msg.model === "string" ? msg.model : undefined,
    stopReason: typeof msg.stop_reason === "string" ? msg.stop_reason : undefined,
  };
  const usage = msg.usage as Record<string, unknown> | undefined;
  if (usage && typeof usage === "object") {
    entry.usage = {};
    if (typeof usage.input_tokens === "number") entry.usage.input = usage.input_tokens;
    if (typeof usage.output_tokens === "number") entry.usage.output = usage.output_tokens;
    if (typeof usage.cache_read_input_tokens === "number") entry.usage.cacheRead = usage.cache_read_input_tokens;
    if (typeof usage.cache_creation_input_tokens === "number") {
      entry.usage.cacheWrite = usage.cache_creation_input_tokens;
    }
  }
  return entry;
}

/** Claude content blocks → shared block vocabulary (tool_use → toolCall). */
function normalizeBlocks(blocks: unknown[]): TranscriptEntry["content"] {
  return blocks
    .filter((block): block is Record<string, unknown> => !!block && typeof block === "object")
    .map((block) => {
      const type = typeof block.type === "string" ? block.type : "unknown";
      if (type === "text" && typeof block.text === "string") {
        return { type: "text", text: block.text } as TextBlock;
      }
      if (type === "thinking" && typeof block.thinking === "string") {
        return { type: "thinking", thinking: block.thinking } as ThinkingBlock;
      }
      if (type === "tool_use") {
        return {
          type: "toolCall" as const,
          id: typeof block.id === "string" ? block.id : "",
          name: typeof block.name === "string" ? block.name : "",
          arguments: block.input,
        };
      }
      if (type === "tool_result") {
        return {
          type: "toolResult" as const,
          toolCallId: typeof block.tool_use_id === "string" ? block.tool_use_id : "",
          isError: block.is_error === true,
          content: block.content,
        };
      }
      return block as TranscriptEntry["content"][number];
    });
}

function blockText(content: unknown): string {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .filter((block): block is Record<string, unknown> => !!block && typeof block === "object")
      .map((block) => (typeof block.text === "string" ? block.text : ""))
      .join("\n");
  }
  return "";
}

function entryTextOf(entry: TranscriptEntry): string {
  const parts: string[] = [];
  for (const block of entry.content) {
    if (block.type === "text" && "text" in block) parts.push((block as TextBlock).text);
    if (block.type === "toolCall" && "name" in block) parts.push(`[${(block as { name: string }).name}]`);
  }
  return parts.join("\n");
}
