import type { PaneInfo, SessionSnapshot, TabInfo, WorkspaceInfo } from "../../src/herdr/types.js";

/**
 * Snapshot builders: every herdr entity spelled out once, with `overrides` for
 * the one or two fields a test actually cares about. Tests that assert over
 * hierarchy shape (workspace/tab/pane relations) share these so a protocol
 * field lands in one place.
 */
export function pane(overrides: Partial<PaneInfo> = {}): PaneInfo {
  return {
    pane_id: "p1",
    workspace_id: "ws1",
    tab_id: "t1",
    terminal_id: "term1",
    focused: false,
    agent_status: "idle",
    revision: 0,
    agent: null,
    display_agent: null,
    agent_session: null,
    cwd: null,
    foreground_cwd: null,
    label: null,
    title: null,
    terminal_title: null,
    terminal_title_stripped: null,
    state_labels: {},
    scroll: null,
    ...overrides,
  };
}

export function tab(overrides: Partial<TabInfo> = {}): TabInfo {
  return { tab_id: "t1", workspace_id: "ws1", number: 1, label: "Tab 1", focused: false, pane_count: 0, agent_status: "idle", ...overrides };
}

export function workspace(overrides: Partial<WorkspaceInfo> = {}): WorkspaceInfo {
  return {
    workspace_id: "ws1",
    number: 1,
    label: "Workspace 1",
    focused: false,
    pane_count: 0,
    tab_count: 0,
    active_tab_id: "t1",
    agent_status: "idle",
    worktree: null,
    ...overrides,
  };
}

export function snapshot(panes: PaneInfo[], tabs: TabInfo[] = [], workspaces: WorkspaceInfo[] = []): SessionSnapshot {
  return {
    version: "0.8.0",
    protocol: 19,
    focused_workspace_id: null,
    focused_tab_id: null,
    focused_pane_id: null,
    workspaces,
    tabs,
    panes,
    agents: [],
    layouts: [],
  };
}
