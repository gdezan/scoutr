import { BridgeError } from "../errors.js";
import type { HerdrPort } from "./port.js";

/**
 * The session-scoped unit of a pane: its tab. Workspaces are per folder and a
 * session is one tab in one (see `sessions.ts`), so close and rename act on
 * the tab — the folder's other sessions must survive both. The last tab takes
 * the workspace with it, leaving no empty workspace behind.
 */
async function sessionScope(herdr: HerdrPort, paneId: string): Promise<{ tabId: string; workspaceId: string; lastTab: boolean }> {
  let snapshot;
  try {
    snapshot = await herdr.snapshot();
  } catch {
    throw new BridgeError("pane not found in the snapshot", 404);
  }
  const pane = snapshot.panes.find((candidate) => candidate.pane_id === paneId);
  if (!pane) throw new BridgeError("pane not found in the snapshot", 404);
  const tabs = snapshot.tabs.filter((tab) => tab.workspace_id === pane.workspace_id);
  return { tabId: pane.tab_id, workspaceId: pane.workspace_id, lastTab: tabs.length <= 1 };
}

/** Close the session running in a pane: its tab, or the workspace it was alone in. */
export async function closeSessionPane(herdr: HerdrPort, paneId: string): Promise<void> {
  const scope = await sessionScope(herdr, paneId);
  if (scope.lastTab) await herdr.workspaceClose(scope.workspaceId);
  else await herdr.tabClose(scope.tabId);
}

/** Rename the session running in a pane, which names its tab, not its folder's workspace. */
export async function renameSessionPane(herdr: HerdrPort, paneId: string, label: string): Promise<void> {
  const scope = await sessionScope(herdr, paneId);
  await herdr.tabRename(scope.tabId, label);
}

/** Resolve a pane's workspace id from the live snapshot. */
export async function findPaneWorkspace(herdr: HerdrPort, paneId: string): Promise<string> {
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

/** Resolve a pane's reported session transcript path (path-kind references only). */
export async function findPaneSessionPath(herdr: HerdrPort, paneId: string): Promise<string> {
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
