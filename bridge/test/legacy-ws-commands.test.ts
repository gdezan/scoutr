import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { handleLegacyWsCommand } from "../src/commands.js";
import { fakeHerdr } from "./support/fake-herdr.js";
import type { ServerDeps } from "../src/routes/types.js";
import { fakeFeed } from "./support/fake-feed.js";
import { FakeTerminalLauncher } from "./support/fake-terminal.js";
import type { PaneInfo, SessionSnapshot } from "../src/herdr/types.js";

/**
 * Legacy `/ws` command compatibility, for APKs installed before
 * `commands.http.v1`. These tests pin the frame vocabulary and its ack
 * shapes; the command semantics themselves are specified once, against the
 * shared operations, in session-commands.test.ts. `subscribe` and `ping` are
 * not legacy — they are the live topology-feed vocabulary, answered on the
 * connection in server.ts and covered by its socket test.
 */

/** One pane owned by the requested agent, so answers reach a real backend. */
function makeSnapshot(agent: string | null): SessionSnapshot {
  const pane: PaneInfo = {
    pane_id: "p1",
    workspace_id: "ws1",
    tab_id: "t1",
    terminal_id: "term1",
    focused: false,
    agent_status: "idle",
    revision: 0,
    agent,
    display_agent: agent,
    agent_session: null,
    cwd: null,
    foreground_cwd: null,
    label: null,
    title: null,
    terminal_title: null,
    terminal_title_stripped: null,
    state_labels: {},
    scroll: null,
  };
  return {
    version: "0.8.0",
    protocol: 19,
    focused_workspace_id: null,
    focused_tab_id: null,
    focused_pane_id: null,
    workspaces: [],
    tabs: [],
    // The pane is always live; `agent: null` is the unknown-agent fallback,
    // not a missing pane (which the shared operations answer with a 404).
    panes: [pane],
    agents: [],
    layouts: [],
  };
}

function makeDeps(agent?: "pi" | "claude"): { herdr: ReturnType<typeof fakeHerdr>; deps: ServerDeps } {
  const herdr = fakeHerdr();
  return {
    herdr,
    deps: {
      herdr,
      feed: fakeFeed(makeSnapshot(agent ?? null)),
      usage: {} as never,
      config: { configDir: "/tmp/scoutr-test-config", token: "x".repeat(16), port: 1 },
      terminal: new FakeTerminalLauncher(),
    },
  };
}

describe("legacy WS mutation frames (pre-commands.http.v1 APKs)", () => {
  test("steer frame reaches agent.prompt and acks with the target", async () => {
    const { herdr, deps } = makeDeps();
    const result = await handleLegacyWsCommand({ type: "steer", target: "p1", text: "keep going" }, deps);
    assert.deepEqual(result, { type: "steered", target: "p1", result: {} });
    assert.deepEqual(herdr.sent, [{ method: "agentPrompt", params: { target: "p1", text: "keep going" } }]);
  });

  test("answer_ask frame delivers the round and acks with the call id", async () => {
    const { herdr, deps } = makeDeps();
    const result = await handleLegacyWsCommand({ type: "answer_ask", paneId: "p1", text: "yes, do it" }, deps);
    assert.deepEqual(result, { type: "answered", paneId: "p1", callId: "" });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "yes, do it" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("dismiss_ask frame escapes the questionnaire", async () => {
    const { herdr, deps } = makeDeps("pi");
    const result = await handleLegacyWsCommand({ type: "dismiss_ask", paneId: "p1" }, deps);
    assert.deepEqual(result, { type: "dismissed", paneId: "p1" });
    assert.deepEqual(herdr.sent, [{ method: "paneSendKeys", params: { pane_id: "p1", keys: ["escape"] } }]);
  });

  test("slash_command frame sends the validated command plus Enter", async () => {
    const { herdr, deps } = makeDeps();
    const result = await handleLegacyWsCommand({ type: "slash_command", paneId: "p1", text: "/compact" }, deps);
    assert.deepEqual(result, { type: "command_sent", paneId: "p1", text: "/compact" });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendInput", params: { pane_id: "p1", text: "/compact", keys: ["Enter"] } },
    ]);
  });

  test("send_text frame delivers raw text without keys", async () => {
    const { herdr, deps } = makeDeps();
    const result = await handleLegacyWsCommand({ type: "send_text", paneId: "p1", text: "hello" }, deps);
    assert.deepEqual(result, { type: "sent", paneId: "p1" });
    assert.deepEqual(herdr.sent, [{ method: "paneSendText", params: { pane_id: "p1", text: "hello" } }]);
  });

  test("the adapter adds no validation of its own — rejections come from the shared operation", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(
      () => handleLegacyWsCommand({ type: "slash_command", paneId: "p1", text: "/compact\n/quit" } as never, deps),
      /invalid slash command/,
    );
    await assert.rejects(
      () => handleLegacyWsCommand({ type: "steer", target: "", text: "x" } as never, deps),
      /target and text/,
    );
    assert.deepEqual(herdr.sent, []);
  });

  test("unknown commands throw", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(() => handleLegacyWsCommand({ type: "explode" } as never, deps), /unknown command/);
    assert.deepEqual(herdr.sent, []);
  });
});
