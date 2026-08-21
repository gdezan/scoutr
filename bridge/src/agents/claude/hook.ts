import { existsSync } from "node:fs";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { shellQuote } from "../../shell.js";
import { claudeConfigDir } from "./index.js";
import { clearPendingAsk, writePendingAsk } from "./pending-asks.js";
import * as v from "valibot";
/**
 * The Claude Code hook that makes a pending ask visible to Scoutr.
 *
 * `PreToolUse` fires before `AskUserQuestion` blocks the pane and hands the
 * hook the session id, the tool call id, and the questions; `PostToolUse`
 * fires once the ask is answered. Everything the bridge needs is on stdin, so
 * the hook talks to no socket and can never make the agent wait on Scoutr.
 */
export const CLAUDE_ASK_TOOL_MATCHER = "AskUserQuestion";

/**
 * The command Claude runs for the hook.
 *
 * It has to be absolute. There is no `scoutr-bridge` on PATH — the daemon
 * runs as `node dist/cli.js` from a systemd unit — and a hook inherits
 * whatever environment the agent happened to have, which for a mise-managed
 * node is not the one that installed it. So the command names this
 * interpreter and this checkout's built CLI explicitly.
 */
export function defaultHookCommand(): string {
  const here = dirname(fileURLToPath(import.meta.url));
  // Built layout: dist/agents/claude/hook.js -> dist/cli.js.
  let cli = resolve(here, "..", "..", "cli.js");
  if (!existsSync(cli)) {
    // Running from source under tsx: install the built entry, not the .ts one.
    cli = resolve(here, "..", "..", "..", "dist", "cli.js");
  }
  return `${shellQuote(process.execPath)} ${shellQuote(cli)} hook claude`;
}

const hookInputSchema = v.looseObject({
  hook_event_name: v.optional(v.string()),
  session_id: v.optional(v.string()),
  transcript_path: v.optional(v.string()),
  tool_name: v.optional(v.string()),
  tool_use_id: v.optional(v.string()),
  tool_input: v.optional(v.looseObject({ questions: v.optional(v.unknown()) })),
});

const hookEntrySchema = v.looseObject({
  matcher: v.optional(v.string()),
  hooks: v.optional(v.array(v.looseObject({ type: v.optional(v.string()), command: v.optional(v.string()) }))),
});

const settingsSchema = v.looseObject({
  hooks: v.optional(v.record(v.string(), v.array(hookEntrySchema))),
});

/**
 * Handle one hook invocation. Returns what it did, for the CLI's own output;
 * an unrecognized or malformed event is a no-op, never an error — a failing
 * hook would disrupt the agent it is only supposed to observe.
 */
export function handleClaudeHook(raw: string): string {
  let json: unknown;
  try {
    json = JSON.parse(raw);
  } catch {
    return "ignored: unparseable hook input";
  }
  const parsed = v.safeParse(hookInputSchema, json);
  if (!parsed.success) return "ignored: unparseable hook input";
  const input = parsed.output;
  const sessionId = input.session_id ?? "";
  if (!sessionId) return "ignored: no session id";
  if (input.tool_name !== CLAUDE_ASK_TOOL_MATCHER) return "ignored: not an ask";

  if (input.hook_event_name === "PreToolUse") {
    const questions = input.tool_input?.questions;
    if (!Array.isArray(questions) || questions.length === 0) return "ignored: no questions";
    writePendingAsk({
      sessionId,
      toolUseId: input.tool_use_id ?? "",
      timestamp: new Date().toISOString(),
      transcriptPath: input.transcript_path ?? "",
      questions,
    });
    return `recorded ask for session ${sessionId}`;
  }
  // PostToolUse (and any later lifecycle event): the ask is over, and the
  // transcript now owns the answers.
  clearPendingAsk(sessionId);
  return `cleared ask for session ${sessionId}`;
}

/**
 * Add the Scoutr hooks to Claude's settings, keeping every other setting and
 * hook as it is. Idempotent: an entry whose command is already ours is left
 * alone. Returns the settings path and whether it changed.
 */
export async function installClaudeHook(
  command = defaultHookCommand(),
  settingsPath = join(claudeConfigDir(), "settings.json"),
): Promise<{ path: string; command: string; changed: boolean }> {
  let rawSettings: string | undefined;
  try {
    rawSettings = await readFile(settingsPath, "utf8");
  } catch {
    rawSettings = undefined; // no settings file yet, or unreadable — write a fresh one
  }
  const settingsParsed = v.safeParse(settingsSchema, rawSettings ? JSON.parse(rawSettings) : {});
  const settings = settingsParsed.success ? settingsParsed.output : { hooks: undefined };
  const hooks = settings.hooks ?? {};
  let changed = false;
  for (const event of ["PreToolUse", "PostToolUse"]) {
    const existing = Array.isArray(hooks[event]) ? hooks[event] : [];
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
  if (!changed) return { path: settingsPath, command, changed: false };

  settings.hooks = hooks;
  await mkdir(join(settingsPath, ".."), { recursive: true });
  await writeFile(settingsPath, `${JSON.stringify(settings, null, 2)}\n`, "utf8");
  return { path: settingsPath, command, changed: true };
}
