import { homedir, tmpdir } from "node:os";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { createScoutrServer, type ScoutrServer } from "../src/server.js";
import type { PaneInfo } from "../src/herdr/types.js";
import { fakeHerdr, type SentInput } from "./support/fake-herdr.js";
import { FakeTerminalLauncher } from "./support/fake-terminal.js";
import WebSocket from "ws";

const PORT = 8792;
const TOKEN = "test_token_for_sessions_0003";
const cwd = homedir();

/** Snapshot pane so control actions can resolve p1 -> ws1 (see fake-herdr.ts). */
const SNAPSHOT_PANE: PaneInfo = {
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

function fakeDeps(sessionCatalogRoot?: string) {
  const fake = fakeHerdr({ panes: [SNAPSHOT_PANE] });
  const feed = { onMessage: () => {}, removeMessage: () => {}, stop: async () => {}, start: async () => {} };
  const usage = { all: async () => ({}) };
  return {
    deps: {
      herdr: fake,
      feed: feed as never,
      usage: usage as never,
      config: { configDir: "/tmp/scoutr-test-config", token: TOKEN, port: PORT },
      terminal: new FakeTerminalLauncher(),
      sessionCatalogRoot,
    },
    sent: fake.sent,
    fake,
  };
}

/** Second snapshot pane owned by the claude backend (capability checks). */
const CLAUDE_PANE: PaneInfo = { ...SNAPSHOT_PANE, pane_id: "p-claude", agent: "claude", display_agent: "claude" };

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

async function wsCommand(command: unknown): Promise<any> {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?token=${TOKEN}`);
  return new Promise((resolve, reject) => {
    ws.on("open", () => ws.send(JSON.stringify(command)));
    ws.on("message", (data) => {
      const frame = JSON.parse(data.toString());
      ws.close();
      resolve(frame);
    });
    ws.on("error", reject);
  });
}

function lastLaunch(sent: readonly SentInput[]): string {
  const send = sent.filter((call) => call.method === "paneSendInput").at(-1);
  return (send?.params.text as string) ?? "";
}

describe("POST /api/sessions and /api/sessions/:paneId/control", () => {
  let server: ScoutrServer;
  let sent: SentInput[];
  let snapshot: ReturnType<typeof fakeHerdr>;
  let sessionRoot: string;
  let sessionPath: string;

  before(async () => {
    sessionRoot = await mkdtemp(join(homedir(), ".scoutr-session-http-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = sessionRoot;
    // Isolate the claude backend's store so catalog scans stay hermetic.
    process.env.CLAUDECONFIGDIR = await mkdtemp(join(tmpdir(), "scoutr-http-claude-"));
    await mkdir(join(process.env.CLAUDECONFIGDIR, "projects"), { recursive: true });
    const project = join(sessionRoot, "project");
    await mkdir(project);
    sessionPath = join(project, "saved.jsonl");
    await writeFile(sessionPath, `${JSON.stringify({
      type: "session",
      version: 3,
      id: "saved",
      timestamp: "2026-01-01T00:00:00.000Z",
      cwd,
    })}\n`);
    const fake = fakeDeps(sessionRoot);
    sent = fake.sent;
    snapshot = fake.fake;
    server = createScoutrServer(fake.deps, { listen: true });
  });

  after(async () => {
    await server.close();
    await rm(sessionRoot, { recursive: true, force: true });
  });

  it("delivers one slash command as exact pane text plus Enter", async () => {
    sent.length = 0;

    const frame = await wsCommand({ type: "slash_command", paneId: "p1", text: "/skill:research compare APIs" });

    assert.equal(frame.type, "command_sent");
    assert.deepEqual(sent, [
      {
        method: "paneSendInput",
        params: { pane_id: "p1", text: "/skill:research compare APIs", keys: ["Enter"] },
      },
    ]);
  });

  it("rejects slash commands containing terminal control input", async () => {
    sent.length = 0;

    const frame = await wsCommand({ type: "slash_command", paneId: "p1", text: "/compact\n/quit" });

    assert.equal(frame.type, "error");
    assert.deepEqual(sent, []);
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
    assert.equal(lastLaunch(sent), "pi --model 'openai-codex/gpt-5.4' --name 'demo'");
  });

  it("resumes a stored session through a quoted headless launch", async () => {
    sent.length = 0;
    const { status, data } = await post("/api/session-catalog/resume", { path: sessionPath });

    assert.equal(status, 201);
    assert.equal(data.paneId, "p1");
    assert.equal(lastLaunch(sent), `pi --session '${sessionPath}'`);
  });

  it("delivers thinking through launch and the exact prompt through agent.prompt", async () => {
    const prompt = "--help\n\nfix the tests";
    const before = sent.length;

    const { status } = await post("/api/sessions", {
      cwd,
      model: "openai-codex/gpt-5.4",
      thinkingLevel: "high",
      initialPrompt: prompt,
    });

    assert.equal(status, 200);
    assert.equal(lastLaunch(sent), "pi --model 'openai-codex/gpt-5.4' --thinking 'high'");
    const promptCall = sent.slice(before).find((call) => call.method === "agentPrompt");
    assert.equal(promptCall?.params.text, prompt);
  });

  it("keeps empty-prompt creation working", async () => {
    const before = sent.length;
    const { status } = await post("/api/sessions", { cwd, model: "m", initialPrompt: "" });

    assert.equal(status, 200);
    assert.equal(lastLaunch(sent), "pi --model 'm'");
    assert.equal(sent.slice(before).some((call) => call.method === "agentPrompt"), false);
  });

  it("rejects invalid creation input with 400 and no Herdr call", async () => {
    const before = sent.length;
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
    assert.equal(sent.length, before);
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
    const last = sent.at(-1);
    assert.equal(last?.method, "paneSendKeys");
    assert.deepEqual(last?.params.keys, ["escape"]);
  });

  it("control: rename resolves workspace and renames", async () => {
    const { status } = await post("/api/sessions/p1/control", { action: "rename", text: "newname" });
    assert.equal(status, 200);
    const last = sent.at(-1);
    assert.equal(last?.method, "workspaceRename");
    assert.deepEqual(last?.params, { workspace_id: "ws1", label: "newname" });
  });

  it("control: close resolves and closes the workspace", async () => {
    const { status } = await post("/api/sessions/p1/control", { action: "close" });
    assert.equal(status, 200);
    const last = sent.at(-1);
    assert.equal(last?.method, "workspaceClose");
    assert.deepEqual(last?.params, { workspace_id: "ws1" });
  });

  it("control: unknown action returns 400", async () => {
    const { status, data } = await post("/api/sessions/p1/control", { action: "definitely-not-real" });
    assert.equal(status, 400);
    assert.match(data.error, /unknown control action/);
  });

  it("control: rejects actions outside the backend's declared capabilities", async () => {
    // claude declares abort/compact/close/set_model — fork is not among them.
    await snapshot.setSnapshot({ ...(await snapshot.snapshot()), panes: [SNAPSHOT_PANE, CLAUDE_PANE] });
    sent.length = 0;
    const { status, data } = await post("/api/sessions/p-claude/control", { action: "fork" });
    assert.equal(status, 400);
    assert.match(data.error, /claude does not support fork/);
    const controlCalls = sent.filter((call) => call.method !== "snapshot");
    assert.deepEqual(controlCalls, [], "a capability-rejected action must not reach herdr");
  });

  it("control: missing action is rejected at the route", async () => {
    const { status, data } = await post("/api/sessions/p1/control", {});
    assert.equal(status, 400);
    assert.match(data.error, /unknown control action/);
  });

  it("session read for a path outside every registered store returns 403", async () => {
    const response = await fetch(`http://127.0.0.1:${PORT}/api/sessions?path=${encodeURIComponent("/etc/passwd")}`, {
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    assert.equal(response.status, 403);
    const data = (await response.json()) as { ok: boolean; error: string };
    assert.match(data.error, /outside a registered session store/);
  });
});
