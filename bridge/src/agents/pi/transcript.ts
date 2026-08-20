import { appendFile } from "node:fs/promises";
import { randomBytes } from "node:crypto";
import * as v from "valibot";
import {
  fileEditFromAnchoredDiff,
  fileEditFromUnifiedPatch,
  pathFromSnapshotId,
} from "../file-edit.js";
import {
  collapseTranscriptText,
  joinContentBlocks,
  MAX_SESSION_TITLE_LENGTH,
  type FileEditBlock,
  type ContentBlock,
  type Transcript,
  type TranscriptEntry,
  type TranscriptReadOpts,
} from "../../transcript.js";
import { expandSkillInvocationContent } from "../../skill-invocation.js";

/**
 * The pi JSONL (version 3) parser. Every pi consumer — chat, catalog, board —
 * reads through here. `Transcript` is deliberately format-neutral, so the
 * claude adapter can produce one without reshaping its data into pi's
 * vocabulary.
 *
 * Format observed on this machine (~/.pi/agent/sessions/<project>/<ts>_<uuid>.jsonl):
 *   {"type":"session","version":3,"id":"...","timestamp":"...","cwd":"..."}
 *   {"type":"session_info","id":"...","parentId":null,"timestamp":"...","name":"..."}
 *   {"type":"model_change","id":"...","parentId":null,"timestamp":"...","provider":"...","modelId":"..."}
 *   {"type":"thinking_level_change",...}
 *   {"type":"message","id":"...","parentId":"...","timestamp":"...","message":{role, content, ...}}
 *
 * Every record is decoded at this single I/O boundary with valibot schemas,
 * so downstream code branches on typed domain values instead of narrowing
 * `unknown` with `typeof`.
 */

// ── decode schemas ──────────────────────────────────────────────────────

const blockSchema = v.looseObject({ type: v.string() });
type DecodedBlock = v.InferOutput<typeof blockSchema>;

const contentSchema = v.union([v.string(), v.array(blockSchema)]);

const usageSchema = v.looseObject({
  input: v.optional(v.number()),
  output: v.optional(v.number()),
  cacheRead: v.optional(v.number()),
  cacheWrite: v.optional(v.number()),
  totalTokens: v.optional(v.number()),
  cost: v.optional(v.record(v.string(), v.number())),
});

const messageSchema = v.looseObject({
  role: v.optional(v.string()),
  content: v.optional(contentSchema),
  toolCallId: v.optional(v.string()),
  toolName: v.optional(v.string()),
  isError: v.optional(v.boolean()),
  details: v.optional(v.record(v.string(), v.unknown())),
  stopReason: v.optional(v.string()),
  model: v.optional(v.string()),
  usage: v.optional(usageSchema),
});

const fileEditDetailsSchema = v.looseObject({
  patch: v.optional(v.string()),
  diff: v.optional(v.string()),
  snapshotId: v.optional(v.string()),
});

const sessionRecord = v.looseObject({
  type: v.literal("session"),
  version: v.optional(v.number()),
  id: v.optional(v.string()),
  cwd: v.optional(v.string()),
  timestamp: v.optional(v.string()),
});

const sessionInfoRecord = v.looseObject({
  type: v.literal("session_info"),
  id: v.optional(v.string()),
  parentId: v.optional(v.nullable(v.string())),
  timestamp: v.optional(v.string()),
  name: v.optional(v.string()),
});

const modelChangeRecord = v.looseObject({
  type: v.literal("model_change"),
  id: v.optional(v.string()),
  parentId: v.optional(v.nullable(v.string())),
  timestamp: v.optional(v.string()),
  provider: v.optional(v.string()),
  modelId: v.optional(v.string()),
});

const thinkingLevelRecord = v.looseObject({
  type: v.literal("thinking_level_change"),
  id: v.optional(v.string()),
  parentId: v.optional(v.nullable(v.string())),
  timestamp: v.optional(v.string()),
  thinkingLevel: v.optional(v.string()),
});

const piMessageRecord = v.looseObject({
  type: v.literal("message"),
  id: v.optional(v.string()),
  parentId: v.optional(v.nullable(v.string())),
  timestamp: v.optional(v.string()),
  message: v.optional(messageSchema),
});

const piRecord = v.variant("type", [
  sessionRecord,
  sessionInfoRecord,
  modelChangeRecord,
  thinkingLevelRecord,
  piMessageRecord,
]);

type PiRecord = v.InferOutput<typeof piRecord>;

export function parsePiTranscript(text: string, opts: TranscriptReadOpts = {}): Transcript {
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
    const parsed = v.safeParse(piRecord, raw);
    if (!parsed.success) continue; // custom or malformed records are not transcript entries
    const rec: PiRecord = parsed.output;

    switch (rec.type) {
      case "session":
        transcript.version = rec.version ?? transcript.version;
        transcript.id = rec.id ?? "";
        transcript.cwd = rec.cwd ?? "";
        transcript.timestamp = rec.timestamp ?? "";
        break;
      case "session_info":
        if (rec.name) {
          transcript.title = collapseTranscriptText(rec.name).slice(0, MAX_SESSION_TITLE_LENGTH) || null;
        }
        break;
      case "model_change": {
        const provider = rec.provider ?? "";
        const modelId = rec.modelId ?? "";
        transcript.model = provider && modelId ? `${provider}/${modelId}` : null;
        break;
      }
      case "thinking_level_change":
        transcript.thinkingLevel = rec.thinkingLevel ?? null;
        break;
      case "message": {
        const entry = parseMessageRecord(rec);
        if (entry) {
          if (keepEntries) transcript.entries.push(entry);
          transcript.lastEntryId = entry.entryId;
          if (!transcript.preview && entry.role === "user") {
            transcript.preview = collapseTranscriptText(joinContentBlocks(entry)).slice(0, 240);
          }
        }
        break;
      }
    }
  }

  if (opts.tail !== undefined && transcript.entries.length > opts.tail) {
    transcript.entries = transcript.entries.slice(-opts.tail);
  }
  return transcript;
}

function parseMessageRecord(rec: v.InferOutput<typeof piMessageRecord>): TranscriptEntry | null {
  const entryId = rec.id ?? "";
  if (!entryId) return null;
  const parentId = rec.parentId ?? null;
  const timestamp = rec.timestamp ?? "";
  const message = rec.message;
  if (!message) return null;

  const role = message.role ?? "unknown";
  const content = role === "user"
    ? expandSkillInvocationContent(normalizeContent(message.content ?? []))
    : normalizeContent(message.content ?? []);
  const entry: TranscriptEntry = { entryId, parentId, timestamp, role, content };

  if (message.toolCallId) entry.toolCallId = message.toolCallId;
  if (message.toolName) entry.toolName = message.toolName;
  if (message.isError !== undefined) entry.isError = message.isError;
  if (message.details) {
    entry.details = message.details;
    const edit = fileEditFromDetails(message.details);
    if (edit) entry.content.push(edit);
  }
  if (message.stopReason) entry.stopReason = message.stopReason;
  if (message.model) entry.model = message.model;
  if (message.usage) {
    entry.usage = {};
    for (const key of ["input", "output", "cacheRead", "cacheWrite", "totalTokens"] as const) {
      const value = message.usage[key];
      if (value !== undefined) entry.usage[key] = value;
    }
    if (message.usage.cost) entry.usage.cost = message.usage.cost;
  }
  return entry;
}

/**
 * Read an edit out of a pi tool result. `details.patch` is a unified patch and
 * names its own file; `details.diff` (what `replace` writes) is anchor-prefixed
 * and takes its path from `details.snapshotId`. Any tool that writes either
 * field qualifies, extensions included — there is no tool-name list.
 */
function fileEditFromDetails(details: v.InferOutput<typeof fileEditDetailsSchema>): FileEditBlock | null {
  if (details.patch && details.patch.trim()) {
    const edit = fileEditFromUnifiedPatch(details.patch);
    if (edit) return edit;
  }
  if (details.diff && details.diff.trim()) {
    return fileEditFromAnchoredDiff(details.diff, pathFromSnapshotId(details.snapshotId));
  }
  return null;
}

function normalizeContent(content: string | DecodedBlock[]): ContentBlock[] {
  if (Array.isArray(content)) return content.map(normalizeBlock);
  return content.length > 0 ? [{ type: "text", text: content }] : [];
}

function normalizeBlock(block: DecodedBlock): ContentBlock {
  const type = block.type;
  if (type === "text") {
    return { type: "text", text: String(block.text ?? "") };
  }
  if (type === "thinking") {
    return { type: "thinking", thinking: String(block.thinking ?? "") };
  }
  if (type === "toolCall") {
    return {
      type: "toolCall",
      id: String(block.id ?? ""),
      name: String(block.name ?? ""),
      arguments: block.arguments,
    };
  }
  return block;
}

/**
 * Name a pi session. pi stores the name as an appended `session_info` record
 * rather than a rewrite, so this never races a live agent writing the file.
 */
export async function writePiSessionTitle(path: string, title: string): Promise<void> {
  const record = {
    type: "session_info",
    id: randomBytes(4).toString("hex"),
    parentId: null,
    timestamp: new Date().toISOString(),
    name: title,
  };
  await appendFile(path, `${JSON.stringify(record)}\n`, "utf8");
}
