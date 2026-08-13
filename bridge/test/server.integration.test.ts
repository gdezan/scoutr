import { test, describe, before, after } from "node:test";
import { existsSync } from "node:fs";
import assert from "node:assert/strict";
import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { HerdrClient } from "../src/herdr/client.js";
import { HerdrEventFeed } from "../src/herdr/feed.js";
import { createCockpitServer, type CockpitServer } from "../src/server.js";
import { UsageService } from "../src/usage/providers.js";

// Live-herdr integration suite. Unlike the old skip-gate, this only runs when
// HERDR_SOCKET_PATH is set EXPLICITLY — an opt-in, not a silent default-path
// probe — so its absence is visible. Offline coverage of the same routes
// lives in server.test.ts and runs on every machine.

const socketPath = process.env.HERDR_SOCKET_PATH;
// A set-but-nonexistent path skips too: npm test must stay green (with the
// skip notice) for a bogus HERDR_SOCKET_PATH, so the opt-in must verify the
// socket actually exists, not just that the var is nonempty.
const skip = !socketPath || !existsSync(socketPath);
if (skip) console.error("server.integration live suite skipped: set HERDR_SOCKET_PATH to an existing socket to run it");

const PORT = 8791;
const TOKEN = "test_token_for_live_run_0001";

async function getJson(path: string): Promise<{ status: number; body: unknown }> {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    headers: { authorization: `Bearer ${TOKEN}` },
  });
  return { status: response.status, body: await response.json() };
}

describe("cockpit bridge live herdr integration", { skip }, () => {
  let herdr: HerdrClient;
  let feed: HerdrEventFeed;
  let server: CockpitServer;

  before(async () => {
    herdr = new HerdrClient({ socketPath: socketPath! });
    feed = new HerdrEventFeed(socketPath!);
    await feed.start();
    const usage = new UsageService({
      authPath: join(await mkdtemp(join(tmpdir(), "cockpit-auth-")), "auth.json"),
    });
    await writeFile(
      usage["authPath"],
      JSON.stringify({ "openai-codex": { type: "oauth", access: "x", accountId: "y" } }),
    );
    server = createCockpitServer({
      herdr,
      feed,
      usage,
      config: { token: TOKEN, port: PORT },
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

});
