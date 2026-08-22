import { test, describe } from "node:test";
import assert from "node:assert/strict";
import {
  answerSessionAsk,
  dismissSessionAsk,
  runSlashCommand,
  sendSessionText,
  steerSession,
} from "../src/commands.js";
import { BridgeError } from "../src/errors.js";
import { fakeHerdr } from "./support/fake-herdr.js";
import type { ServerDeps } from "../src/routes/types.js";
import { fakeFeed } from "./support/fake-feed.js";
import { FakeTerminalLauncher } from "./support/fake-terminal.js";
import type { PaneInfo, SessionSnapshot } from "../src/herdr/types.js";

/**
 * The shared command operations, exercised with no socket and no HTTP server
 * — this is the one place the validation limits and herdr side effects are
 * specified. The HTTP routes on top of them are covered by
 * session-commands-http.test.ts and the legacy frame adapter by
 * legacy-ws-commands.test.ts.
 */

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
    // The pane is always live. `agent: null` means "no recognized backend"
    // (the unknown-agent fallback), which is not the same as a missing pane —
    // that case is exercised explicitly below against an empty snapshot.
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
      config: { configDir: "/tmp/scoutr-test-config", hostId: "host_test", token: "x".repeat(16), port: 1 },
      terminal: new FakeTerminalLauncher(),
    },
  };
}

/** The HTTP status a rejected operation carries, so routes need no mapping table. */
async function statusOf(run: () => Promise<unknown>): Promise<number> {
  try {
    await run();
  } catch (error) {
    assert.ok(error instanceof BridgeError, `expected a BridgeError, got ${String(error)}`);
    return error.status;
  }
  throw new assert.AssertionError({ message: "expected the operation to reject" });
}

describe("shared session command operations", () => {
  test("steerSession sends the prompt through agent.prompt", async () => {
    const { herdr, deps } = makeDeps();
    await steerSession(deps, "p1", "keep going");
    assert.deepEqual(herdr.sent, [{ method: "agentPrompt", params: { target: "p1", text: "keep going" } }]);
  });

  test("steerSession accepts multi-line prompt text", async () => {
    const { herdr, deps } = makeDeps();
    const text = "first line\nsecond line";
    await steerSession(deps, "p1", text);
    assert.deepEqual(herdr.sent, [{ method: "agentPrompt", params: { target: "p1", text } }]);
  });

  test("steerSession requires target and text", async () => {
    const { deps } = makeDeps();
    await assert.rejects(() => steerSession(deps, "", "x"), /target and text/);
    assert.equal(await statusOf(() => steerSession(deps, "", "x")), 400);
  });

  test("steerSession rejects NUL/DEL control characters", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(
      () => steerSession(deps, "p1", `ok${String.fromCharCode(0)}`),
      /without control characters/,
    );
    assert.deepEqual(herdr.sent, []);
  });

  test("steerSession rejects a prompt past the create-session limit", async () => {
    const { herdr, deps } = makeDeps();
    assert.equal(await statusOf(() => steerSession(deps, "p1", "x".repeat(100_001))), 400);
    assert.deepEqual(herdr.sent, []);
  });

  test("answerSessionAsk types the sanitized answer then Enter", async () => {
    const { herdr, deps } = makeDeps();
    const callId = await answerSessionAsk(deps, { paneId: "p1", text: "yes, do it" });
    assert.equal(callId, "");
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "yes, do it" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("answerSessionAsk strips control characters before sending", async () => {
    const { herdr, deps } = makeDeps();
    await answerSessionAsk(deps, { paneId: "p1", text: "a\u0000b\u001bc" });
    assert.equal((herdr.sent[0]?.params as { text: string }).text, "abc");
  });

  test("answerSessionAsk rejects an unbounded selectedLabels list", async () => {
    const { deps } = makeDeps("pi");
    await assert.rejects(
      () =>
        answerSessionAsk(deps, {
          paneId: "p1",
          callId: "call",
          answers: [{ questionId: "call#0", selectedLabels: new Array(33).fill("Yes") }],
        }),
      /bounded list of option labels/,
    );
  });

  test("answerSessionAsk rejects a callId that is not a tool call id", async () => {
    const { deps } = makeDeps("pi");
    await assert.rejects(
      () => answerSessionAsk(deps, { paneId: "p1", callId: "x".repeat(201) }),
      /must be a tool call id/,
    );
  });

  test("answerSessionAsk rejects a round longer than any ask can be", async () => {
    const { deps } = makeDeps("pi");
    const answers = new Array(9).fill({ questionId: "call#0", selectedLabels: ["Yes"] });
    await assert.rejects(() => answerSessionAsk(deps, { paneId: "p1", callId: "call", answers }), /bounded list/);
  });

  test("answerSessionAsk rejects an empty answer that names no ask", async () => {
    const { deps } = makeDeps("pi");
    await assert.rejects(() => answerSessionAsk(deps, { paneId: "p1", text: "" }), /requires text or an ask/);
  });

  test("an ask that is no longer open is a conflict, not bad input", async () => {
    const { deps } = makeDeps("pi");
    // The pane has no session file, so no question carries this call id.
    assert.equal(
      await statusOf(() =>
        answerSessionAsk(deps, { paneId: "p1", callId: "toolu_gone", answers: [{ questionId: "toolu_gone#0", text: "Yes" }] }),
      ),
      409,
    );
  });

  test("unknown-agent answers require text (no questionnaire protocol to speak)", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(
      () => answerSessionAsk(deps, { paneId: "p1", text: "" }),
      /requires text for unknown agents/,
    );
    assert.deepEqual(herdr.sent, []);
  });

  test("claude answers flatten multi-line text to one line then Enter", async () => {
    const { herdr, deps } = makeDeps("claude");
    await answerSessionAsk(deps, { paneId: "p1", text: "keep\nworking" });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "keep working" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("dismissSessionAsk escapes the questionnaire", async () => {
    const { herdr, deps } = makeDeps("pi");
    await dismissSessionAsk(deps, "p1");
    assert.deepEqual(herdr.sent, [{ method: "paneSendKeys", params: { pane_id: "p1", keys: ["escape"] } }]);
  });

  test("dismissSessionAsk requires a pane", async () => {
    const { deps } = makeDeps("pi");
    assert.equal(await statusOf(() => dismissSessionAsk(deps, "")), 400);
  });

  test("runSlashCommand sends the validated command plus Enter", async () => {
    const { herdr, deps } = makeDeps();
    const text = await runSlashCommand(deps, "p1", "/compact");
    assert.equal(text, "/compact");
    assert.deepEqual(herdr.sent, [
      { method: "paneSendInput", params: { pane_id: "p1", text: "/compact", keys: ["Enter"] } },
    ]);
  });

  test("runSlashCommand flattens newlines so they cannot submit extra input", async () => {
    const { herdr, deps } = makeDeps();
    const text = await runSlashCommand(deps, "p1", "/skill:research\ncompare APIs");
    assert.equal(text, "/skill:research compare APIs");
    assert.deepEqual(herdr.sent, [
      { method: "paneSendInput", params: { pane_id: "p1", text: "/skill:research compare APIs", keys: ["Enter"] } },
    ]);
  });

  test("runSlashCommand rejects terminal control input", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(() => runSlashCommand(deps, "p1", "/compact\u001b"), /invalid slash command/);
    assert.equal(await statusOf(() => runSlashCommand(deps, "p1", "/compact\u001b")), 400);
    assert.deepEqual(herdr.sent, []);
  });

  test("sendSessionText delivers raw text without keys", async () => {
    const { herdr, deps } = makeDeps();
    await sendSessionText(deps, "p1", "hello");
    assert.deepEqual(herdr.sent, [{ method: "paneSendText", params: { pane_id: "p1", text: "hello" } }]);
  });

  test("sendSessionText rejects text that would alter PTY submission", async () => {
    const { herdr, deps } = makeDeps();
    await assert.rejects(() => sendSessionText(deps, "p1", "line one\nline two"), /plain single-line text/);
    await assert.rejects(
      () => sendSessionText(deps, "p1", `ctrl${String.fromCharCode(0)}char`),
      /plain single-line text/,
    );
    await assert.rejects(() => sendSessionText(deps, "p1", ""), /plain single-line text/);
    assert.deepEqual(herdr.sent, []);
  });

  test("a pane missing from the live topology is a 404 on every pane-addressed operation", async () => {
    const { herdr, deps } = makeDeps();
    deps.feed.setSnapshot({
      ...makeSnapshot(null),
      panes: [],
    });
    assert.equal(await statusOf(() => runSlashCommand(deps, "ghost", "/compact")), 404);
    assert.equal(await statusOf(() => sendSessionText(deps, "ghost", "hello")), 404);
    assert.equal(await statusOf(() => dismissSessionAsk(deps, "ghost")), 404);
    assert.equal(await statusOf(() => answerSessionAsk(deps, { paneId: "ghost", text: "yes" })), 404);
    // Nothing was attempted against herdr: the pane check runs first.
    assert.deepEqual(herdr.sent, []);
  });

  test("steer skips the pane check because agent.prompt accepts non-pane targets", async () => {
    const { herdr, deps } = makeDeps();
    deps.feed.setSnapshot({
      ...makeSnapshot(null),
      panes: [],
    });
    await steerSession(deps, "some-agent-session", "keep going");
    assert.deepEqual(herdr.sent, [
      { method: "agentPrompt", params: { target: "some-agent-session", text: "keep going" } },
    ]);
  });

  test("a null snapshot leaves herdr the judge instead of 404ing every command", async () => {
    const { herdr, deps } = makeDeps();
    deps.feed.setSnapshot(null);
    await runSlashCommand(deps, "p1", "/compact");
    assert.deepEqual(herdr.sent, [
      { method: "paneSendInput", params: { pane_id: "p1", text: "/compact", keys: ["Enter"] } },
    ]);
  });

  test("a herdr failure keeps its 502 rather than becoming a bad request", async () => {
    const { herdr, deps } = makeDeps();
    herdr.failNext("agentPrompt", new Error("pane is gone"));
    await assert.rejects(() => steerSession(deps, "p1", "hi"), /pane is gone/);
  });
});
