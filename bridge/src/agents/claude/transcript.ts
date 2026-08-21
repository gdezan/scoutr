import * as v from "valibot";
import { claudeFileEditResultSchema, fileEditFromClaudeResult } from "../file-edit.js";
import { claudeAnswersSchema, claudeQuestionAnswers } from "./questions.js";
import {
  collapseTranscriptText,
  MAX_SESSION_TITLE_LENGTH,
  type ContentBlock,
  type Transcript,
  type TranscriptEntry,
  type TranscriptReadOpts,
} from "../../transcript.js";
import { peelClaudeCommandInvocation, skillInvocationPreview } from "../../skill-invocation.js";

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
 *
 * Every record is decoded at this single I/O boundary with valibot schemas,
 * so downstream code branches on typed domain values instead of narrowing
 * `unknown` with `typeof`.
 */

const blockSchema = v.looseObject({ type: v.string() });
type DecodedBlock = v.InferOutput<typeof blockSchema>;

const contentSchema = v.union([v.string(), v.array(blockSchema)]);

const toolResultContentSchema = v.union([v.string(), v.array(blockSchema)]);

const messageSchema = v.looseObject({
  role: v.optional(v.string()),
  content: v.optional(v.unknown()),
  model: v.optional(v.string()),
  stop_reason: v.optional(v.string()),
  usage: v.optional(v.unknown()),
});

const toolResultBlockSchema = v.looseObject({
  type: v.literal("tool_result"),
  tool_use_id: v.optional(v.string()),
  is_error: v.optional(v.boolean()),
  content: v.optional(v.unknown()),
});

const usageSchema = v.looseObject({
  input_tokens: v.optional(v.number()),
  output_tokens: v.optional(v.number()),
  cache_read_input_tokens: v.optional(v.number()),
  cache_creation_input_tokens: v.optional(v.number()),
});

const claudeRecord = v.looseObject({
  type: v.optional(v.string()),
  timestamp: v.optional(v.string()),
  sessionId: v.optional(v.string()),
  cwd: v.optional(v.string()),
  uuid: v.optional(v.string()),
  parentUuid: v.optional(v.nullable(v.string())),
  aiTitle: v.optional(v.string()),
  customTitle: v.optional(v.string()),
  message: v.optional(v.unknown()),
  toolUseResult: v.optional(v.unknown()),
});

type DecodedClaudeRecord = v.InferOutput<typeof claudeRecord>;

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
    let raw: unknown;
    try {
      raw = JSON.parse(line);
    } catch {
      continue; // tolerate stray lines in a live-growing file
    }
    const parsed = v.safeParse(claudeRecord, raw);
    if (!parsed.success) continue;
    const rec: DecodedClaudeRecord = parsed.output;

    const type = rec.type ?? "";
    const timestamp = rec.timestamp ?? "";
    if (!transcript.timestamp && timestamp) transcript.timestamp = timestamp;
    if (!transcript.id && rec.sessionId) transcript.id = rec.sessionId;
    if (!transcript.cwd && rec.cwd) transcript.cwd = rec.cwd;

    // Session display names arrive as `custom-title` / `aiTitle` records.
    if (type === "custom-title" || (type === "user" && rec.aiTitle)) {
      const title = rec.aiTitle ?? (rec.customTitle ?? "");
      if (title) {
        transcript.title = collapseTranscriptText(title).slice(0, MAX_SESSION_TITLE_LENGTH) || null;
      }
      continue;
    }

    if (type === "user") {
      const entry = parseUserRecord(rec);
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
      const entry = parseAssistantRecord(rec);
      if (entry) {
        if (keepEntries) transcript.entries.push(entry);
        transcript.lastEntryId = entry.entryId;
        // Assistant records carry the active model; the newest one wins.
        if (entry.model) {
          transcript.model = entry.model;
          transcript.modelObservationSeen = true;
        }
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
function parseUserRecord(rec: DecodedClaudeRecord): TranscriptEntry | null {
  const entryId = rec.uuid ?? "";
  if (!entryId) return null;
  const parentId = rec.parentUuid ?? null;
  const timestamp = rec.timestamp ?? "";
  const messageParsed = v.safeParse(messageSchema, rec.message);
  if (!messageParsed.success) return null;
  const msg = messageParsed.output;
  const contentParsed = v.safeParse(contentSchema, msg.content);
  if (!contentParsed.success) return null;
  const rawContent = contentParsed.output;

  // Real prompt: bare string. A slash command arrives as the CLI's own
  // `<command-name>` markup, which chat shows as a chip instead of the dump.
  if (!Array.isArray(rawContent)) {
    if (!rawContent.trim()) return null;
    const blocks = peelClaudeCommandInvocation(rawContent) ?? [{ type: "text", text: rawContent }];
    return { entryId, parentId, timestamp, role: "user", content: blocks };
  }

  // Tool result: array with a tool_result block.
  const toolResultBlock = rawContent.find((block) => block.type === "tool_result");
  if (!toolResultBlock) return null;
  const resultParsed = v.safeParse(toolResultBlockSchema, toolResultBlock);
  if (!resultParsed.success) return null;
  const result = resultParsed.output;

  const answersParsed = v.safeParse(claudeAnswersSchema, rec.toolUseResult);
  const entry: TranscriptEntry = {
    entryId,
    parentId,
    timestamp,
    role: "toolResult",
    content: [],
    toolCallId: result.tool_use_id,
    isError: result.is_error ?? false,
    // Only the AskUserQuestion answers are kept as structured details;
    // the rest of `toolUseResult` (file contents, command output) would
    // ride along on every transcript poll for no reader.
    details: claudeQuestionAnswers(answersParsed.success ? answersParsed.output : undefined) ?? undefined,
  };
  const textRaw = v.safeParse(toolResultContentSchema, result.content);
  const text = textRaw.success ? toolResultText(textRaw.output) : "";
  if (text) entry.content = [{ type: "text", text }];
  // `toolUseResult` sits on the record, not inside the message: Edit and
  // Write report the change they made as a `structuredPatch` there, which
  // the tool_result text only summarizes in prose.
  const editParsed = v.safeParse(claudeFileEditResultSchema, rec.toolUseResult);
  const edit = fileEditFromClaudeResult(editParsed.success ? editParsed.output : undefined);
  if (edit) entry.content.push(edit);
  return entry;
}

function parseAssistantRecord(rec: DecodedClaudeRecord): TranscriptEntry | null {
  const entryId = rec.uuid ?? "";
  if (!entryId) return null;
  const parentId = rec.parentUuid ?? null;
  const timestamp = rec.timestamp ?? "";
  const messageParsed = v.safeParse(messageSchema, rec.message);
  if (!messageParsed.success) return null;
  const msg = messageParsed.output;
  const contentParsed = v.safeParse(v.array(blockSchema), msg.content);
  if (!contentParsed.success) return null;

  const entry: TranscriptEntry = {
    entryId,
    parentId,
    timestamp,
    role: "assistant",
    content: normalizeBlocks(contentParsed.output),
    model: msg.model,
    stopReason: msg.stop_reason,
  };
  const usageParsed = v.safeParse(usageSchema, msg.usage);
  if (usageParsed.success) {
    const usage = usageParsed.output;
    entry.usage = {};
    if (usage.input_tokens !== undefined) entry.usage.input = usage.input_tokens;
    if (usage.output_tokens !== undefined) entry.usage.output = usage.output_tokens;
    if (usage.cache_read_input_tokens !== undefined) entry.usage.cacheRead = usage.cache_read_input_tokens;
    if (usage.cache_creation_input_tokens !== undefined) {
      entry.usage.cacheWrite = usage.cache_creation_input_tokens;
    }
  }
  return entry;
}

/** Claude content blocks → shared block vocabulary (tool_use → toolCall). */
function normalizeBlocks(blocks: DecodedBlock[]): ContentBlock[] {
  return blocks.map(normalizeBlock);
}

function normalizeBlock(block: DecodedBlock): ContentBlock {
  const type = block.type;
  if (type === "text") {
    return { type: "text", text: String(block.text ?? "") };
  }
  if (type === "thinking") {
    return { type: "thinking", thinking: String(block.thinking ?? "") };
  }
  if (type === "tool_use") {
    return {
      type: "toolCall",
      id: String(block.id ?? ""),
      name: String(block.name ?? ""),
      arguments: block.input,
    };
  }
  if (type === "tool_result") {
    return {
      type: "toolResult",
      toolCallId: String(block.tool_use_id ?? ""),
      isError: block.is_error === true,
      content: block.content,
    };
  }
  return block;
}

function toolResultText(content: v.InferOutput<typeof toolResultContentSchema>): string {
  if (Array.isArray(content)) {
    return content
      .map((block) => block.text ?? "")
      .join("\n");
  }
  return content;
}

function entryTextOf(entry: TranscriptEntry): string {
  const parts: string[] = [];
  for (const block of entry.content) {
    switch (block.type) {
      case "text":
        parts.push(String(block.text));
        break;
      case "toolCall":
        parts.push(`[${String(block.name)}]`);
        break;
      case "skill":
        parts.push(skillInvocationPreview(String(block.name)));
        break;
      default:
        break;
    }
  }
  return parts.join("\n");
}
