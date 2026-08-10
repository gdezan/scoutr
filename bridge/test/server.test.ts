import { test, describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { mkdtemp, writeFile } from "node:fs/promises";
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
    server = createCockpitServer({ herdr, feed, usage, config: { token: TOKEN, port: PORT } });
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
