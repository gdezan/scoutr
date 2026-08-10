import type { HerdrClient } from "./herdr/client.js";
import { resolveAllowedDir } from "./dirs.js";

/** pi's documented `--thinking` levels (README: Model Options). */
export const THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"] as const;
export type ThinkingLevel = (typeof THINKING_LEVELS)[number];

export interface CreateSessionParams {
  cwd: string;
  model: string;
  name?: string;
  thinkingLevel?: string;
  initialPrompt?: string;
}

export interface CreatedSession {
  workspaceId: string;
  paneId: string;
}

export type ControlAction =
  | "abort"
  | "retry"
  | "compact"
  | "fork"
  | "rename"
  | "cycle_thinking";

export interface ControlParams {
  paneId: string;
  action: ControlAction;
  /** Retry: last user message. Rename: the new workspace label. */
  text?: string;
}

export class SessionsError extends Error {
  constructor(
    message: string,
    public readonly status = 400,
  ) {
    super(message);
  }
}

const MAX_MODEL_LENGTH = 200;
const MAX_NAME_LENGTH = 100;
const MAX_PROMPT_LENGTH = 100_000;
const CONTROL_CHAR = /[\u0000-\u001f\u007f]/;
const PROMPT_FORBIDDEN_CHAR = /[\u0000\u007f]/;
const AGENT_START_TIMEOUT_MS = 8_000;
const AGENT_POLL_MS = 100;

function assertNoControlChars(field: string, value: string): void {
  if (CONTROL_CHAR.test(value)) {
    throw new SessionsError(`${field} must not contain control characters`);
  }
}

/**
 * Validate creation params against the launch-command surface: required
 * cwd/model, length caps, no control characters, and a thinking level from
 * pi's documented set. Runs before any herdr call.
 */
export function validateCreateSessionParams(params: CreateSessionParams): void {
  if (typeof params.cwd !== "string" || params.cwd === "" || typeof params.model !== "string" || params.model === "") {
    throw new SessionsError("cwd and model are required");
  }
  assertNoControlChars("cwd", params.cwd);
  if (params.model.length > MAX_MODEL_LENGTH) {
    throw new SessionsError(`model is too long (max ${MAX_MODEL_LENGTH} characters)`);
  }
  assertNoControlChars("model", params.model);

  if (params.thinkingLevel !== undefined) {
    if (typeof params.thinkingLevel !== "string" || !(THINKING_LEVELS as readonly string[]).includes(params.thinkingLevel)) {
      throw new SessionsError(`unknown thinking level: ${String(params.thinkingLevel)}`);
    }
  }

  if (params.name !== undefined && params.name !== "") {
    if (typeof params.name !== "string") throw new SessionsError("name must be a string");
    if (params.name.length > MAX_NAME_LENGTH) {
      throw new SessionsError(`name is too long (max ${MAX_NAME_LENGTH} characters)`);
    }
    assertNoControlChars("name", params.name);
  }

  if (params.initialPrompt !== undefined && params.initialPrompt !== "") {
    if (typeof params.initialPrompt !== "string") throw new SessionsError("initialPrompt must be a string");
    if (params.initialPrompt.length > MAX_PROMPT_LENGTH) {
      throw new SessionsError(`initialPrompt is too long (max ${MAX_PROMPT_LENGTH} characters)`);
    }
    if (PROMPT_FORBIDDEN_CHAR.test(params.initialPrompt)) throw new SessionsError("initialPrompt must not contain NUL or DEL");
  }
}

/**
 * POSIX single-quote escaping: the value survives any shell as one literal
 * argument. Apostrophes become `'\''`; every other metacharacter (`;`, `$`,
 * backtick, `"`, `\`, spaces) is inert inside single quotes.
 */
export function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

/** Build the shell command that starts pi. Prompt delivery uses agent.prompt. */
export function buildLaunchCommand(params: {
  model: string;
  thinkingLevel?: string;
  name?: string;
}): string {
  const parts = ["pi", "--model", shellQuote(params.model)];
  if (params.thinkingLevel) parts.push("--thinking", shellQuote(params.thinkingLevel));
  if (params.name) parts.push("--name", shellQuote(params.name));
  return parts.join(" ");
}

/**
 * Create a workspace, start pi, wait for Herdr to detect it, and deliver the
 * first prompt through agent.prompt. Any launch failure closes the workspace.
 */
export async function createSession(
  herdr: HerdrClient,
  params: CreateSessionParams,
): Promise<CreatedSession> {
  validateCreateSessionParams(params);
  let cwd: string;
  try {
    cwd = resolveAllowedDir(params.cwd);
  } catch (error) {
    throw new SessionsError(error instanceof Error ? error.message : String(error));
  }

  const name = params.name?.trim() || cwd.split("/").filter(Boolean).pop() || "session";
  const ws = await herdr.workspaceCreate({ cwd, label: name, focus: false });
  const workspaceId = ws.workspace?.workspace_id ?? "";
  const paneId = ws.root_pane?.pane_id ?? "";
  if (!workspaceId) throw new SessionsError("herdr did not return a workspace id", 502);
  if (!paneId) {
    await closeWorkspaceQuietly(herdr, workspaceId);
    throw new SessionsError("herdr did not return a pane id", 502);
  }

  try {
    const command = buildLaunchCommand({
      model: params.model,
      thinkingLevel: params.thinkingLevel,
      name: params.name?.trim() || undefined,
    });
    await herdr.paneSendInput(paneId, command, ["Enter"]);
    await waitForAgent(herdr, paneId);
    if (params.initialPrompt) await herdr.agentPrompt(paneId, params.initialPrompt);
    return { workspaceId, paneId };
  } catch (error) {
    await closeWorkspaceQuietly(herdr, workspaceId);
    if (error instanceof SessionsError) throw error;
    throw new SessionsError(`session launch failed: ${error instanceof Error ? error.message : String(error)}`, 502);
  }
}

async function waitForAgent(herdr: HerdrClient, paneId: string): Promise<void> {
  const deadline = Date.now() + AGENT_START_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const snapshot = await herdr.snapshot();
      const pane = snapshot.panes.find((candidate) => candidate.pane_id === paneId);
      if (pane?.agent || snapshot.agents.some((agent) => agent.pane_id === paneId)) return;
    } catch {
      // The next poll may succeed while Herdr and pi finish startup.
    }
    await new Promise((resolve) => setTimeout(resolve, AGENT_POLL_MS));
  }
  throw new SessionsError("pi did not start before the launch timeout", 502);
}

async function closeWorkspaceQuietly(herdr: HerdrClient, workspaceId: string): Promise<void> {
  try {
    await herdr.workspaceClose(workspaceId);
  } catch {
    // Keep the launch error; cleanup failure is visible in Herdr's workspace list.
  }
}

/**
 * One pane-native control action, grounded in pi's documented TUI commands:
 * abort = app.interrupt (escape), retry = agent.prompt with the last user
 * message, compact/fork = typed slash commands, rename = workspace label,
 * cycle_thinking = app.thinking.cycle (shift+tab).
 */
export async function controlSession(
  herdr: HerdrClient,
  params: ControlParams,
): Promise<void> {
  const { paneId, action, text } = params;
  switch (action) {
    case "abort":
      await herdr.paneSendKeys(paneId, ["escape"]);
      return;
    case "retry": {
      if (!text) throw new SessionsError("retry needs the last user message");
      await herdr.agentPrompt(paneId, text);
      return;
    }
    case "compact":
      await herdr.paneSendText(paneId, "/compact");
      await herdr.paneSendKeys(paneId, ["Enter"]);
      return;
    case "fork":
      await herdr.paneSendText(paneId, "/fork");
      await herdr.paneSendKeys(paneId, ["Enter"]);
      return;
    case "rename": {
      if (!text) throw new SessionsError("rename needs a label");
      const workspaceId = await findPaneWorkspace(herdr, paneId);
      if (!workspaceId) throw new SessionsError("pane not found in the snapshot", 404);
      await herdr.workspaceRename(workspaceId, text);
      return;
    }
    case "cycle_thinking":
      await herdr.paneSendKeys(paneId, ["shift+tab"]);
      return;
    default:
      throw new SessionsError(`unknown control action: ${String(action)}`);
  }
}

/** Resolve a pane's workspace id from the live snapshot. */
async function findPaneWorkspace(herdr: HerdrClient, paneId: string): Promise<string> {
  try {
    const snapshot = await herdr.snapshot();
    for (const pane of snapshot.panes) {
      if (pane.pane_id === paneId) return pane.workspace_id;
    }
  } catch {
    // fall through
  }
  return "";
}
