import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { handleCommand } from "../src/commands.js";
import { fakeHerdr } from "./support/fake-herdr.js";
import type { ServerDeps } from "../src/routes/types.js";
import { fakeFeed } from "./support/fake-feed.js";
import type { PaneInfo, SessionSnapshot } from "../src/herdr/types.js";

/**
 * One pane owned by the requested agent, so `resolveBackendForPane` finds a
 * real backend and the answer path under test is the backend's own
 * implementation — not the unknown-agent fallback.
 */
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
    panes: agent ? [pane] : [],
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
      config: { token: "x".repeat(16), port: 1 },
    },
  };
}

describe("WS command dispatch", () => {
  test("ping returns a pong with a timestamp", async () => {
    const { deps } = makeDeps();
    const result = await handleCommand({ type: "ping" }, deps);
    assert.equal(result.type, "pong");
    assert.ok((result as { ts: number }).ts > 0);
  });

  test("subscribe acknowledges the requested filters (no-op wiring)", async () => {
    const { deps } = makeDeps();
    const result = await handleCommand({ type: "subscribe", filter: ["pane_closed"] }, deps);
    assert.deepEqual(result, { type: "subscribed", filters: ["pane_closed"] });
  });

  test("steer sends the prompt through agent.prompt", async () => {
    const { herdr, deps } = makeDeps();
    const result = await handleCommand({ type: "steer", target: "p1", text: "keep going" }, deps);
    assert.equal(result.type, "steered");
    assert.equal((result as { target: string }).target, "p1");
    assert.deepEqual(herdr.sent, [{ method: "agentPrompt", params: { target: "p1", text: "keep going" } }]);
  });

  test("steer requires target and text", async () => {
    const { deps } = makeDeps();
    await assert.rejects(() => handleCommand({ type: "steer", target: "", text: "x" } as never, deps), /target and text/);
  });

  test("answer_question types the sanitized answer then Enter", async () => {
    const { herdr, deps } = makeDeps();
    const result = await handleCommand({ type: "answer_question", paneId: "p1", text: "yes, do it" }, deps);
    assert.equal(result.type, "answered");
    assert.equal((result as { text: string }).text, "yes, do it");
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "yes, do it" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("answer_question strips control characters before sending", async () => {
    const { herdr, deps } = makeDeps();
    const result = await handleCommand({ type: "answer_question", paneId: "p1", text: "a\u0000b\u001bc" }, deps);
    assert.equal((result as { text: string }).text, "abc");
    assert.equal((herdr.sent[0]?.params as { text: string }).text, "abc");
  });

  test("answer_question with keys sends navigation keys only for option answers (pi questionnaire)", async () => {
    const { herdr, deps } = makeDeps("pi");
    const result = await handleCommand(
      { type: "answer_question", paneId: "p1", text: "", keys: ["down", "down", "enter"] },
      deps,
    );
    assert.equal(result.type, "answered");
    assert.deepEqual(herdr.sent, [{ method: "paneSendKeys", params: { pane_id: "p1", keys: ["down", "down", "enter"] } }]);
  });

  test("answer_question with keys and text opens the editor then types and submits (pi backend)", async () => {
    const { herdr, deps } = makeDeps("pi");
    await handleCommand(
      { type: "answer_question", paneId: "p1", text: "Mango", keys: ["down", "enter"], trailingKeys: ["enter", "enter"] },
      deps,
    );
    assert.deepEqual(herdr.sent, [
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["down", "enter"] } },
      { method: "paneSendText", params: { pane_id: "p1", text: "Mango" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["enter", "enter"] } },
    ]);
  });

  test("answer_question rejects empty text without keys", async () => {
    const { deps } = makeDeps();
    await assert.rejects(
      () => handleCommand({ type: "answer_question", paneId: "p1", text: "" } as never, deps),
      /text or keys/,
    );
  });

  test("unknown-agent answers require text: keys alone are rejected (no questionnaire protocol)", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(
      () => handleCommand({ type: "answer_question", paneId: "p1", text: "", keys: ["down", "enter"] } as never, deps),
      /requires text for unknown agents/,
    );
    assert.deepEqual(herdr.sent, []);
  });

  test("answer_question rejects keys outside the navigation allowlist", async () => {
    const { deps } = makeDeps();
    await assert.rejects(
      () => handleCommand({ type: "answer_question", paneId: "p1", text: "", keys: ["down", "a"] } as never, deps),
      /bounded sequence of navigation keys/,
    );
    await assert.rejects(
      () => handleCommand({ type: "answer_question", paneId: "p1", text: "x", trailingKeys: new Array(33).fill("enter") } as never, deps),
      /bounded sequence of navigation keys/,
    );
  });
  test("slash_command sends the validated command plus Enter", async () => {
    const { herdr, deps } = makeDeps();
    const result = await handleCommand({ type: "slash_command", paneId: "p1", text: "/compact" }, deps);
    assert.equal(result.type, "command_sent");
    assert.equal((result as { text: string }).text, "/compact");
    assert.deepEqual(herdr.sent, [
      { method: "paneSendInput", params: { pane_id: "p1", text: "/compact", keys: ["Enter"] } },
    ]);
  });

  test("slash_command rejects terminal control input", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(
      () => handleCommand({ type: "slash_command", paneId: "p1", text: "/compact\n/quit" } as never, deps),
      /invalid slash command/,
    );
    assert.deepEqual(herdr.sent, []);
  });

  test("send_text delivers raw text without keys", async () => {
    const { herdr, deps } = makeDeps();
    const result = await handleCommand({ type: "send_text", paneId: "p1", text: "hello" }, deps);
    assert.equal(result.type, "sent");
    assert.deepEqual(herdr.sent, [{ method: "paneSendText", params: { pane_id: "p1", text: "hello" } }]);
  });

  test("send_text rejects text that would alter PTY submission (newline/control chars)", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(
      () => handleCommand({ type: "send_text", paneId: "p1", text: "line one\nline two" } as never, deps),
      /plain single-line text/,
    );
    await assert.rejects(
      () => handleCommand({ type: "send_text", paneId: "p1", text: `ctrl${String.fromCharCode(0)}char` } as never, deps),
      /plain single-line text/,
    );
    await assert.rejects(
      () => handleCommand({ type: "send_text", paneId: "p1", text: "" } as never, deps),
      /plain single-line text/,
    );
    assert.deepEqual(herdr.sent, []);
  });

  test("steer accepts multi-line prompt text (createSession prompt limit)", async () => {
    const { herdr, deps } = makeDeps();
    const text = "first line\nsecond line";
    const result = await handleCommand({ type: "steer", target: "p1", text }, deps);
    assert.equal(result.type, "steered");
    assert.deepEqual(herdr.sent, [{ method: "agentPrompt", params: { target: "p1", text } }]);
  });

  test("steer rejects NUL/DEL control characters", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(
      () => handleCommand({ type: "steer", target: "p1", text: `ok${String.fromCharCode(0)}` } as never, deps),
      /without control characters/,
    );
    assert.deepEqual(herdr.sent, []);
  });

  test("claude answers flatten multi-line text to one line then Enter", async () => {
    const { herdr, deps } = makeDeps("claude");
    const result = await handleCommand({ type: "answer_question", paneId: "p1", text: "keep\nworking" }, deps);
    assert.equal(result.type, "answered");
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "keep working" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("pi answers replay the full key sequence through the backend (keys, text, trailingKeys)", async () => {
    const { herdr, deps } = makeDeps("pi");
    const result = await handleCommand(
      { type: "answer_question", paneId: "p1", text: "Mango", keys: ["down", "enter"], trailingKeys: ["enter", "enter"] },
      deps,
    );
    assert.equal(result.type, "answered");
    assert.deepEqual(herdr.sent, [
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["down", "enter"] } },
      { method: "paneSendText", params: { pane_id: "p1", text: "Mango" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["enter", "enter"] } },
    ]);
  });

  test("unknown commands throw", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(() => handleCommand({ type: "explode" } as never, deps), /unknown command/);
    assert.deepEqual(herdr.sent, []);
  });
});
