import type { AgentReadResponse } from "../../src/herdr/client.js";
import type { HerdrPort } from "../../src/herdr/port.js";
import type { SessionSnapshot } from "../../src/herdr/types.js";

/** One recorded herdr call: the port method and the named arguments it received. */
export interface SentInput {
  method: keyof HerdrPort;
  params: Record<string, unknown>;
}

export interface FakeHerdrExtras {
  /** Every herdr call the bridge made, in order. Reset with `sent.length = 0`. */
  readonly sent: SentInput[];
  /** Replace the snapshot returned by `snapshot()`. */
  setSnapshot(next: SessionSnapshot): void;
  /** The next call to `method` rejects with `error`; later calls behave normally. */
  failNext(method: keyof HerdrPort, error: Error): void;
}

const DEFAULT_SNAPSHOT: SessionSnapshot = {
  version: "0.8.0",
  protocol: 19,
  focused_workspace_id: null,
  focused_tab_id: null,
  focused_pane_id: null,
  workspaces: [],
  tabs: [],
  panes: [],
  agents: [],
  layouts: [],
};

/**
 * In-memory HerdrPort: records every call (method + named args) in `sent`,
 * serves a mutable snapshot, and can fail one call per method on demand.
 * Workspace creation always yields `ws1`/`p1` unless overridden via
 * `setSnapshot` + a stubbed `workspaceCreate`.
 */
export function fakeHerdr(initial: Partial<SessionSnapshot> = {}): HerdrPort & FakeHerdrExtras {
  const sent: SentInput[] = [];
  let snapshot: SessionSnapshot = { ...DEFAULT_SNAPSHOT, ...initial };
  const failures = new Map<keyof HerdrPort, Error[]>();

  const takeFailure = (method: keyof HerdrPort): Error | undefined => {
    const queue = failures.get(method);
    const error = queue?.shift();
    if (queue && queue.length === 0) failures.delete(method);
    return error;
  };

  const herdr: HerdrPort = {
    async ping() {
      sent.push({ method: "ping", params: {} });
      throwIfFailed(takeFailure("ping"));
      return { type: "pong", version: "test", protocol: 19, capabilities: {} };
    },
    async snapshot() {
      sent.push({ method: "snapshot", params: {} });
      throwIfFailed(takeFailure("snapshot"));
      return snapshot;
    },
    async workspaceCreate(params) {
      sent.push({ method: "workspaceCreate", params: { ...params } });
      throwIfFailed(takeFailure("workspaceCreate"));
      return { workspace: { workspace_id: "ws1" }, root_pane: { pane_id: "p1" } };
    },
    async tabCreate(params) {
      sent.push({ method: "tabCreate", params: { ...params } });
      throwIfFailed(takeFailure("tabCreate"));
      return { tab: { tab_id: "t1" }, root_pane: { pane_id: "p1" } };
    },
    async tabRename(tab_id, label) {
      sent.push({ method: "tabRename", params: { tab_id, label } });
      throwIfFailed(takeFailure("tabRename"));
      return {};
    },
    async tabClose(tab_id) {
      sent.push({ method: "tabClose", params: { tab_id } });
      throwIfFailed(takeFailure("tabClose"));
      return {};
    },
    async paneRename(pane_id, label) {
      sent.push({ method: "paneRename", params: { pane_id, label } });
      throwIfFailed(takeFailure("paneRename"));
      return {};
    },
    async paneClose(pane_id) {
      sent.push({ method: "paneClose", params: { pane_id } });
      throwIfFailed(takeFailure("paneClose"));
      return {};
    },
    async workspaceRename(workspace_id, label) {
      sent.push({ method: "workspaceRename", params: { workspace_id, label } });
      throwIfFailed(takeFailure("workspaceRename"));
      return {};
    },
    async workspaceClose(workspace_id) {
      sent.push({ method: "workspaceClose", params: { workspace_id } });
      throwIfFailed(takeFailure("workspaceClose"));
      return {};
    },
    async paneSendText(pane_id, text) {
      sent.push({ method: "paneSendText", params: { pane_id, text } });
      throwIfFailed(takeFailure("paneSendText"));
      return {};
    },
    async paneSendKeys(pane_id, keys) {
      sent.push({ method: "paneSendKeys", params: { pane_id, keys } });
      throwIfFailed(takeFailure("paneSendKeys"));
      return {};
    },
    async paneSendInput(pane_id, text, keys = []) {
      sent.push({ method: "paneSendInput", params: { pane_id, text, keys } });
      throwIfFailed(takeFailure("paneSendInput"));
      return {};
    },
    async agentPrompt(target, text) {
      sent.push({ method: "agentPrompt", params: { target, text } });
      throwIfFailed(takeFailure("agentPrompt"));
      return {};
    },
    async agentGet(target, timeoutMs) {
      sent.push({ method: "agentGet", params: { target, timeoutMs } });
      throwIfFailed(takeFailure("agentGet"));
      return { type: "agent" };
    },
    async agentRead(target, source, options = {}) {
      sent.push({ method: "agentRead", params: { target, source, ...options } });
      throwIfFailed(takeFailure("agentRead"));
      return {
        type: "pane_read",
        read: {
          pane_id: target,
          workspace_id: "ws1",
          tab_id: "t1",
          source,
          format: options.format ?? "text",
          text: "",
          revision: 0,
          truncated: false,
        },
      } as AgentReadResponse;
    },
  };

  return {
    ...herdr,
    sent,
    setSnapshot(next: SessionSnapshot) {
      snapshot = next;
    },
    failNext(method: keyof HerdrPort, error: Error) {
      const queue = failures.get(method) ?? [];
      queue.push(error);
      failures.set(method, queue);
    },
  };
}

function throwIfFailed(error: Error | undefined): void {
  if (error) throw error;
}
