/**
 * Types mirroring the herdr 0.8.0 JSON-RPC protocol (protocol 19).
 * Grounding artifact: `herdr api schema --json` captured at bridge/reference/herdr-schema.json.
 */

export type JsonValue = string | number | boolean | null | JsonValue[] | object;

/**
 * Arbitrary JSON value as sent by herdr: primitives, arrays, and nested
 * objects. Used for protocol maps (pane/workspace tokens, event payloads)
 * whose shape herdr leaves open.
 */
export type HerdrValue = string | number | boolean | null | HerdrValue[] | { [key: string]: HerdrValue };


export type AgentStatus = "idle" | "working" | "blocked" | "done" | "unknown";

export interface AgentSessionInfo {
  source: string;
  agent: string;
  kind: "id" | "path";
  value: string;
}

export interface PaneScrollInfo {
  offset_from_bottom: number;
  max_offset_from_bottom: number;
  viewport_rows: number;
}

export interface PaneInfo {
  pane_id: string;
  workspace_id: string;
  tab_id: string;
  terminal_id: string;
  focused: boolean;
  agent_status: AgentStatus;
  revision: number;
  agent: string | null;
  display_agent: string | null;
  agent_session: AgentSessionInfo | null;
  cwd: string | null;
  foreground_cwd: string | null;
  label: string | null;
  title: string | null;
  terminal_title: string | null;
  terminal_title_stripped: string | null;
  state_labels: Record<string, string>;
  scroll: PaneScrollInfo | null;
  state_change_seq?: number;
  screen_detection_skipped?: boolean;
  tokens?: Record<string, HerdrValue>;
}

export interface WorkspaceInfo {
  workspace_id: string;
  number: number;
  label: string;
  focused: boolean;
  pane_count: number;
  tab_count: number;
  active_tab_id: string;
  agent_status: AgentStatus;
  worktree: unknown | null;
  tokens?: Record<string, HerdrValue>;
}

export interface TabInfo {
  tab_id: string;
  workspace_id: string;
  number: number;
  label: string;
  focused: boolean;
  pane_count: number;
  agent_status: AgentStatus;
}

export interface AgentInfo {
  agent: string;
  agent_status: AgentStatus;
  pane_id: string;
  workspace_id: string;
  tab_id: string;
  terminal_id: string;
  focused: boolean;
  cwd: string | null;
  foreground_cwd: string | null;
  agent_session: AgentSessionInfo | null;
  revision: number;
  state_change_seq: number;
  state_labels?: Record<string, string>;
  terminal_title?: string | null;
  terminal_title_stripped?: string | null;
  screen_detection_skipped?: boolean;
}

export interface SessionSnapshot {
  version: string;
  protocol: number;
  focused_workspace_id: string | null;
  focused_tab_id: string | null;
  focused_pane_id: string | null;
  workspaces: WorkspaceInfo[];
  tabs: TabInfo[];
  panes: PaneInfo[];
  agents: AgentInfo[];
  layouts: unknown[];
}

/** Subscription selector for events.subscribe. */
export type Subscription =
  | { type: "workspace.created" }
  | { type: "workspace.updated" }
  | { type: "workspace.metadata_updated" }
  | { type: "workspace.renamed" }
  | { type: "workspace.moved" }
  | { type: "workspace.reordered" }
  | { type: "workspace.closed" }
  | { type: "workspace.focused" }
  | { type: "worktree.created" }
  | { type: "worktree.opened" }
  | { type: "worktree.removed" }
  | { type: "tab.created" }
  | { type: "tab.closed" }
  | { type: "tab.focused" }
  | { type: "tab.renamed" }
  | { type: "tab.moved" }
  | { type: "pane.created" }
  | { type: "pane.closed" }
  | { type: "pane.updated" }
  | { type: "pane.focused" }
  | { type: "pane.moved" }
  | { type: "pane.exited" }
  | { type: "pane.agent_detected" }
  | { type: "pane.output_matched"; pane_id: string; source: string; match: string; lines?: number }
  | { type: "pane.agent_status_changed"; pane_id: string }
  | { type: "pane.scroll_changed"; pane_id: string }
  | { type: "layout.updated" };

/** Envelope pushed after a successful subscribe. */
export interface SubscriptionEventEnvelope {
  event: string;
  data: Record<string, HerdrValue>;
}

export interface HerdrPong {
  type: "pong";
  version: string;
  protocol: number;
  capabilities: Record<string, boolean>;
}

export interface AgentReadResult {
  pane_id: string;
  workspace_id: string;
  tab_id: string;
  source: string;
  format: string;
  text: string;
  revision: number;
  truncated: boolean;
}
