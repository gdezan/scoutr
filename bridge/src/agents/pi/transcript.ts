import { appendFile } from "node:fs/promises";
import { randomBytes } from "node:crypto";
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
  type TextBlock,
  type ThinkingBlock,
  type Transcript,
  type TranscriptEntry,
  type TranscriptReadOpts,
} from "../../transcript.js";

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
 */
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
        transcript.title = collapseTranscriptText(record.name).slice(0, MAX_SESSION_TITLE_LENGTH) || null;
      }
      continue;
    }
    if (type === "message") {
      const entry = parseMessageRecord(record);
      if (entry) {
        if (keepEntries) transcript.entries.push(entry);
        transcript.lastEntryId = entry.entryId;
        if (!transcript.preview && entry.role === "user") {
          transcript.preview = collapseTranscriptText(joinContentBlocks(entry)).slice(0, 240);
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
  if (msg.details && typeof msg.details === "object") {
    entry.details = msg.details;
    const edit = fileEditFromDetails(msg.details as Record<string, unknown>);
    if (edit) entry.content.push(edit);
  }
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

/**
 * Read an edit out of a pi tool result. `details.patch` is a unified patch and
 * names its own file; `details.diff` (what `replace` writes) is anchor-prefixed
 * and takes its path from `details.snapshotId`. Any tool that writes either
 * field qualifies, extensions included — there is no tool-name list.
 */
function fileEditFromDetails(details: Record<string, unknown>): FileEditBlock | null {
  if (typeof details.patch === "string" && details.patch.trim()) {
    const edit = fileEditFromUnifiedPatch(details.patch);
    if (edit) return edit;
  }
  if (typeof details.diff === "string" && details.diff.trim()) {
    return fileEditFromAnchoredDiff(details.diff, pathFromSnapshotId(details.snapshotId));
  }
  return null;
}

function normalizeContent(content: unknown): TranscriptEntry["content"] {
  if (typeof content === "string") {
    return content.length > 0 ? [{ type: "text", text: content }] : [];
  }
  if (!Array.isArray(content)) return [];
  return content
    .filter((block): block is Record<string, unknown> => !!block && typeof block === "object")
    .map((block) => {
      const type = typeof block.type === "string" ? block.type : "unknown";
      if (type === "text" && typeof block.text === "string") {
        return { type, text: block.text } as TextBlock;
      }
      if (type === "thinking" && typeof block.thinking === "string") {
        return { type, thinking: block.thinking } as ThinkingBlock;
      }
      if (type === "toolCall") {
        return {
          type,
          id: typeof block.id === "string" ? block.id : "",
          name: typeof block.name === "string" ? block.name : "",
          arguments: block.arguments,
        };
      }
      return block as TranscriptEntry["content"][number];
    });
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
