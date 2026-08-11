import type { HerdrPort } from "./port.js";

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
