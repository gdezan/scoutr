import { test, describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { HerdrClient, defaultSocketPath } from "../src/herdr/client.js";
import { HerdrEventFeed } from "../src/herdr/feed.js";
import { createCockpitServer, type CockpitServer } from "../src/server.js";
import { LIVE_OUTPUT_MAX_BYTES } from "../src/live-output.js";
import { UsageService } from "../src/usage/providers.js";

const socketPath = process.env.HERDR_SOCKET_PATH ?? defaultSocketPath();
const skip = !existsSync(socketPath);

const PORT = 8790;
const TOKEN = "test_token_for_unit_run_0001";

async function getJson(path: string): Promise<{ status: number; body: unknown }> {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    headers: { authorization: `Bearer ${TOKEN}` },
  });
  return { status: response.status, body: await response.json() };
}

describe("cockpit bridge HTTP/WS API", { skip }, () => {
  let herdr: HerdrClient;
  let feed: HerdrEventFeed;
  let server: CockpitServer;
  let sessionRoot: string;

  before(async () => {
    herdr = new HerdrClient({ socketPath });
    feed = new HerdrEventFeed(socketPath);
    await feed.start();
    const usage = new UsageService({
      authPath: join(await mkdtemp(join(tmpdir(), "cockpit-auth-")), "auth.json"),
    });
    await writeFile(
      usage["authPath"],
      JSON.stringify({ "openai-codex": { type: "oauth", access: "x", accountId: "y" } }),
    );
    sessionRoot = await mkdtemp(join(tmpdir(), "cockpit-server-catalog-"));
    const projectDir = join(sessionRoot, "project");
    await mkdir(projectDir);
    await writeFile(
      join(projectDir, "session.jsonl"),
      [
        JSON.stringify({ type: "session", version: 3, id: "catalog-session", timestamp: "2026-01-01T00:00:00.000Z", cwd: "/work/catalog" }),
        JSON.stringify({ type: "message", id: "e1", timestamp: "2026-01-01T00:00:01.000Z", message: { role: "user", content: [{ type: "text", text: "Catalog route prompt" }] } }),
      ].join("\n"),
    );
    server = createCockpitServer({
      herdr,
      feed,
      usage,
      config: { token: TOKEN, port: PORT },
      sessionCatalogRoot: sessionRoot,
    });
  });

  after(async () => {
    await server.close();
    await feed.stop();
  });

  test("health reports herdr connectivity", async () => {
    const { status, body } = await getJson("/api/health");
    assert.equal(status, 200);
    const health = body as { ok: boolean; herdr: { connected: boolean; version: string } };
    assert.equal(health.ok, true);
    assert.equal(health.herdr.connected, true);
  });

  test("snapshot returns the live herd", async () => {
    const { status, body } = await getJson("/api/snapshot");
    assert.equal(status, 200);
    const snapshot = (body as { snapshot: { workspaces: unknown[]; agents: unknown[] } }).snapshot;
    assert.ok(Array.isArray(snapshot.workspaces));
    assert.ok(Array.isArray(snapshot.agents));
  });

  test("agents derives cards from the snapshot", async () => {
    const { status, body } = await getJson("/api/agents");
    assert.equal(status, 200);
    const cards = (body as { agents: { paneId: string; status: string }[] }).agents;
    assert.ok(Array.isArray(cards));
    for (const card of cards) {
      assert.ok(card.paneId.startsWith("w"));
      assert.ok(["working", "blocked", "idle", "done", "unknown"].includes(card.status));
    }
  });

  test("agents enrich cards with bounded model and latest activity", async () => {
    const { status, body } = await getJson("/api/agents");
    assert.equal(status, 200);
    const cards = (body as { agents: Array<{
      paneId: string;
      sessionPath?: string;
      model?: string | null;
      latestActivity?: string;
      latestActivityAtMs?: number | null;
    }> }).agents;
    for (const card of cards) {
      if (!card.sessionPath) continue;
      // Fields are always present on cards with a session path (values may be null).
      assert.ok("model" in card);
      assert.ok("latestActivity" in card);
      if (typeof card.latestActivity === "string") {
        assert.ok(card.latestActivity.length <= 160);
      }
      assert.ok("latestActivityAtMs" in card);
    }
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


  test("live output returns a bounded plain-text agent snapshot", async (context) => {
    const snapshotResponse = await getJson("/api/snapshot");
    const agents = (snapshotResponse.body as { snapshot: { agents: { pane_id: string }[] } }).snapshot.agents;
    if (agents.length === 0) {
      context.skip("no live agents");
      return;
    }
    const paneId = encodeURIComponent(agents[0]!.pane_id);
    const { status, body } = await getJson(`/api/agents/${paneId}/read?lines=20`);
    assert.equal(status, 200);
    const output = (body as { output: { text: string; lineLimit: number } }).output;
    assert.equal(output.lineLimit, 20);
    assert.ok(Buffer.byteLength(output.text) <= LIVE_OUTPUT_MAX_BYTES);
    assert.equal(output.text.includes("\u001b"), false);
  });

  test("sessions requires an allowed path", async () => {
    const { status } = await getJson("/api/sessions?path=/etc/passwd");
    assert.equal(status, 500); // path guard rejects
  });

  test("session catalog lists persisted sessions and validates limits", async () => {
    const { status, body } = await getJson("/api/session-catalog?q=route");
    assert.equal(status, 200);
    const catalog = body as { ok: boolean; sessions: { id: string; status: string }[] };
    assert.equal(catalog.ok, true);
    assert.equal(catalog.sessions.length, 1);
    assert.equal(catalog.sessions[0]?.id, "catalog-session");
    assert.equal(catalog.sessions[0]?.status, "completed");

    const invalid = await getJson("/api/session-catalog?limit=0");
    assert.equal(invalid.status, 400);
  });

  test("review reaches a completed session's workspace via the catalog", async () => {
    // Fix 13: a session's recorded cwd is an implicitly allowed review root
    // even after the session completed (no live agent). Non-repo cwds are
    // dropped, so a cwd=$HOME session never re-opens the whole home dir.
    const repo = await mkdtemp(join(tmpdir(), "cockpit-review-catalog-"));
    execFileSync("git", ["init", "-q", "-b", "main", repo]);
    execFileSync("git", ["config", "user.email", "test@cockpit.dev"], { cwd: repo });
    execFileSync("git", ["config", "user.name", "Cockpit Test"], { cwd: repo });
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
    // the repo must no longer be reachable through it.
    await writeFile(
      join(sessionDir, "session.jsonl"),
      JSON.stringify({ type: "session", version: 3, id: "completed-session", timestamp: "2026-01-02T00:00:00.000Z", cwd: "/home" }),
    );
    const denied = await getJson(`/api/repo?path=${encodeURIComponent(repo)}`);
    assert.equal(denied.status, 403, JSON.stringify(denied.body));
  });

  test("attachment upload accepts a raw binary body", async () => {
    // Fix 14: the generic POST JSON pre-parse used to 400 binary uploads
    // before the attachments route could read them.
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
  });

  test("unauthorized requests are rejected", async () => {
    const response = await fetch(`http://127.0.0.1:${PORT}/api/health`);
    assert.equal(response.status, 401);
  });

  test("ws streams feed messages and answers commands", async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?token=${TOKEN}`);
    const messages: unknown[] = [];
    const got = new Promise<void>((resolve) => {
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
    await got;
    assert.ok(messages.some((m) => (m as { type: string }).type === "pong"));
    ws.close();
  });
});
