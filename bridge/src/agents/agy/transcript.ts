import {
  collapseTranscriptText,
  MAX_SESSION_TITLE_LENGTH,
  type ContentBlock,
  type TextBlock,
  type ThinkingBlock,
  type ToolCallBlock,
  type Transcript,
  type TranscriptEntry,
  type TranscriptReadOpts,
} from "../../transcript.js";

/**
 * The Antigravity CLI (`agy`) JSONL transcript parser.
 * (~/.gemini/antigravity-cli/brain/<conversation-id>/.system_generated/logs/transcript.jsonl)
 */
export function parseAgyTranscript(text: string, opts: TranscriptReadOpts = {}): Transcript {
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
  let lastToolCallId: string | null = null;
  let lastToolName: string | null = null;

  for (const rawLine of text.split("\n")) {
    const line = rawLine.trim();
    if (line.length === 0) continue;
    let record: Record<string, unknown>;
    try {
      record = JSON.parse(line) as Record<string, unknown>;
    } catch {
      continue; // tolerate partial/malformed lines in a growing file
    }

    const type = typeof record.type === "string" ? record.type : "";
    const source = typeof record.source === "string" ? record.source : "";
    const createdAt = typeof record.created_at === "string" ? record.created_at : "";
    const stepIndex = typeof record.step_index === "number" ? record.step_index : 0;
    const entryId = `step-${stepIndex}`;

    if (!transcript.timestamp && createdAt) {
      transcript.timestamp = createdAt;
    }

    // Parse User Input
    if (type === "USER_INPUT" || source === "USER_EXPLICIT") {
      const rawContent = typeof record.content === "string" ? record.content : "";
      const userText = extractUserPrompt(rawContent);

      // Check for user settings change (e.g. Model Selection or Effort)
      const settingsModel = extractModelFromSettings(rawContent);
      if (settingsModel) {
        transcript.model = settingsModel;
        const effort = extractEffortFromModel(settingsModel);
        if (effort) transcript.thinkingLevel = effort;
      }

      // Check for workspace CWD
      const foundCwd = extractCwdFromContent(rawContent);
      if (foundCwd && !transcript.cwd) {
        transcript.cwd = foundCwd;
      }

      if (userText) {
        if (!transcript.preview) {
          transcript.preview = collapseTranscriptText(userText).slice(0, 240);
        }
        if (keepEntries) {
          transcript.entries.push({
            entryId,
            parentId: stepIndex > 0 ? `step-${stepIndex - 1}` : null,
            timestamp: createdAt,
            role: "user",
            content: [{ type: "text", text: userText }],
          });
        }
        transcript.lastEntryId = entryId;
      }
      continue;
    }

    // Parse Model / Assistant Response (PLANNER_RESPONSE)
    if (type === "PLANNER_RESPONSE" || source === "MODEL") {
      if (type === "PLANNER_RESPONSE") {
        const blocks: ContentBlock[] = [];

        // Assistant thinking
        if (typeof record.thinking === "string" && record.thinking.trim()) {
          blocks.push({ type: "thinking", thinking: record.thinking } as ThinkingBlock);
        }

        // Assistant content
        if (typeof record.content === "string" && record.content.trim()) {
          blocks.push({ type: "text", text: record.content } as TextBlock);
        }

        // Tool calls
        if (Array.isArray(record.tool_calls)) {
          record.tool_calls.forEach((tc, idx) => {
            const tool = tc as { name?: string; args?: unknown; id?: string };
            const toolName = typeof tool.name === "string" ? tool.name : "unknown_tool";
            const callId = tool.id || `${entryId}-call-${idx}`;
            lastToolCallId = callId;
            lastToolName = toolName;

            let args = tool.args;
            if (typeof args === "string") {
              try {
                args = JSON.parse(args) as unknown;
              } catch {
                // keep string if unparseable
              }
            }

            blocks.push({
              type: "toolCall",
              id: callId,
              name: toolName,
              arguments: args,
            } as ToolCallBlock);

            // Extract cwd from tool calls if not yet set
            if (!transcript.cwd && args && typeof args === "object") {
              const obj = args as Record<string, unknown>;
              if (typeof obj.Cwd === "string") transcript.cwd = obj.Cwd;
              else if (typeof obj.DirectoryPath === "string") transcript.cwd = obj.DirectoryPath;
              else if (typeof obj.SearchPath === "string") transcript.cwd = obj.SearchPath;
            }
          });
        }

        if (blocks.length > 0) {
          if (keepEntries) {
            transcript.entries.push({
              entryId,
              parentId: stepIndex > 0 ? `step-${stepIndex - 1}` : null,
              timestamp: createdAt,
              role: "assistant",
              content: blocks,
            });
          }
          transcript.lastEntryId = entryId;
        }
        continue;
      }

      // Tool Results (e.g. LIST_DIRECTORY, RUN_COMMAND, VIEW_FILE, etc.)
      const resultContent = typeof record.content === "string" ? record.content : "";
      const isError = record.status === "ERROR";
      const toolCallId = lastToolCallId || `${entryId}-call-0`;
      const toolName = lastToolName || type.toLowerCase();

      if (keepEntries) {
        transcript.entries.push({
          entryId,
          parentId: stepIndex > 0 ? `step-${stepIndex - 1}` : null,
          timestamp: createdAt,
          role: "toolResult",
          toolCallId,
          toolName,
          isError,
          content: [{ type: "text", text: resultContent }],
        });
      }
      transcript.lastEntryId = entryId;
      continue;
    }

    // Parse Checkpoints or Session Summary
    if (type === "CHECKPOINT" || type === "CONVERSATION_HISTORY") {
      const rawContent = typeof record.content === "string" ? record.content : "";
      if (!transcript.title) {
        const objectiveMatch = rawContent.match(/# USER Objective:\s*([^\r\n]+)/i);
        if (objectiveMatch && objectiveMatch[1]) {
          transcript.title = collapseTranscriptText(objectiveMatch[1]).slice(0, MAX_SESSION_TITLE_LENGTH);
        }
      }
      if (!transcript.id) {
        const convMatch = rawContent.match(/brain\/([a-f0-9-]+)\/\.system_generated/i);
        if (convMatch && convMatch[1]) {
          transcript.id = convMatch[1];
        }
      }
    }
  }

  if (opts.tail !== undefined && transcript.entries.length > opts.tail) {
    transcript.entries = transcript.entries.slice(-opts.tail);
  }

  if (!transcript.cwd) {
    transcript.cwd = process.env.PWD || process.cwd();
  }

  return transcript;
}

export function extractUserPrompt(content: string): string {
  const match = content.match(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/i);
  if (match && match[1]) {
    return match[1].trim();
  }
  // Strip tags if any metadata wrapper is present
  const stripped = content
    .replace(/<ADDITIONAL_METADATA>[\s\S]*?<\/ADDITIONAL_METADATA>/gi, "")
    .replace(/<USER_SETTINGS_CHANGE>[\s\S]*?<\/USER_SETTINGS_CHANGE>/gi, "")
    .trim();
  return stripped || content.trim();
}

function extractModelFromSettings(content: string): string | null {
  const match = content.match(/Model Selection`?\s+from\s+.*?to\s+([^\r\n<]+)/i);
  if (match && match[1]) {
    const raw = match[1].trim().replace(/\.$/, "").trim();
    return normalizeModelName(raw);
  }
  return null;
}

function normalizeModelName(name: string): string {
  const lower = name.toLowerCase().replace(/[\s()]+/g, "-").replace(/-+$/, "");
  if (lower.includes("gemini-3.7-flash")) {
    if (lower.includes("high")) return "gemini-3.7-flash-high";
    if (lower.includes("low")) return "gemini-3.7-flash-low";
    return "gemini-3.7-flash-medium";
  }
  if (lower.includes("gemini-3.6-flash")) return "gemini-3.6-flash-high";
  if (lower.includes("gemini-3.5-flash")) return "gemini-3.5-flash-high";
  if (lower.includes("gemini-3.1-pro")) return "gemini-3.1-pro-high";
  if (lower.includes("claude-sonnet")) return "claude-sonnet-4-6";
  if (lower.includes("claude-opus")) return "claude-opus-4-6-thinking";
  if (lower.includes("gpt-oss")) return "gpt-oss-120b-medium";
  return name;
}

function extractEffortFromModel(modelName: string): string | null {
  const lower = modelName.toLowerCase();
  if (lower.endsWith("-high") || lower.includes("(high)")) return "high";
  if (lower.endsWith("-medium") || lower.includes("(medium)")) return "medium";
  if (lower.endsWith("-low") || lower.includes("(low)")) return "low";
  return null;
}

function extractCwdFromContent(content: string): string | null {
  const match = content.match(/The user has \d+ active workspaces.*?:[\s\S]*?\[(.*?)\]\s*->/i);
  if (match && match[1]) {
    return match[1].trim();
  }
  const appDataMatch = content.match(/brain\/([a-f0-9-]+)/i);
  if (appDataMatch && appDataMatch[1]) {
    return null;
  }
  return null;
}
