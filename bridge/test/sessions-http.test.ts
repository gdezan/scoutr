import { homedir } from "node:os";
import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { createCockpitServer, type CockpitServer } from "../src/server.js";

const PORT = 8792;
const TOKEN = "test_token_for_sessions_0003";
const cwd = homedir();

function fakeDeps() {
  const calls: { method: string; params: unknown }[] = [];
  const herdr = {
    calls,
    async workspaceCreate(params: unknown) {
      calls.push({ method: "workspace.create", params });
      return { workspace: { workspace_id: "ws1" }, root_pane: { pane_id: "p1" } };
    },
    async workspaceClose(workspace_id: string) {
      calls.push({ method: "workspace.close", params: { workspace_id } });
      return {};
    },
    async paneSendText(pane_id: string, text: string) {
      calls.push({ method: "pane.send_text", params: { pane_id, text } });
      return {};
    },
    async paneSendKeys(pane_id: string, keys: string[]) {
      calls.push({ method: "pane.send_keys", params: { pane_id, keys } });
      return {};
    },
    async paneSendInput(pane_id: string, text: string, keys: string[]) {
      calls.push({ method: "pane.send_input", params: { pane_id, text, keys } });
      return {};
    },
    async agentPrompt(target: string, text: string) {
      calls.push({ method: "agent.prompt", params: { target, text } });
      return {};
    },
    async workspaceRename(workspace_id: string, label: string) {
      calls.push({ method: "workspace.rename", params: { workspace_id, label } });
      return {};
    },
    async snapshot() {
      calls.push({ method: "session.snapshot", params: {} });
      return { panes: [{ pane_id: "p1", workspace_id: "ws1", agent: "pi" }], workspaces: [], tabs: [], agents: [] };
    },
  };
  const feed = { onMessage: () => {}, stop: async () => {}, start: async () => {} };
  const usage = { all: async () => ({}) };
  return {
    deps: {
      herdr: herdr as never,
      feed: feed as never,
      usage: usage as never,
      config: { token: TOKEN, port: PORT },
    },
    calls,
  };
}

async function rawPost(path: string, body: string, token = TOKEN): Promise<{ status: number; data: any }> {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
    },
    body,
  });
  return { status: response.status, data: await response.json() };
}

async function post(path: string, body: unknown, token = TOKEN): Promise<{ status: number; data: any }> {
  return rawPost(path, JSON.stringify(body), token);
}

function lastLaunch(calls: { method: string; params: unknown }[]): string {
  const send = calls.filter((call) => call.method === "pane.send_input").at(-1) as { params: { text: string } };
  return send.params.text;
}

describe("POST /api/sessions and /api/sessions/:paneId/control", () => {
  let server: CockpitServer;
  let calls: { method: string; params: unknown }[];

  before(() => {
    const fake = fakeDeps();
    calls = fake.calls;
    server = createCockpitServer(fake.deps, { listen: true });
  });

  after(async () => {
    await server.close();
  });

  it("creates a session and returns the pane and workspace", async () => {
    const { status, data } = await post("/api/sessions", {
      cwd,
      model: "openai-codex/gpt-5.4",
      name: "demo",
    });

    assert.equal(status, 200);
    assert.equal(data.ok, true);
    assert.equal(data.paneId, "p1");
    assert.equal(lastLaunch(calls), "pi --model 'openai-codex/gpt-5.4' --name 'demo'");
  });

  it("delivers thinking through launch and the exact prompt through agent.prompt", async () => {
    const prompt = "--help\n\nfix the tests";
    const before = calls.length;

    const { status } = await post("/api/sessions", {
      cwd,
      model: "openai-codex/gpt-5.4",
      thinkingLevel: "high",
      initialPrompt: prompt,
    });

    assert.equal(status, 200);
    assert.equal(lastLaunch(calls), "pi --model 'openai-codex/gpt-5.4' --thinking 'high'");
    const promptCall = calls.slice(before).find((call) => call.method === "agent.prompt") as { params: { text: string } };
    assert.equal(promptCall.params.text, prompt);
  });

  it("keeps empty-prompt creation working", async () => {
    const before = calls.length;
    const { status } = await post("/api/sessions", { cwd, model: "m", initialPrompt: "" });

    assert.equal(status, 200);
    assert.equal(lastLaunch(calls), "pi --model 'm'");
    assert.equal(calls.slice(before).some((call) => call.method === "agent.prompt"), false);
  });

  it("rejects invalid creation input with 400 and no Herdr call", async () => {
    const before = calls.length;
    const invalid = [
      { cwd, model: "m", thinkingLevel: "turbo" },
      { cwd, model: "m", initialPrompt: "a\u0000b" },
      { cwd },
      {},
      { cwd: "/", model: "m" },
    ];

    for (const body of invalid) {
      const { status } = await post("/api/sessions", body);
      assert.equal(status, 400);
    }
    assert.equal(calls.length, before);
  });

  it("rejects malformed and non-object JSON with 400", async () => {
    const malformed = await rawPost("/api/sessions", "{");
    const nullBody = await rawPost("/api/sessions", "null");
    const arrayBody = await rawPost("/api/sessions", "[]");

    assert.equal(malformed.status, 400);
    assert.match(malformed.data.error, /valid JSON/);
    assert.equal(nullBody.status, 400);
    assert.equal(arrayBody.status, 400);
  });

  it("rejects unauthenticated requests", async () => {
    const { status } = await post("/api/sessions", { cwd, model: "m" }, "wrong");
    assert.equal(status, 401);
  });

  it("control: abort maps to pane.send_keys escape", async () => {
    const { status } = await post("/api/sessions/p1/control", { action: "abort" });
    assert.equal(status, 200);
    const last = calls.at(-1) as { method: string; params: { keys: string[] } };
    assert.equal(last.method, "pane.send_keys");
    assert.deepEqual(last.params.keys, ["escape"]);
  });

  it("control: rename resolves workspace and renames", async () => {
    const { status } = await post("/api/sessions/p1/control", { action: "rename", text: "newname" });
    assert.equal(status, 200);
    const last = calls.at(-1) as { method: string; params: { workspace_id: string; label: string } };
    assert.equal(last.method, "workspace.rename");
    assert.deepEqual(last.params, { workspace_id: "ws1", label: "newname" });
  });

  it("control: unknown action returns 400", async () => {
    const { status } = await post("/api/sessions/p1/control", { action: "explode" });
    assert.equal(status, 400);
  });
});
