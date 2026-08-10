import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { createCockpitServer, type CockpitServer } from "../src/server.js";

const PORT = 8792;
const TOKEN = "test_token_for_sessions_0003";

function fakeDeps(herdrOverrides: Record<string, unknown> = {}) {
  const calls: { method: string; params: unknown }[] = [];
  const herdr = {
    calls,
    async workspaceCreate(params: unknown) {
      calls.push({ method: "workspace.create", params });
      return { workspace: { workspace_id: "ws1" }, root_pane: { pane_id: "p1" } };
    },
    async paneSendText(pane_id: string, text: string) {
      calls.push({ method: "pane.send_text", params: { pane_id, text } });
      return {};
    },
    async paneSendKeys(pane_id: string, keys: string[]) {
      calls.push({ method: "pane.send_keys", params: { pane_id, keys } });
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
      return { panes: [{ pane_id: "p1", workspace_id: "ws1" }], workspaces: [], tabs: [], agents: [] };
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

async function post(path: string, body: unknown, token = TOKEN): Promise<{ status: number; data: unknown }> {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
  });
  return { status: response.status, data: await response.json() };
}

describe("POST /api/sessions and /api/sessions/:paneId/control", () => {
  let server: CockpitServer;
  let calls: { method: string; params: unknown }[];

  before(() => {
    const f = fakeDeps();
    calls = f.calls;
    server = createCockpitServer(f.deps, { listen: true });
  });

  after(async () => {
    await server.close();
  });

  it("creates a session and returns the pane + workspace", async () => {
    const { status, data } = await post("/api/sessions", {
      cwd: "/tmp/demo",
      model: "openai-codex/gpt-5.4",
      name: "demo",
    });
    assert.equal(status, 200);
    assert.deepEqual((data as { ok: boolean }).ok, true);
    assert.deepEqual((data as { workspaceId: string; paneId: string }).paneId, "p1");
    const launch = calls[1] as { method: string; params: { text: string } };
    assert.equal(launch.params.text, "pi --model openai-codex/gpt-5.4");
  });

  it("rejects a missing model with 400", async () => {
    const { status, data } = await post("/api/sessions", { cwd: "/tmp" });
    assert.equal(status, 400);
    assert.match((data as { error: string }).error, /cwd and model are required/);
  });

  it("rejects an empty body with 400", async () => {
    const { status, data } = await post("/api/sessions", {});
    assert.equal(status, 400);
  });

  it("rejects unauthenticated requests", async () => {
    const { status } = await post("/api/sessions", { cwd: "/tmp", model: "m" }, "wrong");
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
