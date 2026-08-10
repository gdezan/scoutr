import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { createSession, controlSession, SessionsError } from "../src/sessions.js";

/** Minimal fake herdr recording every call. */
function fakeHerdr(overrides: Record<string, unknown> = {}) {
  const calls: { method: string; params: unknown }[] = [];
  const herdr = {
    calls,
    async workspaceCreate(params: unknown) {
      calls.push({ method: "workspace.create", params });
      return overrides.workspaceCreate ?? { workspace: { workspace_id: "ws1" }, root_pane: { pane_id: "p1" } };
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
      calls.push({ method: "session.snapshot", params: {} });
      return overrides.snapshot ?? {
        panes: [{ pane_id: "p1", workspace_id: "ws1" }],
        workspaces: [],
        tabs: [],
        agents: [],
      };
    },
  };
  return herdr as never;
}

describe("createSession", () => {
  it("creates a workspace (herdr pre-creates the root pane) and launches pi with the model", async () => {
    const herdr = fakeHerdr();
    const created = await createSession(herdr, { cwd: "/tmp/demo", model: "openai-codex/gpt-5.4", name: "demo" });
    assert.deepEqual(created, { workspaceId: "ws1", paneId: "p1" });
    const methods = herdr.calls.map((c) => c.method);
    assert.deepEqual(methods, ["workspace.create", "pane.send_text", "pane.send_keys"]);
    const ws = herdr.calls[0].params as { cwd: string; label: string };
    assert.deepEqual(ws, { cwd: "/tmp/demo", label: "demo", focus: false });
    const launch = herdr.calls[1].params as { text: string };
    assert.equal(launch.text, "pi --model openai-codex/gpt-5.4");
  });

  it("defaults the name to the cwd basename and launches the model exactly", async () => {
    const herdr = fakeHerdr();
    await createSession(herdr, { cwd: "/home/user/Dev/project", model: "deepseek/deepseek-v4-flash" });
    const ws = herdr.calls[0].params as { label: string };
    assert.equal(ws.label, "project");
    const launch = herdr.calls[1].params as { text: string };
    assert.equal(launch.text, "pi --model deepseek/deepseek-v4-flash");
  });

  it("rejects a missing cwd or model", async () => {
    const herdr = fakeHerdr();
    await assert.rejects(createSession(herdr, { cwd: "", model: "m" }), SessionsError);
    await assert.rejects(createSession(herdr, { cwd: "/tmp", model: "" }), SessionsError);
  });

  it("fails with 502 when herdr returns no ids", async () => {
    const herdr = fakeHerdr({ workspaceCreate: {} });
    await assert.rejects(createSession(herdr, { cwd: "/tmp/a", model: "m" }), (e: unknown) => {
      assert.ok(e instanceof SessionsError);
      assert.equal(e.status, 502);
      return true;
    });
  });
});

describe("controlSession", () => {
  it("abort sends escape", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "abort" });
    assert.deepEqual(herdr.calls, [{ method: "pane.send_keys", params: { pane_id: "p1", keys: ["escape"] } }]);
  });

  it("retry re-prompts with the last user message", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "retry", text: "fix the tests" });
    assert.deepEqual(herdr.calls, [{ method: "agent.prompt", params: { target: "p1", text: "fix the tests" } }]);
  });

  it("retry without text is rejected", async () => {
    const herdr = fakeHerdr();
    await assert.rejects(controlSession(herdr, { paneId: "p1", action: "retry" }), SessionsError);
  });

  it("compact types /compact + Enter", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "compact" });
    assert.deepEqual(herdr.calls, [
      { method: "pane.send_text", params: { pane_id: "p1", text: "/compact" } },
      { method: "pane.send_keys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  it("fork types /fork + Enter", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "fork" });
    assert.equal(herdr.calls[0].params.text, "/fork");
  });

  it("rename resolves the pane workspace and renames it", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "rename", text: "my session" });
    assert.deepEqual(herdr.calls, [
      { method: "session.snapshot", params: {} },
      { method: "workspace.rename", params: { workspace_id: "ws1", label: "my session" } },
    ]);
  });

  it("rename of an unknown pane fails with 404", async () => {
    const herdr = fakeHerdr({ snapshot: { panes: [{ pane_id: "other", workspace_id: "w2" }] } });
    await assert.rejects(controlSession(herdr, { paneId: "p1", action: "rename", text: "x" }), (e: unknown) => {
      assert.ok(e instanceof SessionsError);
      assert.equal(e.status, 404);
      return true;
    });
  });

  it("cycle_thinking sends shift+tab", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "cycle_thinking" });
    assert.deepEqual(herdr.calls, [{ method: "pane.send_keys", params: { pane_id: "p1", keys: ["shift+tab"] } }]);
  });

  it("rejects an unknown action", async () => {
    const herdr = fakeHerdr();
    await assert.rejects(controlSession(herdr, { paneId: "p1", action: "explode" as never }), SessionsError);
  });
});
