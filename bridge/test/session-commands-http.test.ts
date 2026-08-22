import { homedir } from "node:os";
import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { createScoutrServer, type ScoutrServer } from "../src/server.js";
import { SCOUTR_API_FEATURES } from "../src/api-protocol.js";
import type { PaneInfo } from "../src/herdr/types.js";
import { fakeHerdr } from "./support/fake-herdr.js";
import { fakeFeed } from "./support/fake-feed.js";
import { FakeTerminalLauncher } from "./support/fake-terminal.js";

/**
 * The `commands.http.v1` route surface end to end: auth, path/body parsing,
 * the status taxonomy, and the herdr side effect through a fake herdr. The
 * limits themselves are specified in session-commands.test.ts; what is proved
 * here is that a route reaches them and reports their status.
 */

const PORT = 8795;
const TOKEN = "test_token_for_commands_0001";
const cwd = homedir();

const PANE: PaneInfo = {
  pane_id: "p1",
  workspace_id: "ws1",
  tab_id: "t1",
  terminal_id: "term1",
  focused: false,
  agent_status: "idle",
  revision: 0,
  agent: "pi",
  display_agent: "pi",
  agent_session: null,
  cwd,
  foreground_cwd: cwd,
  label: null,
  title: null,
  terminal_title: null,
  terminal_title_stripped: null,
  state_labels: {},
  scroll: null,
};

let server: ScoutrServer;
let herdr: ReturnType<typeof fakeHerdr>;

before(() => {
  herdr = fakeHerdr({ panes: [PANE] });
  server = createScoutrServer({
    herdr,
    feed: fakeFeed({
      version: "0.8.0",
      protocol: 19,
      focused_workspace_id: null,
      focused_tab_id: null,
      focused_pane_id: null,
      workspaces: [],
      tabs: [],
      panes: [PANE],
      agents: [],
      layouts: [],
    }) as never,
    usage: { all: async () => ({}) } as never,
    config: { configDir: "/tmp/scoutr-test-config", hostId: "host_test", token: TOKEN, port: PORT },
    terminal: new FakeTerminalLauncher(),
  });
});

after(async () => {
  await server.close();
});

async function post(path: string, body: unknown, token = TOKEN): Promise<{ status: number; data: any }> {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
  return { status: response.status, data: await response.json() };
}

describe("HTTP session command routes", () => {
  it("advertises commands.http.v1 so the app never guesses", async () => {
    assert.ok(SCOUTR_API_FEATURES.includes("commands.http.v1"));
    const response = await fetch(`http://127.0.0.1:${PORT}/api/health`, {
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    const health = (await response.json()) as { api: { features: string[] } };
    assert.ok(health.api.features.includes("commands.http.v1"));
  });

  it("steers a pane through agent.prompt", async () => {
    herdr.sent.length = 0;
    const { status, data } = await post("/api/sessions/p1/steer", { text: "keep going" });
    assert.equal(status, 200);
    assert.equal(data.ok, true);
    assert.deepEqual(herdr.sent, [{ method: "agentPrompt", params: { target: "p1", text: "keep going" } }]);
  });

  it("percent-encoded pane ids reach herdr decoded", async () => {
    herdr.sent.length = 0;
    const { status } = await post("/api/sessions/ws1%3Ap1/steer", { text: "hi" });
    assert.equal(status, 200);
    assert.deepEqual(herdr.sent, [{ method: "agentPrompt", params: { target: "ws1:p1", text: "hi" } }]);
  });

  it("runs a slash command and echoes what was sent", async () => {
    herdr.sent.length = 0;
    const { status, data } = await post("/api/sessions/p1/slash-command", { text: "/compact" });
    assert.equal(status, 200);
    assert.equal(data.text, "/compact");
    assert.deepEqual(herdr.sent, [
      { method: "paneSendInput", params: { pane_id: "p1", text: "/compact", keys: ["Enter"] } },
    ]);
  });

  it("sends raw single-line text", async () => {
    herdr.sent.length = 0;
    const { status } = await post("/api/sessions/p1/send-text", { text: "hello" });
    assert.equal(status, 200);
    assert.deepEqual(herdr.sent, [{ method: "paneSendText", params: { pane_id: "p1", text: "hello" } }]);
  });

  it("answers a plain blocked prompt on the ask-less route", async () => {
    herdr.sent.length = 0;
    const { status, data } = await post("/api/sessions/p1/asks/answer", { text: "yes" });
    assert.equal(status, 200);
    assert.equal(data.callId, "");
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "yes" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  it("dismisses the ask on screen", async () => {
    herdr.sent.length = 0;
    const { status } = await post("/api/sessions/p1/asks/dismiss", {});
    assert.equal(status, 200);
    assert.deepEqual(herdr.sent, [{ method: "paneSendKeys", params: { pane_id: "p1", keys: ["escape"] } }]);
  });

  it("answering an ask that is no longer open is a 409, not a 400", async () => {
    herdr.sent.length = 0;
    const { status, data } = await post("/api/sessions/p1/asks/toolu_gone/answer", {
      answers: [{ questionId: "toolu_gone#0", selectedLabels: ["Yes"] }],
    });
    assert.equal(status, 409);
    assert.match(data.error, /no open ask toolu_gone/);
    assert.deepEqual(herdr.sent, []);
  });

  it("invalid input is an actionable 400 from the shared operation", async () => {
    herdr.sent.length = 0;
    const slash = await post("/api/sessions/p1/slash-command", { text: "not a command" });
    assert.equal(slash.status, 400);
    assert.match(slash.data.error, /invalid slash command/);

    const missingText = await post("/api/sessions/p1/steer", {});
    assert.equal(missingText.status, 400);
    assert.match(missingText.data.error, /text must be a string/);

    const multiline = await post("/api/sessions/p1/send-text", { text: "one\ntwo" });
    assert.equal(multiline.status, 400);
    assert.match(multiline.data.error, /plain single-line text/);

    const badAnswers = await post("/api/sessions/p1/asks/toolu_1/answer", { answers: "nope" });
    assert.equal(badAnswers.status, 400);
    assert.match(badAnswers.data.error, /answers must be an array/);

    const badJson = await post("/api/sessions/p1/steer", "{not json");
    assert.equal(badJson.status, 400);
    assert.match(badJson.data.error, /valid JSON/);

    assert.deepEqual(herdr.sent, []);
  });

  it("a backend failure keeps its own status instead of becoming a 400", async () => {
    herdr.failNext("agentPrompt", new Error("pane is gone"));
    const { status, data } = await post("/api/sessions/p1/steer", { text: "hi" });
    assert.equal(status, 502);
    assert.match(data.error, /pane is gone/);
  });

  it("a pane id absent from the live topology is a 404 on every pane-addressed route", async () => {
    herdr.sent.length = 0;
    for (const [path, body] of [
      ["/api/sessions/ghost/slash-command", { text: "/compact" }],
      ["/api/sessions/ghost/send-text", { text: "hello" }],
      ["/api/sessions/ghost/asks/answer", { text: "yes" }],
      ["/api/sessions/ghost/asks/dismiss", {}],
    ] as const) {
      const { status, data } = await post(path, body);
      assert.equal(status, 404, `${path} must report a missing pane as 404`);
      assert.match(data.error, /no live pane ghost/);
    }
    // Steer is deliberately exempt: its target goes to agent.prompt, which
    // accepts targets that are not snapshot pane ids.
    const steer = await post("/api/sessions/ghost/steer", { text: "hi" });
    assert.equal(steer.status, 200);
    assert.deepEqual(herdr.sent, [{ method: "agentPrompt", params: { target: "ghost", text: "hi" } }]);
  });

  it("rejects an unauthenticated command on every route", async () => {
    herdr.sent.length = 0;
    for (const path of [
      "/api/sessions/p1/steer",
      "/api/sessions/p1/slash-command",
      "/api/sessions/p1/send-text",
      "/api/sessions/p1/asks/answer",
      "/api/sessions/p1/asks/toolu_1/answer",
      "/api/sessions/p1/asks/dismiss",
    ]) {
      const { status } = await post(path, { text: "/compact" }, "wrong-token");
      assert.equal(status, 401, `${path} must require the pairing token`);
    }
    assert.deepEqual(herdr.sent, []);
  });
});
