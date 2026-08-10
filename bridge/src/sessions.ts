import type { HerdrClient } from "./herdr/client.js";

export interface CreateSessionParams {
  cwd: string;
  model: string;
  name?: string;
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

/**
 * Create a pane-native pi session: a fresh herdr workspace with one pane,
 * then launch `pi --model <model>` in it. Returns the new pane + workspace ids.
 */
export async function createSession(
  herdr: HerdrClient,
  params: CreateSessionParams,
): Promise<CreatedSession> {
  if (!params.cwd || !params.model) {
    throw new SessionsError("cwd and model are required");
  }
  const name = params.name?.trim() || params.cwd.split("/").filter(Boolean).pop() || "session";
  const ws = await herdr.workspaceCreate({ cwd: params.cwd, label: name, focus: false });
  const workspaceId = ws.workspace?.workspace_id ?? "";
  const paneId = ws.root_pane?.pane_id ?? "";
  if (!workspaceId) throw new SessionsError("herdr did not return a workspace id", 502);
  if (!paneId) throw new SessionsError("herdr did not return a pane id", 502);

  await herdr.paneSendText(paneId, `pi --model ${params.model}`);
  await herdr.paneSendKeys(paneId, ["Enter"]);
  return { workspaceId, paneId };
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
