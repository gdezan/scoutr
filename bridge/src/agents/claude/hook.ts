import { readFile, writeFile, mkdir } from "node:fs/promises";
import { join } from "node:path";
import { claudeConfigDir } from "./index.js";
import { clearPendingAsk, writePendingAsk } from "./pending-asks.js";

/**
 * The Claude Code hook that makes a pending ask visible to Scoutr.
 *
 * `PreToolUse` fires before `AskUserQuestion` blocks the pane and hands the
 * hook the session id, the tool call id, and the questions; `PostToolUse`
 * fires once the ask is answered. Everything the bridge needs is on stdin, so
 * the hook talks to no socket and can never make the agent wait on Scoutr.
 */
export const CLAUDE_HOOK_COMMAND = "scoutr-bridge hook claude";
export const CLAUDE_ASK_TOOL_MATCHER = "AskUserQuestion";

interface HookInput {
  hook_event_name?: string;
  session_id?: string;
  transcript_path?: string;
  tool_name?: string;
  tool_use_id?: string;
  tool_input?: { questions?: unknown };
}

/**
 * Handle one hook invocation. Returns what it did, for the CLI's own output;
 * an unrecognized or malformed event is a no-op, never an error — a failing
 * hook would disrupt the agent it is only supposed to observe.
 */
export function handleClaudeHook(raw: string): string {
  let input: HookInput;
  try {
    input = JSON.parse(raw) as HookInput;
  } catch {
    return "ignored: unparseable hook input";
  }
  const sessionId = typeof input.session_id === "string" ? input.session_id : "";
  if (!sessionId) return "ignored: no session id";
  if (input.tool_name !== CLAUDE_ASK_TOOL_MATCHER) return "ignored: not an ask";

  if (input.hook_event_name === "PreToolUse") {
    const questions = input.tool_input?.questions;
    if (!Array.isArray(questions) || questions.length === 0) return "ignored: no questions";
    writePendingAsk({
      sessionId,
      toolUseId: typeof input.tool_use_id === "string" ? input.tool_use_id : "",
      timestamp: new Date().toISOString(),
      transcriptPath: typeof input.transcript_path === "string" ? input.transcript_path : "",
      questions,
    });
    return `recorded ask for session ${sessionId}`;
  }
  // PostToolUse (and any later lifecycle event): the ask is over, and the
  // transcript now owns the answers.
  clearPendingAsk(sessionId);
  return `cleared ask for session ${sessionId}`;
}

interface HookEntry {
  matcher?: string;
  hooks?: Array<{ type?: string; command?: string }>;
}

/**
 * Add the Scoutr hooks to Claude's settings, keeping every other setting and
 * hook as it is. Idempotent: an entry whose command is already ours is left
 * alone. Returns the settings path and whether it changed.
 */
export async function installClaudeHook(
  command = CLAUDE_HOOK_COMMAND,
  settingsPath = join(claudeConfigDir(), "settings.json"),
): Promise<{ path: string; changed: boolean }> {
  let settings: Record<string, unknown> = {};
  try {
    settings = JSON.parse(await readFile(settingsPath, "utf8")) as Record<string, unknown>;
  } catch {
    settings = {}; // no settings file yet, or unreadable — write a fresh one
  }
  const hooks = (settings.hooks && typeof settings.hooks === "object" && !Array.isArray(settings.hooks)
    ? settings.hooks
    : {}) as Record<string, unknown>;

  let changed = false;
  for (const event of ["PreToolUse", "PostToolUse"]) {
    const existing = Array.isArray(hooks[event]) ? (hooks[event] as HookEntry[]) : [];
    const already = existing.some((entry) =>
      entry?.hooks?.some((hook) => hook?.command === command),
    );
    if (already) continue;
    hooks[event] = [
      ...existing,
      { matcher: CLAUDE_ASK_TOOL_MATCHER, hooks: [{ type: "command", command }] },
    ];
    changed = true;
  }
  if (!changed) return { path: settingsPath, changed: false };

  settings.hooks = hooks;
  await mkdir(join(settingsPath, ".."), { recursive: true });
  await writeFile(settingsPath, `${JSON.stringify(settings, null, 2)}\n`, "utf8");
  return { path: settingsPath, changed: true };
}
