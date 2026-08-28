import type { HerdrPort } from "./herdr/port.js";
import type { PaneInfo, SessionSnapshot } from "./herdr/types.js";
import { BridgeError } from "./errors.js";
import { canonicalPath, resolveAllowedDir } from "./dirs.js";
import { resolveCatalogSessionPath } from "./session-catalog.js";
import { backendFor, resolveBackendForPane } from "./agents/registry.js";
import type { AgentBackend, ControlAction, ControlParams } from "./agents/types.js";
import { shellQuote } from "./shell.js";
import { findPaneWorkspace } from "./herdr/panes.js";
import type { WorkspaceRootStore } from "./workspace-roots.js";
import { realpathSync, statSync } from "node:fs";
import { basename, dirname, resolve } from "node:path";

/**
 * pi's documented `--thinking` levels (README: Model Options) — the only
 * thinking vocabulary the create-session surface knows. Backends that cannot
 * express a thinking level simply never receive one (capability gated).
 */
export const THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"] as const;
const THINKING_LEVEL_SET = new Set<string>(THINKING_LEVELS);
export type ThinkingLevel = (typeof THINKING_LEVELS)[number];

export interface CreateSessionParams {
  cwd: string;
  model: string;
  name?: string;
  thinkingLevel?: string;
  initialPrompt?: string;
  /** Registry backend id; defaults to "pi". */
  agent?: string;
  /**
   * Scoutr-awareness text appended to the agent's system prompt
   * (see `agents/scoutr-context.ts`); absent for sessions launched outside Scoutr.
   */
  scoutrContext?: string;
}

export interface CreatedSession {
  workspaceId: string;
  paneId: string;
}

export type StoredSessionMode = "resume" | "fork";

export interface LaunchStoredSessionParams {
  path: string;
  mode: StoredSessionMode;
  /** Scoutr-awareness text appended to the agent's system prompt; see `agents/scoutr-context.ts`. */
  scoutrContext?: string;
}

export class SessionsError extends BridgeError {
  constructor(message: string, status = 400) {
    super(message, status);
  }
}

const MAX_MODEL_LENGTH = 200;
const MAX_NAME_LENGTH = 100;
export const MAX_PROMPT_LENGTH = 100_000;
const CONTROL_CHAR = /\p{Cc}/u;
/** A prompt may carry newlines; NUL truncates argv and DEL garbles PTY input. */
export function promptHasForbiddenChar(text: string): boolean {
  return text.includes("\u0000") || text.includes("\u007f");
}
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
  if (params.cwd === "") {
    throw new SessionsError("cwd is required");
  }
  assertNoControlChars("cwd", params.cwd);
  if (params.model !== undefined && params.model !== "") {
    if (params.model.length > MAX_MODEL_LENGTH) {
      throw new SessionsError(`model is too long (max ${MAX_MODEL_LENGTH} characters)`);
    }
    assertNoControlChars("model", params.model);
  }

  if (params.thinkingLevel !== undefined) {
    if (!THINKING_LEVEL_SET.has(params.thinkingLevel)) {
      throw new SessionsError(`unknown thinking level: ${String(params.thinkingLevel)}`);
    }
  }

  if (params.name !== undefined && params.name !== "") {
    if (params.name.length > MAX_NAME_LENGTH) {
      throw new SessionsError(`name is too long (max ${MAX_NAME_LENGTH} characters)`);
    }
    assertNoControlChars("name", params.name);
  }

  if (params.initialPrompt !== undefined && params.initialPrompt !== "") {
    if (params.initialPrompt.length > MAX_PROMPT_LENGTH) {
      throw new SessionsError(`initialPrompt is too long (max ${MAX_PROMPT_LENGTH} characters)`);
    }
    if (promptHasForbiddenChar(params.initialPrompt)) throw new SessionsError("initialPrompt must not contain NUL or DEL");
  }
}

/**
 * Open a pane for the folder (a tab in its workspace, or a new workspace when
 * the folder has none), start the agent, wait for Herdr to detect it, and
 * deliver the first prompt through agent.prompt. Any launch failure undoes
 * what it created. The launch command is the selected backend's own grammar.
 */
export async function createSession(
  herdr: HerdrPort,
  params: CreateSessionParams,
  workspaceRoots?: WorkspaceRootStore,
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
    scoutrContext: params.scoutrContext,
  });
  return launchWorkspace(herdr, cwd, name, command, workspaceRoots, async (paneId) => {
    if (!params.initialPrompt) return;
    if (backend.deliverInitialPrompt) {
      await backend.deliverInitialPrompt(herdr, paneId, params.initialPrompt);
    } else {
      await herdr.agentPrompt(paneId, params.initialPrompt);
    }
  });
}

/**
 * Open a stored session through the owning backend, in the Herdr workspace
 * rooted at its recorded cwd (a new workspace when that folder has none).
 */
export async function launchStoredSession(
  herdr: HerdrPort,
  params: LaunchStoredSessionParams,
  workspaceRoots?: WorkspaceRootStore,
): Promise<CreatedSession> {
  const { path, backend } = await resolveCatalogSessionPath(params.path);
  const session = await backend.readTranscript(path, { metadataOnly: true });
  if (!session.cwd) throw new SessionsError("session working directory is unavailable", 409);
  const cwd = resolveSessionWorkspace(session.cwd, path, backend.sessionRoot());
  let command: string;
  try {
    command = backend.resumeCommand(path, params.mode, params.scoutrContext);
  } catch (error) {
    // e.g. forking a claude transcript, which has no fork-at-path launch.
    throw new SessionsError(error instanceof Error ? error.message : String(error), 400);
  }
  return launchWorkspace(
    herdr,
    cwd,
    basename(cwd) || "session",
    command,
    workspaceRoots,
  );
}

/**
 * Working directory for a stored-session launch. The cwd recorded in the
 * session file is trusted (the user already ran an agent there — the same
 * least-privilege reasoning as the review allow-list in review.ts), so a
 * session run outside $HOME can still be resumed. When that directory no
 * longer exists, fall back to the backend's session store root, then to the
 * session file's own directory, so the transcript still opens (resume
 * contract: preserve a usable workspace when the recorded cwd is gone.
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

/**
 * The workspace already rooted at `cwd`, or null when none is. A workspace's
 * folder is the cwd of its **root pane** — the first pane of the workspace in
 * snapshot order, the one herdr created with the workspace itself (the same
 * "first pane of the workspace" convention the terminal hierarchy uses for
 * post-close selection). Only that pane defines the folder: a pane the user
 * later opened and `cd`-ed somewhere else must not claim a folder for the
 * whole workspace. herdr's snapshot carries no workspace cwd, so this is the
 * only grounding available. Two workspaces on one folder resolve to the
 * lowest-numbered one, so repeat launches keep landing in the same place.
 */
export function findWorkspaceForCwd(snapshot: SessionSnapshot, cwd: string): string | null {
  const wanted = canonicalPath(cwd);
  const ordered = [...snapshot.workspaces].sort((a, b) => a.number - b.number);
  for (const workspace of ordered) {
    const root = snapshot.panes.find((pane) => pane.workspace_id === workspace.workspace_id);
    const rootCwd = root?.cwd ?? root?.foreground_cwd;
    if (rootCwd && canonicalPath(rootCwd) === wanted) return workspace.workspace_id;
  }
  return null;
}

/**
 * Where a session landed, plus how to undo it. A reused workspace is undone by
 * closing only the tab this launch added; a workspace created here is undone
 * whole.
 */
interface LaunchTarget {
  workspaceId: string;
  paneId: string;
  /** Set only when this launch created the tab (i.e. the workspace was reused). */
  tabId: string | null;
  /** True when herdr created the whole workspace for this launch. Only then
   * may rollback close it or drop its recorded root.
   */
  createdWorkspace: boolean;
}

/**
 * The workspace a launch should join, from one snapshot: validated persisted
 * metadata first (a recorded root survives its root pane cd-ing elsewhere),
 * then the root-pane cwd inference for workspaces Scoutr never recorded.
 * Every persisted id is checked against the same live snapshot, so a dead
 * workspace id is pruned rather than resurrected. Returns null when no
 * workspace matches or the snapshot is unavailable — a fresh workspace is
 * then the safe fallback (at worst a second workspace on the folder).
 */
async function selectLaunchWorkspace(
  herdr: HerdrPort,
  cwd: string,
  roots?: WorkspaceRootStore,
): Promise<string | null> {
  let snapshot: SessionSnapshot;
  try {
    snapshot = await herdr.snapshot();
  } catch {
    return null;
  }
  const wanted = canonicalPath(cwd);
  if (!roots) return findWorkspaceForCwd(snapshot, wanted);

  const liveIds = new Set(snapshot.workspaces.map((workspace) => workspace.workspace_id));
  await roots.prune(liveIds);
  const numbers = new Map(snapshot.workspaces.map((workspace) => [workspace.workspace_id, workspace.number]));
  const recorded = (await roots.list())
    .filter((record) => liveIds.has(record.workspaceId) && canonicalPath(record.cwd) === wanted)
    .sort((a, b) => (numbers.get(a.workspaceId) ?? Infinity) - (numbers.get(b.workspaceId) ?? Infinity));
  const first = recorded[0];
  if (first) return first.workspaceId;

  // Nothing recorded claims this folder; the snapshot inference is the only
  // grounding left — and reusing it makes the mapping deliberate.
  const inferred = findWorkspaceForCwd(snapshot, wanted);
  if (inferred) await roots.record(inferred, wanted);
  return inferred;
}

/**
 * Open the pane a session will run in. Workspaces are per folder: when one is
 * already rooted at `cwd` (persisted metadata first, inference second) the
 * session becomes a new tab in it, and only an unclaimed folder gets a fresh
 * workspace — which is then recorded so later launches skip the inference.
 * The session name labels the tab; existing workspace labels are never touched.
 */
async function openLaunchTarget(
  herdr: HerdrPort,
  cwd: string,
  label: string,
  roots?: WorkspaceRootStore,
): Promise<LaunchTarget> {
  const existing = await selectLaunchWorkspace(herdr, cwd, roots);

  if (existing) {
    const created = await herdr.tabCreate({ workspace_id: existing, cwd, label, focus: false });
    const tabId = created.tab?.tab_id ?? "";
    const paneId = created.root_pane?.pane_id ?? "";
    if (!paneId) {
      if (tabId) await closeTabQuietly(herdr, tabId);
      throw new SessionsError("herdr did not return a pane id", 502);
    }
    return { workspaceId: existing, paneId, tabId: tabId || null, createdWorkspace: false };
  }

  const ws = await herdr.workspaceCreate({ cwd, focus: false });
  const workspaceId = ws.workspace?.workspace_id ?? "";
  const paneId = ws.root_pane?.pane_id ?? "";
  if (!workspaceId) throw new SessionsError("herdr did not return a workspace id", 502);
  if (!paneId) {
    await closeWorkspaceQuietly(herdr, workspaceId);
    throw new SessionsError("herdr did not return a pane id", 502);
  }
  // Record only after creation fully succeeds: a rolled-back workspace must
  // not leave an entry behind for the prune to clean up.
  await roots?.record(workspaceId, cwd);
  return { workspaceId, paneId, tabId: null, createdWorkspace: true };
}

async function launchWorkspace(
  herdr: HerdrPort,
  cwd: string,
  label: string,
  command: string,
  workspaceRoots?: WorkspaceRootStore,
  afterStart?: (paneId: string) => Promise<void>,
): Promise<CreatedSession> {
  const target = await openLaunchTarget(herdr, cwd, label, workspaceRoots);
  const { workspaceId, paneId } = target;

  try {
    await herdr.paneSendInput(paneId, command, ["Enter"]);
    const snapshot = await waitForAgent(herdr, paneId);
    // A workspace herdr created for us carries its own root tab, which
    // tab.create could not label — name it after the session now.
    if (!target.tabId) await labelRootTabQuietly(herdr, snapshot, paneId, label);
    await afterStart?.(paneId);
    return { workspaceId, paneId };
  } catch (error) {
    await undoLaunchTarget(herdr, target, workspaceRoots);
    throw new SessionsError(`session launch failed: ${error instanceof Error ? error.message : String(error)}`, 502);
  }
}

async function waitForAgent(herdr: HerdrPort, paneId: string): Promise<SessionSnapshot> {
  const deadline = Date.now() + AGENT_START_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const snapshot = await herdr.snapshot();
      const pane = snapshot.panes.find((candidate) => candidate.pane_id === paneId);
      if (pane?.agent || snapshot.agents.some((agent) => agent.pane_id === paneId)) return snapshot;
    } catch {
      // The next poll may succeed while Herdr and the agent finish startup.
    }
    await new Promise((resolve) => setTimeout(resolve, AGENT_POLL_MS));
  }
  throw new SessionsError("agent did not start before the launch timeout", 502);
}

/** Undo exactly what this launch created — never what it merely joined.
 * A joined workspace loses only its new tab (and if herdr gave no tab id,
 * nothing at all: closing the whole workspace would destroy someone else's
 * session). A created workspace is closed, and only when the close succeeds
 * does its recorded root go with it; a failed close leaves the record for
 * prune to settle once herdr reports the workspace's real fate.
 */
async function undoLaunchTarget(
  herdr: HerdrPort,
  target: LaunchTarget,
  roots?: WorkspaceRootStore,
): Promise<void> {
  if (!target.createdWorkspace) {
    if (target.tabId) await closeTabQuietly(herdr, target.tabId);
    return;
  }
  const closed = await closeWorkspaceQuietly(herdr, target.workspaceId);
  if (closed) await roots?.remove(target.workspaceId);
}

async function labelRootTabQuietly(
  herdr: HerdrPort,
  snapshot: SessionSnapshot,
  paneId: string,
  label: string,
): Promise<void> {
  const tabId = snapshot.panes.find((pane) => pane.pane_id === paneId)?.tab_id;
  if (!tabId) return;
  try {
    await herdr.tabRename(tabId, label);
  } catch {
    // A tab label is cosmetic; a running session must not fail over it.
  }
}

async function closeWorkspaceQuietly(herdr: HerdrPort, workspaceId: string): Promise<boolean> {
  try {
    await herdr.workspaceClose(workspaceId);
    return true;
  } catch {
    // Keep the launch error; cleanup failure is visible in Herdr's workspace list.
    return false;
  }
}

async function closeTabQuietly(herdr: HerdrPort, tabId: string): Promise<void> {
  try {
    await herdr.tabClose(tabId);
  } catch {
    // Keep the launch error; a stray tab is visible in Herdr's own hierarchy.
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
  const context = await controlBackendContext(herdr, paneId);
  const { backend } = context;
  if (!backend.capabilities.has(action)) {
    throw new SessionsError(`${backend.id} does not support ${action}`, 400);
  }
  const controlParams = action === "set_thinking"
    ? { ...params, model: await activePaneModel(context.pane, backend) }
    : params;
  try {
    await backend.control(herdr, controlParams);
  } catch (error) {
    // Backends throw BridgeError with a deliberate status (e.g. 404 for a
    // vanished pane); everything else is a bad control request.
    if (error instanceof BridgeError) throw error;
    throw new SessionsError(error instanceof Error ? error.message : String(error), 400);
  }
}

/** The registered backend and pane that own a live control target. */
interface ControlBackendContext {
  backend: AgentBackend;
  pane: PaneInfo | null;
}

async function controlBackendContext(herdr: HerdrPort, paneId: string): Promise<ControlBackendContext> {
  try {
    const snapshot = await herdr.snapshot();
    const pane = snapshot.panes.find((candidate) => candidate.pane_id === paneId);
    const backend = resolveBackendForPane(snapshot, paneId);
    if (backend) return { backend, pane: pane ?? null };
  } catch {
    // fall through
  }
  throw new SessionsError("pane has no registered agent backend", 404);
}

/** Resolve Claude's active model so model-specific effort levels are enforced. */
async function activePaneModel(pane: PaneInfo | null, backend: AgentBackend): Promise<string | undefined> {
  if (backend.id !== "claude" || !pane?.agent_session) return undefined;
  try {
    const path = await backend.resolveSessionPath(pane.agent_session, pane.cwd ?? pane.foreground_cwd ?? undefined);
    if (!path) return undefined;
    return (await backend.readTranscript(path, { metadataOnly: true })).model ?? undefined;
  } catch {
    return undefined;
  }
}

export { shellQuote, findPaneWorkspace };
export type { ControlAction };
