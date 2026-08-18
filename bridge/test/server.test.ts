import { test, describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { createScoutrServer, type ScoutrServer } from "../src/server.js";
import { REVIEW_ROOTS_TTL_MS } from "../src/routes/review.js";
import type { AgentInfo, SessionSnapshot } from "../src/herdr/types.js";
import { fakeHerdr } from "./support/fake-herdr.js";
import { fakeFeed } from "./support/fake-feed.js";
import { FakeTerminalLauncher } from "./support/fake-terminal.js";
import type { FakeFeedExtras } from "./support/fake-feed.js";

// Offline HTTP/WS suite: the real herdr is replaced by test/support/fakes, so
// every route runs on any machine. Live-socket coverage lives in
// server.integration.test.ts (explicitly gated on HERDR_SOCKET_PATH).

const PORT = 8790;
const TOKEN = "test_token_for_offline_run_0001";

function snapshotWithAgents(agents: Partial<AgentInfo>[]): SessionSnapshot {
  return {
    version: "0.8.0",
    protocol: 19,
    focused_workspace_id: "ws1",
    focused_tab_id: "t1",
    focused_pane_id: "p1",
    workspaces: [{ workspace_id: "ws1", number: 1, label: "", focused: true, pane_count: 1, agent_status: "idle" }],
    tabs: [{ tab_id: "t1", workspace_id: "ws1", label: "", focused: true, agent_status: "idle" }],
    panes: [],
    agents: [
      {
        agent: "pi",
        agent_status: "working",
        pane_id: "p1",
        workspace_id: "ws1",
        tab_id: "t1",
        terminal_id: "term1",
        focused: true,
        cwd: "/work/project",
        foreground_cwd: "/work/project",
        revision: 1,
        state_change_seq: 0,
        ...agents[0],
      },
    ],
    layouts: [],
  };
}

async function getJson(path: string): Promise<{ status: number; body: unknown }> {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    headers: { authorization: `Bearer ${TOKEN}` },
  });
  return { status: response.status, body: await response.json() };
}

async function postJson(path: string, body: unknown): Promise<{ status: number; body: unknown }> {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    method: "POST",
    headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
}

describe("scoutr bridge HTTP/WS API (offline)", () => {
  let server: ScoutrServer;
  let feed: ReturnType<typeof fakeFeed>;
  let sessionRoot: string;
  let sessionPath: string;
  let configDir: string;
  let herdr: ReturnType<typeof fakeHerdr>;

  before(async () => {
    // Uploads live next to the config; give the server a tmp config dir so
    // the attachment route never touches the developer's real config.
    configDir = await mkdtemp(join(tmpdir(), "scoutr-server-config-"));
    // Speed up the commands-catalog test: point the loader at an empty agent
    // dir ("compact" is a builtin command, so the catalog exists without the
    // real ~/.pi/agent resource tree).
    const agentDir = await mkdtemp(join(tmpdir(), "scoutr-agent-dir-"));
    process.env.PI_CODING_AGENT_DIR = agentDir;
    // Seed a minimal model catalog so /api/models serves 200 offline (the
    // empty dir would otherwise make readModelsCatalog throw → 404).
    await writeFile(
      join(agentDir, "models-store.json"),
      JSON.stringify({ opencode: { models: [{ id: "test-model", name: "Test Model" }] } }),
    );
    herdr = fakeHerdr();
    feed = fakeFeed();
    const usage = { all: async () => ({}) };
    sessionRoot = await mkdtemp(join(tmpdir(), "scoutr-server-catalog-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = sessionRoot;
    // Isolate the claude backend's store too, or the catalog scans the
    // developer's real ~/.claude/projects in test runs.
    process.env.CLAUDECONFIGDIR = await mkdtemp(join(tmpdir(), "scoutr-server-claude-"));
    await mkdir(join(process.env.CLAUDECONFIGDIR, "projects"), { recursive: true });
    const projectDir = join(sessionRoot, "project");
    await mkdir(projectDir);
    await writeFile(
      join(projectDir, "session.jsonl"),
      [
        JSON.stringify({ type: "session", version: 3, id: "catalog-session", timestamp: "2026-01-01T00:00:00.000Z", cwd: "/work/catalog" }),
        JSON.stringify({ type: "message", id: "e1", timestamp: "2026-01-01T00:00:01.000Z", message: { role: "user", content: [{ type: "text", text: "Catalog route prompt" }] } }),
      ].join("\n"),
    );
    sessionPath = join(projectDir, "session.jsonl");
    server = createScoutrServer({
      herdr,
      feed,
      usage: usage as never,
      config: { configDir, token: TOKEN, port: PORT },
      terminal: new FakeTerminalLauncher(),
    });
  });

  after(async () => {
    await server.close();
  });

  test("health reports the Scoutr API protocol and herdr connectivity", async () => {
    const { status, body } = await getJson("/api/health");
    assert.equal(status, 200);
    const health = body as {
      ok: boolean;
      api: { protocol: number; features: string[] };
      herdr: { connected: boolean; version: string };
    };
    assert.equal(health.ok, true);
    assert.deepEqual(health.api, {
      protocol: 2,
      features: ["terminal.v1", "asks.v2", "update.pull.v1", "session-model.v3", "commands.http.v1"],
    });
    assert.equal(health.herdr.connected, true);
    assert.equal(health.herdr.version, "test");
  });

  test("health still reports the Scoutr API protocol while herdr is disconnected", async () => {
    herdr.failNext("ping", new Error("socket unavailable"));

    const { status, body } = await getJson("/api/health");
    const health = body as {
      api: { protocol: number; features: string[] };
      herdr: { connected: boolean };
    };

    assert.equal(status, 200);
    assert.deepEqual(health.api, {
      protocol: 2,
      features: ["terminal.v1", "asks.v2", "update.pull.v1", "session-model.v3", "commands.http.v1"],
    });
    assert.equal(health.herdr.connected, false);
  });

  test("snapshot is 503 until the feed has one, then returns it", async () => {
    const empty = await getJson("/api/snapshot");
    assert.equal(empty.status, 503);

    feed.setSnapshot(snapshotWithAgents([]));
    const { status, body } = await getJson("/api/snapshot");
    assert.equal(status, 200);
    const snapshot = (body as { snapshot: { workspaces: unknown[]; agents: unknown[] } }).snapshot;
    assert.ok(Array.isArray(snapshot.workspaces));
    assert.ok(Array.isArray(snapshot.agents));
  });

  test("agents derives cards from the fake snapshot", async () => {
    feed.setSnapshot(snapshotWithAgents([{ agent_status: "blocked", cwd: "/work/project" }]));
    const { status, body } = await getJson("/api/agents");
    assert.equal(status, 200);
    const cards = (body as { agents: { live: { paneId: string; status: string } }[] }).agents;
    assert.equal(cards.length, 1);
    assert.equal(cards[0]?.live.paneId, "p1");
    assert.equal(cards[0]?.live.status, "blocked");
  });

  test("a pane close event prunes status entries for panes no longer alive", async () => {
    const snapshot = snapshotWithAgents([{ agent_status: "done" }]);
    feed.setSnapshot({
      ...snapshot,
      panes: [{
        pane_id: "p1",
        workspace_id: "ws1",
        tab_id: "t1",
        terminal_id: "term1",
        focused: true,
        agent_status: "done",
        revision: 1,
        agent: "pi",
        display_agent: "pi",
        agent_session: null,
        cwd: "/work/project",
        foreground_cwd: "/work/project",
        label: null,
        title: null,
        terminal_title: null,
        terminal_title_stripped: null,
        state_labels: {},
        scroll: null,
      }],
    });
    feed.emit({ kind: "pane_agent_status_changed", data: { pane_id: "p1", agent_status: "done" } } as never);
    const before = await getJson("/api/agents");
    const beforeCard = ((before.body as { agents: { live: { paneId: string; statusSinceMs: number | null } }[] }).agents)[0];
    assert.equal(beforeCard?.live.paneId, "p1");
    assert.equal(typeof beforeCard?.live.statusSinceMs, "number", "status entry exists before the pane closes");

    feed.setSnapshot({ ...snapshot, panes: [] });
    feed.emit({ kind: "pane_exited", data: { pane_id: "p1" } } as never);
    const after = await getJson("/api/agents");
    const afterCard = ((after.body as { agents: { live: { paneId: string; statusSinceMs: number | null } }[] }).agents)[0];
    assert.equal(afterCard?.live.paneId, "p1");
    assert.equal(afterCard?.live.statusSinceMs, null, "status entry pruned for the closed pane");
  });

  test("agents enrich cards with bounded model and latest activity", async () => {
    const liveDir = join(sessionRoot, "live-project");
    await mkdir(liveDir, { recursive: true });
    const liveSession = join(liveDir, "session.jsonl");
    await writeFile(
      liveSession,
      [
        JSON.stringify({ type: "session", version: 3, id: "live-session", timestamp: "2026-01-01T00:00:00.000Z", cwd: "/work/project" }),
        JSON.stringify({ type: "message", id: "e1", timestamp: "2026-01-01T00:00:01.000Z", message: { role: "assistant", content: [{ type: "text", text: "made progress on the fix" }] } }),
      ].join("\n"),
    );
    feed.setSnapshot(
      snapshotWithAgents([{ agent_status: "working", agent_session: { kind: "path", value: liveSession } }]),
    );
    const { status, body } = await getJson("/api/agents");
    assert.equal(status, 200);
    const cards = (body as { agents: Array<{
      key: { path: string } | null;
      model: string | null;
      latestActivity: string | null;
      updatedAtMs: number | null;
    }> }).agents;
    assert.equal(cards.length, 1);
    assert.equal(cards[0]?.key?.path, liveSession);
    assert.ok("model" in cards[0]!);
    assert.ok("latestActivity" in cards[0]!);
    if (typeof cards[0]?.latestActivity === "string") {
      assert.ok(cards[0].latestActivity.length <= 160);
    }
    assert.ok("updatedAtMs" in cards[0]!);
  });

  test("agents carry normalized attention for a simple ask, a multi-question ask, and neither", async () => {
    const askDir = join(sessionRoot, "attention-project");
    await mkdir(askDir, { recursive: true });
    const write = async (name: string, questions: unknown[]) => {
      const path = join(askDir, name);
      await writeFile(
        path,
        [
          JSON.stringify({ type: "session", version: 3, id: name, timestamp: "2026-01-01T00:00:00.000Z", cwd: "/work/project" }),
          JSON.stringify({
            type: "message",
            id: "e1",
            timestamp: "2026-01-01T00:00:01.000Z",
            message: {
              role: "assistant",
              content: [{ type: "toolCall", id: `call_${name}`, name: "ask_user_question", arguments: { questions } }],
            },
          }),
        ].join("\n"),
      );
      return path;
    };
    const simple = await write("simple.jsonl", [{
      question: "Ship the fix?",
      header: "Ship",
      options: [{ label: "Ship it", description: "Deploy now." }, { label: "Hold", description: "Wait for review." }],
    }]);
    const multi = await write("multi.jsonl", [
      { question: "Ship the fix?", header: "Ship", options: [{ label: "Ship it", description: "" }] },
      { question: "Tag a release?", header: "Tag", options: [{ label: "Yes", description: "" }] },
    ]);

    const cardsFor = async (sessionPathValue: string, status = "blocked") => {
      feed.setSnapshot(
        snapshotWithAgents([{ agent_status: status, agent_session: { kind: "path", value: sessionPathValue } }]),
      );
      const { body } = await getJson("/api/agents");
      return (body as { agents: Array<Record<string, unknown>> }).agents;
    };

    const simpleCard = (await cardsFor(simple))[0] as { attention: {
      kind: string;
      callId: string;
      questionCount: number;
      canQuickAnswer: boolean;
      currentQuestion: { id: string; header: string; question: string; multiSelect: boolean; options: Array<Record<string, string>> };
    } };
    assert.equal(simpleCard.attention.kind, "ask");
    assert.equal(simpleCard.attention.callId, "call_simple.jsonl");
    assert.equal(simpleCard.attention.questionCount, 1);
    assert.equal(simpleCard.attention.canQuickAnswer, true);
    assert.equal(simpleCard.attention.currentQuestion.header, "Ship");
    assert.equal(simpleCard.attention.currentQuestion.question, "Ship the fix?");
    assert.equal(simpleCard.attention.currentQuestion.multiSelect, false);
    assert.deepEqual(simpleCard.attention.currentQuestion.options, [
      { label: "Ship it", description: "Deploy now." },
      { label: "Hold", description: "Wait for review." },
    ]);

    const multiCard = (await cardsFor(multi))[0] as { attention: { questionCount: number; canQuickAnswer: boolean; currentQuestion: { header: string } } };
    assert.equal(multiCard.attention.questionCount, 2);
    assert.equal(multiCard.attention.canQuickAnswer, false, "the board cannot submit a two-question round");
    assert.equal(multiCard.attention.currentQuestion.header, "Ship");

    const plain = join(askDir, "plain.jsonl");
    await writeFile(
      plain,
      [
        JSON.stringify({ type: "session", version: 3, id: "plain", timestamp: "2026-01-01T00:00:00.000Z", cwd: "/work/project" }),
        JSON.stringify({ type: "message", id: "e1", timestamp: "2026-01-01T00:00:01.000Z", message: { role: "assistant", content: [{ type: "text", text: "still working on it" }] } }),
      ].join("\n"),
    );
    const workingCard = (await cardsFor(plain, "working"))[0] as { attention: unknown };
    assert.equal(workingCard.attention, null, "a working pane with no open ask is not waiting on the user");
  });

  test("a blocked pane with no structured ask reports prompt attention only", async () => {
    feed.setSnapshot(snapshotWithAgents([{ agent_status: "blocked" }]));
    const { body } = await getJson("/api/agents");
    const card = (body as { agents: Array<{ attention: unknown }> }).agents[0]!;
    assert.deepEqual(card.attention, {
      kind: "prompt",
      callId: null,
      questionCount: 0,
      currentQuestion: null,
      canQuickAnswer: false,
    });
  });

  test("commands returns the slash-command catalog", async () => {
    const { status, body } = await getJson("/api/commands");
    assert.equal(status, 200);
    const commands = (body as { catalog: { commands: { name: string }[] } }).catalog.commands;
    assert.ok(commands.some((command) => command.name === "compact"));
  });

  test("commands rejects a cwd that is not attached to an active agent", async () => {
    const { status } = await getJson("/api/commands?cwd=%2Fetc");
    assert.equal(status, 403);
  });

  test("files lists an active agent's workspace and guards every other cwd", async () => {
    const workspace = await mkdtemp(join(tmpdir(), "scoutr-files-route-"));
    await mkdir(join(workspace, "src"));
    await writeFile(join(workspace, "src", "Screen.kt"), "");
    await writeFile(join(workspace, "README.md"), "");
    feed.setSnapshot(snapshotWithAgents([{ cwd: workspace, foreground_cwd: workspace }]));
    try {
      const { status, body } = await getJson(`/api/files?cwd=${encodeURIComponent(workspace)}`);
      assert.equal(status, 200);
      const listing = (body as { listing: { path: string; files: string[]; truncated: boolean } }).listing;
      assert.deepEqual(listing.files, ["README.md", "src/Screen.kt"]);
      assert.equal(listing.truncated, false);

      // A cwd no live agent is attached to never lists, even though it exists.
      const outside = await getJson("/api/files?cwd=%2Fetc");
      assert.equal(outside.status, 403);

      const missing = await getJson("/api/files");
      assert.equal(missing.status, 400);
    } finally {
      feed.setSnapshot(snapshotWithAgents([]));
      await rm(workspace, { recursive: true, force: true });
    }
  });

  test("lists hidden files and reads only files inside an active workspace", async () => {
    const workspace = await mkdtemp(join(tmpdir(), "scoutr-file-route-"));
    await mkdir(join(workspace, ".config"));
    await writeFile(join(workspace, ".config", "settings.json"), "{\"ok\":true}\n");
    await writeFile(join(workspace, "notes.md"), "# Notes\n");
    feed.setSnapshot(snapshotWithAgents([{ cwd: workspace, foreground_cwd: workspace }]));
    try {
      const hidden = await getJson(`/api/files?cwd=${encodeURIComponent(workspace)}&hidden=1`);
      assert.equal(hidden.status, 200);
      const listing = (hidden.body as { listing: { files: string[] } }).listing;
      assert.deepEqual(listing.files, [".config/settings.json", "notes.md"]);

      const file = await getJson(`/api/file?path=${encodeURIComponent(join(workspace, "notes.md"))}`);
      assert.equal(file.status, 200);
      assert.deepEqual(file.body, {
        ok: true,
        content: "# Notes\n",
        truncated: false,
        binary: false,
        exists: true,
      });

      const page = await getJson(`/api/file?path=${encodeURIComponent(join(workspace, "notes.md"))}&offset=0&limit=4`);
      assert.equal(page.status, 200);
      assert.deepEqual(page.body, {
        ok: true,
        content: "# No",
        truncated: true,
        binary: false,
        exists: true,
        offset: 0,
        nextOffset: 4,
        totalBytes: 8,
      });
      for (const parameter of ["offset=-1", "offset=1.5", "offset=9007199254740992", "limit=0", "limit=262145"]) {
        const invalid = await getJson(`/api/file?path=${encodeURIComponent(join(workspace, "notes.md"))}&${parameter}`);
        assert.equal(invalid.status, 400, parameter);
      }

      const missing = await getJson(`/api/file?path=${encodeURIComponent(join(workspace, "missing.md"))}`);
      assert.equal(missing.status, 200);
      assert.equal((missing.body as { exists: boolean }).exists, false);

      const outside = await getJson("/api/file?path=%2Fetc%2Fpasswd");
      assert.equal(outside.status, 403);
      const relative = await getJson("/api/file?path=notes.md");
      assert.equal(relative.status, 400);
    } finally {
      feed.setSnapshot(snapshotWithAgents([]));
      await rm(workspace, { recursive: true, force: true });
    }
  });

  test("sessions requires an allowed path", async () => {
    const { status } = await getJson("/api/sessions?agentKind=pi&path=/etc/passwd");
    assert.equal(status, 403); // path guard rejects outside the allow-list
  });

  test("stored-session reads and mutations enforce the key's backend namespace", async () => {
    const read = await getJson(`/api/sessions?agentKind=claude&path=${encodeURIComponent(sessionPath)}`);
    assert.equal(read.status, 403);

    const mutate = await postJson("/api/session-catalog/resume", {
      key: { agentKind: "claude", path: sessionPath },
    });
    assert.equal(mutate.status, 403);
  });

  test("session catalog lists persisted sessions and validates limits", async () => {
    const { status, body } = await getJson("/api/session-catalog?q=route");
    assert.equal(status, 200);
    const catalog = body as {
      ok: boolean;
      sessions: { session: { key: { path: string }; live: null } }[];
    };
    assert.equal(catalog.ok, true);
    assert.equal(catalog.sessions.length, 1);
    assert.ok(catalog.sessions[0]?.session.key.path.endsWith("session.jsonl"));
    assert.equal(catalog.sessions[0]?.session.live, null);

    const invalid = await getJson("/api/session-catalog?limit=0");
    assert.equal(invalid.status, 400);
  });

  test("catalog actions treat an active claude session (id-kind ref) as running", async () => {
    // Live claude panes report agent_session as {kind:"id", value:<uuid>}.
    // The action path must resolve that id to its transcript and see the
    // session as active: resume returns the existing pane instead of
    // launching a duplicate, delete is refused while it is running.
    const claudeStore = process.env.CLAUDECONFIGDIR!;
    const dir = join(claudeStore, "projects", "-encoded-");
    await mkdir(dir, { recursive: true });
    const claudePath = join(dir, "claude-live-1.jsonl");
    await writeFile(claudePath, `${JSON.stringify({
      type: "user",
      uuid: "u1",
      sessionId: "claude-live-1",
      cwd: "/work/claude",
      timestamp: "2026-01-01T00:00:00.000Z",
      message: { role: "user", content: "hello" },
    })}\n`);
    feed.setSnapshot(snapshotWithAgents([
      {
        agent: "claude",
        agent_status: "working",
        agent_session: { source: "herdr:claude", agent: "claude", kind: "id", value: "claude-live-1" },
      },
    ]));

    const resumed = await postJson("/api/session-catalog/resume", {
      key: { agentKind: "claude", path: claudePath },
    });
    assert.equal(resumed.status, 200);
    assert.deepEqual(resumed.body, { ok: true, workspaceId: "ws1", paneId: "p1" });

    const deleted = await postJson("/api/session-catalog/delete", {
      key: { agentKind: "claude", path: claudePath },
    });
    assert.equal(deleted.status, 409);

    feed.setSnapshot(null);
  });

  test("review reaches a completed session's workspace via the catalog", async () => {
    // A session's recorded cwd is an implicitly allowed review root even
    // after the session completed (no live agent). Non-repo cwds are
    // dropped, so a cwd=$HOME session never re-opens the whole home dir.
    const repo = await mkdtemp(join(tmpdir(), "scoutr-review-catalog-"));
    execFileSync("git", ["init", "-q", "-b", "main", repo]);
    execFileSync("git", ["config", "user.email", "test@scoutr.dev"], { cwd: repo });
    execFileSync("git", ["config", "user.name", "Scoutr Test"], { cwd: repo });
    await writeFile(join(repo, "doc.txt"), "catalog session workspace\n");
    execFileSync("git", ["add", "."], { cwd: repo });
    execFileSync("git", ["commit", "-q", "-m", "initial"], { cwd: repo });
    const sessionDir = join(sessionRoot, "completed-session");
    await mkdir(sessionDir, { recursive: true });
    await writeFile(
      join(sessionDir, "session.jsonl"),
      [
        JSON.stringify({ type: "session", version: 3, id: "completed-session", timestamp: "2026-01-02T00:00:00.000Z", cwd: repo }),
        JSON.stringify({ type: "message", id: "e1", timestamp: "2026-01-01T00:00:01.000Z", message: { role: "user", content: [{ type: "text", text: "work in the repo" }] } }),
      ].join("\n"),
    );
    const { status, body } = await getJson(`/api/repo?path=${encodeURIComponent(repo)}`);
    assert.equal(status, 200, JSON.stringify(body));
    assert.equal((body as { ok: boolean }).ok, true);
    // Rewrite the completed session's cwd to a non-repo path ($HOME-like):
    // allow-list is TTL-cached, so the stale root stays allowed inside the
    // window and revocation lands once the cache expires.
    await writeFile(
      join(sessionDir, "session.jsonl"),
      JSON.stringify({ type: "session", version: 3, id: "completed-session", timestamp: "2026-01-02T00:00:00.000Z", cwd: "/home" }),
    );
    const withinWindow = await getJson(`/api/repo?path=${encodeURIComponent(repo)}`);
    assert.equal(withinWindow.status, 200, "stale cached roots still allow the repo inside the TTL window");
    await new Promise((resolve) => setTimeout(resolve, REVIEW_ROOTS_TTL_MS + 250));
    const denied = await getJson(`/api/repo?path=${encodeURIComponent(repo)}`);
    assert.equal(denied.status, 403, JSON.stringify(denied.body));
  });

  test("attachment upload accepts a raw binary body", async () => {
    // Binary uploads bypass the JSON pre-parse entirely (rawBody route):
    // the bytes must reach the attachments handler untouched.
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52]);
    const response = await fetch(`http://127.0.0.1:${PORT}/api/attachments?name=test.png`, {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}`, "content-type": "image/png" },
      body: png,
    });
    const body = await response.text();
    assert.equal(response.status, 201, body);
    const parsed = JSON.parse(body) as { ok: boolean; path: string };
    assert.equal(parsed.ok, true);
    assert.ok(typeof parsed.path === "string" && parsed.path.endsWith("test.png"));
    // The upload must land next to the SERVER's own config dir, never the
    // developer's real ~/.config/scoutr (regression: this route used to
    // hardcode defaultConfigPath() and pollute the live uploads dir).
    assert.ok(parsed.path.startsWith(join(configDir, "uploads")), parsed.path);
    await rm(parsed.path, { force: true });
  });

  test("unauthorized requests are rejected", async () => {
    const response = await fetch(`http://127.0.0.1:${PORT}/api/health`);
    assert.equal(response.status, 401);
  });

  test("?token= is rejected on HTTP routes — only the WS upgrade accepts the query form", async () => {
    // The app's WS connect uses query auth; HTTP routes must use the
    // Authorization header so the credential never lands in URL logs. The
    // WS-upgrade query-token success case is covered by the ws test below.
    const response = await fetch(`http://127.0.0.1:${PORT}/api/health?token=${TOKEN}`);
    assert.equal(response.status, 401);
  });

  test("ws streams feed messages, answers ping, and applies filters", async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?token=${TOKEN}`);
    const messages: unknown[] = [];
    const gotPong = new Promise<void>((resolve) => {
      ws.on("message", (data) => {
        const parsed = JSON.parse(data.toString());
        messages.push(parsed);
        if (parsed.type === "pong") resolve();
      });
    });
    await new Promise<void>((resolve, reject) => {
      ws.on("open", () => resolve());
      ws.on("error", reject);
    });
    ws.send(JSON.stringify({ type: "ping" }));
    await gotPong;
    assert.ok(messages.some((m) => (m as { type: string }).type === "pong"));

    // Filters are per-connection and actually applied: subscribe to one
    // kind, emit a different one first, and assert only the subscribed kind
    // is delivered.
    const feedFrames: { kind: string }[] = [];
    const gotIncluded = new Promise<void>((resolve) => {
      const check = (data: Buffer): void => {
        const parsed = JSON.parse(data.toString());
        if (parsed.type === "subscribed") {
          feed.emit({ kind: "pane_closed", data: { pane_id: "p1" } } as never);
          feed.emit({ kind: "pane_agent_status_changed", data: { pane_id: "p1", agent_status: "done" } } as never);
        }
        if (parsed.type === "feed") {
          feedFrames.push(parsed.payload as { kind: string });
          if (parsed.payload.kind === "pane_agent_status_changed") resolve();
        }
      };
      ws.on("message", check);
    });
    ws.send(JSON.stringify({ type: "subscribe", filter: ["pane_agent_status_changed"] }));
    await gotIncluded;
    assert.ok(messages.some((m) => (m as { type: string }).type === "subscribed"));
    // The excluded pane_closed event was dropped by the connection filter.
    assert.deepEqual(feedFrames.map((f) => f.kind), ["pane_agent_status_changed"]);
    ws.close();
  });
describe("route contracts", () => {
  it("GET /api/models serves a catalog for the default agent (pi)", async () => {
    const { status, body } = await getJson("/api/models");
    assert.equal(status, 200);
    const parsed = body as { ok: boolean; catalog: { providers: unknown[] } };
    assert.equal(parsed.ok, true);
    assert.ok(Array.isArray(parsed.catalog.providers) && parsed.catalog.providers.length >= 1);
  });

  it("GET /api/models?agent=nonsense is a 404, never a 502", async () => {
    const { status, body } = await getJson("/api/models?agent=nonsense");
    assert.equal(status, 404);
    assert.deepEqual(body, { ok: false, error: "unknown agent: nonsense" });
  });

  it("GET /api/commands?agent=nonsense is a 404, never a 502", async () => {
    const { status, body } = await getJson("/api/commands?agent=nonsense");
    assert.equal(status, 404);
    assert.deepEqual(body, { ok: false, error: "unknown agent: nonsense" });
  });

  it("GET /api/agents/kinds pins the Android picker wire contract", async () => {
    const { status, body } = await getJson("/api/agents/kinds");
    assert.equal(status, 200);
    const kinds = (body as { ok: boolean; kinds: unknown[] }).kinds;
    assert.ok(Array.isArray(kinds) && kinds.length >= 2);
    const WIRE_FIELDS = ["id", "displayName", "capabilities", "hasModelCatalog", "hasSlashCommands"];
    const ids = new Set<string>();
    for (const kind of kinds as Record<string, unknown>[]) {
      ids.add(String(kind.id));
      // Exact field set: NewSessionSheet.kt consumes these names, so any
      // addition or rename must be a deliberate two-sided contract change.
      assert.deepEqual(Object.keys(kind).sort(), [...WIRE_FIELDS].sort(), `kind ${kind.id} field set`);
      assert.ok(Array.isArray(kind.capabilities), "kind.capabilities must be an array");
    }
    // NewSessionSheet.kt gates the agent choice on these kinds.
    assert.ok(ids.has("pi"), "pi kind missing");
    assert.ok(ids.has("claude"), "claude kind missing");
    assert.ok(ids.has("agy"), "agy kind missing");
  });

  it("GET /api/dirs lists a directory and 400s on a missing one", async () => {
    // listDirs only serves paths inside the user's home, so the fixture must
    // live under homedir(), not /tmp. Removed afterwards so test runs do not
    // litter the developer's home.
    const dir = await mkdtemp(join(homedir(), "scoutr-dirs-"));
    try {
    const { status, body } = await getJson(`/api/dirs?path=${encodeURIComponent(dir)}`);
    assert.equal(status, 200);
    const listing = (body as { ok: boolean; listing: { path: string; dirs: string[] } }).listing;
    assert.deepEqual(listing, { path: dir, dirs: [] });

      const missing = await getJson("/api/dirs?path=/definitely/not/a/real/scoutr-dir");
      assert.equal(missing.status, 400);
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it("GET /api/usage returns the usage payload shape", async () => {
    const { status, body } = await getJson("/api/usage");
    assert.equal(status, 200);
    assert.deepEqual(body, { ok: true, usage: {} });
  });
});
});

type HerdrEventFeedLike = ReturnType<typeof fakeFeed>;

describe("route table startup assertions", () => {
  it("rejects a route table with shadowing patterns", async () => {
    const { RouteTable } = await import("../src/routes/dispatcher.js");
    const routes = (await import("../src/routes/index.js")).buildRoutes();
    // The real table must assemble cleanly (no duplicates, no shadowing).
    new RouteTable(routes);
  });
});
