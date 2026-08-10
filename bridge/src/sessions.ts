import type { HerdrClient } from "./herdr/client.js";
import { resolveAllowedDir } from "./dirs.js";
import { readModelsCatalog, type ModelsCatalog } from "./pi/models.js";
import { readPiSessionFile, type PiSession } from "./pi/session.js";

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
  | "set_model"
  | "set_thinking";

export interface ControlParams {
  paneId: string;
  action: ControlAction;
  /** Retry: last user message. Rename: the new workspace label. */
  text?: string;
}

export interface SessionControlDeps {
  readCatalog?: () => ModelsCatalog;
  readSession?: (path: string) => Promise<Pick<PiSession, "model" | "thinkingLevel">>;
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
 * One pane-native control action. Model changes use pi's exact `/model
 * provider/id` form. Thinking changes translate an explicit target into the
 * shortest deterministic sequence of pi's documented Shift+Tab action.
 */
export async function controlSession(
  herdr: HerdrClient,
  params: ControlParams,
  deps: SessionControlDeps = {},
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
      await herdr.paneSendInput(paneId, "/compact", ["Enter"]);
      return;
    case "fork":
      await herdr.paneSendInput(paneId, "/fork", ["Enter"]);
      return;
    case "rename": {
      if (!text) throw new SessionsError("rename needs a label");
      const workspaceId = await findPaneWorkspace(herdr, paneId);
      if (!workspaceId) throw new SessionsError("pane not found in the snapshot", 404);
      await herdr.workspaceRename(workspaceId, text);
      return;
    }
    case "set_model": {
      const model = requireCatalogModel(deps.readCatalog?.() ?? readModelsCatalog(), text);
      await herdr.paneSendInput(paneId, `/model ${model.provider}/${model.id}`, ["Enter"]);
      return;
    }
    case "set_thinking": {
      if (!text || !(THINKING_LEVELS as readonly string[]).includes(text)) {
        throw new SessionsError(`unknown thinking level: ${String(text)}`);
      }
      const path = await findPaneSessionPath(herdr, paneId);
      if (!path) throw new SessionsError("active pi session path is unavailable", 409);
      const session = await (deps.readSession?.(path) ?? readPiSessionFile(path));
      if (!session.model || !session.thinkingLevel) {
        throw new SessionsError("active model or thinking level is unavailable", 409);
      }
      const model = requireCatalogModel(deps.readCatalog?.() ?? readModelsCatalog(), session.model);
      const keys = thinkingLevelKeys(session.thinkingLevel, text, model.thinkingLevels);
      if (keys.length > 0) await herdr.paneSendKeys(paneId, keys);
      return;
    }
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

/** Keys needed to cycle from the active thinking level to an explicit target. */
export function thinkingLevelKeys(current: string, target: string, available: string[]): string[] {
  const currentIndex = available.indexOf(current);
  const targetIndex = available.indexOf(target);
  if (targetIndex === -1) throw new SessionsError(`${target} is not supported by the active model`);
  if (currentIndex === -1) throw new SessionsError(`active thinking level is unknown: ${current}`, 409);
  const count = (targetIndex - currentIndex + available.length) % available.length;
  return Array.from({ length: count }, () => "shift+tab");
}

function requireCatalogModel(catalog: ModelsCatalog, key: string | undefined) {
  if (!key || key.length > MAX_MODEL_LENGTH || CONTROL_CHAR.test(key)) throw new SessionsError("valid model is required");
  const model = catalog.providers
    .flatMap((provider) => provider.models)
    .find((candidate) => `${candidate.provider}/${candidate.id}` === key);
  if (!model) throw new SessionsError(`model is not available: ${key}`);
  return model;
}

async function findPaneSessionPath(herdr: HerdrClient, paneId: string): Promise<string> {
  try {
    const snapshot = await herdr.snapshot();
    const pane = snapshot.panes.find((candidate) => candidate.pane_id === paneId);
    if (pane?.agent_session?.kind === "path") return pane.agent_session.value;
    const agent = snapshot.agents.find((candidate) => candidate.pane_id === paneId);
    if (agent?.agent_session?.kind === "path") return agent.agent_session.value;
  } catch {
    // fall through
  }
  return "";
}
