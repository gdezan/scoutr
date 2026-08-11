import type { HerdrPort } from "./herdr/port.js";
import { BridgeError } from "./errors.js";
import { resolveAllowedDir } from "./dirs.js";
import type { Transcript } from "./transcript.js";
import { resolveCatalogSessionPath } from "./session-catalog.js";
import { backendFor, backendForAgentSessionInfo, getBackendOrNull } from "./agents/registry.js";
import type { AgentBackend, ControlAction, ControlParams } from "./agents/types.js";
import { shellQuote } from "./shell.js";
import { findPaneWorkspace } from "./herdr/panes.js";
import { realpathSync, statSync } from "node:fs";
import { basename, dirname, resolve } from "node:path";

/**
 * pi's documented `--thinking` levels (README: Model Options) — the only
 * thinking vocabulary the create-session surface knows. Backends that cannot
 * express a thinking level simply never receive one (capability gated).
 */
export const THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"] as const;
export type ThinkingLevel = (typeof THINKING_LEVELS)[number];

export interface CreateSessionParams {
  cwd: string;
  model: string;
  name?: string;
  thinkingLevel?: string;
  initialPrompt?: string;
  /** Registry backend id; defaults to "pi". */
  agent?: string;
}

export interface CreatedSession {
  workspaceId: string;
  paneId: string;
}

export type StoredSessionMode = "resume" | "fork";

export interface LaunchStoredSessionParams {
  path: string;
  mode: StoredSessionMode;
}

export class SessionsError extends BridgeError {
  constructor(message: string, status = 400) {
    super(message, status);
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
  if (typeof params.cwd !== "string" || params.cwd === "") {
    throw new SessionsError("cwd is required");
  }
  assertNoControlChars("cwd", params.cwd);
  if (params.model !== undefined && params.model !== "") {
    if (typeof params.model !== "string") throw new SessionsError("model must be a string");
    if (params.model.length > MAX_MODEL_LENGTH) {
      throw new SessionsError(`model is too long (max ${MAX_MODEL_LENGTH} characters)`);
    }
    assertNoControlChars("model", params.model);
  }

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
 * Create a workspace, start the agent, wait for Herdr to detect it, and
 * deliver the first prompt through agent.prompt. Any launch failure closes
 * the workspace. The launch command is the selected backend's own grammar.
 */
export async function createSession(
  herdr: HerdrPort,
  params: CreateSessionParams,
): Promise<CreatedSession> {
  validateCreateSessionParams(params);
  let cwd: string;
  try {
    cwd = resolveAllowedDir(params.cwd);
  } catch (error) {
    throw new SessionsError(error instanceof Error ? error.message : String(error));
  }

  const backend = backendFor(params.agent ?? "pi");
  if (backend.hasModelCatalog && !params.model?.trim()) {
    throw new SessionsError("cwd and model are required");
  }
  const name = params.name?.trim() || basename(cwd) || "session";
  const command = backend.launchCommand({
    model: params.model?.trim() || undefined,
    thinkingLevel: params.thinkingLevel,
    name: params.name?.trim() || undefined,
  });
  return launchWorkspace(herdr, cwd, name, command, async (paneId) => {
    if (params.initialPrompt) await herdr.agentPrompt(paneId, params.initialPrompt);
  });
}

/** Open a stored session in a new Herdr workspace, via the owning backend. */
export async function launchStoredSession(
  herdr: HerdrPort,
  params: LaunchStoredSessionParams,
): Promise<CreatedSession> {
  const { path, backend } = await resolveCatalogSessionPath(params.path);
  const session = await backend.readTranscript(path, { metadataOnly: true });
  if (!session.cwd) throw new SessionsError("session working directory is unavailable", 409);
  const cwd = resolveSessionWorkspace(session.cwd, path, backend.sessionRoot());
  let command: string;
  try {
    command = backend.resumeCommand(path, params.mode);
  } catch (error) {
    // e.g. forking a claude transcript, which has no fork-at-path launch.
    throw new SessionsError(error instanceof Error ? error.message : String(error), 400);
  }
  return launchWorkspace(
    herdr,
    cwd,
    basename(cwd) || "session",
    command,
  );
}

/**
 * Working directory for a stored-session launch. The cwd recorded in the
 * session file is trusted (the user already ran an agent there — the same
 * least-privilege reasoning as the review allow-list in review.ts), so a
 * session run outside $HOME can still be resumed. When that directory no
 * longer exists, fall back to the backend's session store root, then to the
 * session file's own directory, so the transcript still opens (resume
 * contract, docs/production-goal-checklist.md fix 7).
 */
function resolveSessionWorkspace(recorded: string, sessionPath: string, sessionRoot: string): string {
  try {
    const target = realpathSync(resolve(recorded));
    if (statSync(target).isDirectory()) return target;
  } catch {
    // recorded cwd is gone or unresolvable — try the store root below
  }
  try {
    const root = realpathSync(resolve(sessionRoot));
    if (statSync(root).isDirectory()) return root;
  } catch {
    // store root gone too — the session file's own directory still opens
  }
  return dirname(sessionPath);
}

async function launchWorkspace(
  herdr: HerdrPort,
  cwd: string,
  label: string,
  command: string,
  afterStart?: (paneId: string) => Promise<void>,
): Promise<CreatedSession> {
  const ws = await herdr.workspaceCreate({ cwd, label, focus: false });
  const workspaceId = ws.workspace?.workspace_id ?? "";
  const paneId = ws.root_pane?.pane_id ?? "";
  if (!workspaceId) throw new SessionsError("herdr did not return a workspace id", 502);
  if (!paneId) {
    await closeWorkspaceQuietly(herdr, workspaceId);
    throw new SessionsError("herdr did not return a pane id", 502);
  }

  try {
    await herdr.paneSendInput(paneId, command, ["Enter"]);
    await waitForAgent(herdr, paneId);
    await afterStart?.(paneId);
    return { workspaceId, paneId };
  } catch (error) {
    await closeWorkspaceQuietly(herdr, workspaceId);
    if (error instanceof SessionsError) throw error;
    throw new SessionsError(`session launch failed: ${error instanceof Error ? error.message : String(error)}`, 502);
  }
}

async function waitForAgent(herdr: HerdrPort, paneId: string): Promise<void> {
  const deadline = Date.now() + AGENT_START_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const snapshot = await herdr.snapshot();
      const pane = snapshot.panes.find((candidate) => candidate.pane_id === paneId);
      if (pane?.agent || snapshot.agents.some((agent) => agent.pane_id === paneId)) return;
    } catch {
      // The next poll may succeed while Herdr and the agent finish startup.
    }
    await new Promise((resolve) => setTimeout(resolve, AGENT_POLL_MS));
  }
  throw new SessionsError("agent did not start before the launch timeout", 502);
}

async function closeWorkspaceQuietly(herdr: HerdrPort, workspaceId: string): Promise<void> {
  try {
    await herdr.workspaceClose(workspaceId);
  } catch {
    // Keep the launch error; cleanup failure is visible in Herdr's workspace list.
  }
}

/**
 * One pane-native control action, expressed in the owning backend's own TUI
 * vocabulary. Unsupported verbs are rejected by the backend's capability set.
 */
export async function controlSession(
  herdr: HerdrPort,
  params: ControlParams,
): Promise<void> {
  const { paneId, action } = params;
  // Abort is the one emergency control: Escape is identical across backends
  // and runs without pane identity, so a transient snapshot failure can never
  // block it. Every other verb goes through the pane's backend, which rejects
  // actions outside the agent's capabilities (e.g. retry/fork on claude).
  if (action === "abort") {
    await herdr.paneSendKeys(paneId, ["escape"]);
    return;
  }
  const backend = await backendForPane(herdr, paneId);
  try {
    await backend.control(herdr, params);
  } catch (error) {
    // Backends throw BridgeError with a deliberate status (e.g. 404 for a
    // vanished pane); everything else is a bad control request.
    if (error instanceof BridgeError) throw error;
    throw new SessionsError(error instanceof Error ? error.message : String(error), 400);
  }
}

/** The registered backend that owns a live pane (by herdr's agent label). */
async function backendForPane(herdr: HerdrPort, paneId: string): Promise<AgentBackend> {
  try {
    const snapshot = await herdr.snapshot();
    const pane = snapshot.panes.find((candidate) => candidate.pane_id === paneId);
    const backend =
      backendForAgentSessionInfo(pane?.agent_session) ??
      getBackendOrNull(pane?.agent ?? "") ??
      (() => {
        const agent = snapshot.agents.find((candidate) => candidate.pane_id === paneId);
        return agent ? backendForAgentSessionInfo(agent.agent_session) ?? getBackendOrNull(agent.agent) : null;
      })();
    if (backend) return backend;
  } catch {
    // fall through
  }
  throw new SessionsError("pane has no registered agent backend", 404);
}

export { shellQuote, findPaneWorkspace };
export type { ControlAction };
