import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { HerdrClient, defaultSocketPath, HerdrError } from "../src/herdr/client.js";
import { HerdrEventFeed } from "../src/herdr/feed.js";
import type { SessionSnapshot } from "../src/herdr/types.js";

/**
 * Integration tests against the live herdr socket (herdr 0.8.0, protocol 19).
 * Skipped with a clear message when no herdr server is reachable.
 */

function liveSocketPath(): string | null {
  const path = process.env.HERDR_SOCKET_PATH ?? defaultSocketPath();
  return existsSync(path) ? path : null;
}

const socketPath = liveSocketPath();
const client = socketPath ? new HerdrClient({ socketPath }) : null;
const skip = socketPath === null;

test("a per-call timeout closes a stalled Herdr socket", { timeout: 1_000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), "cockpit-herdr-timeout-"));
  const path = join(directory, "herdr.sock");
  let markDisconnected: () => void = () => undefined;
  const disconnected = new Promise<void>((resolve) => { markDisconnected = resolve; });
  const server = createServer((socket) => {
    socket.on("close", markDisconnected);
    socket.resume();
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(path, resolve);
  });

  try {
    const stalled = new HerdrClient({ socketPath: path });
    await assert.rejects(stalled.request("stall.forever", {}, 30), /timed out after 30ms/);
    await disconnected;
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  }
});

describe("herdr client (live socket)", { skip }, () => {
  test("ping returns server version and protocol", async () => {
    const pong = await client!.ping();
    assert.equal(pong.type, "pong");
    assert.equal(typeof pong.version, "string");
    assert.equal(typeof pong.protocol, "number");
    assert.ok(pong.protocol >= 17, `protocol ${pong.protocol} should be >= 17`);
  });

  test("session.snapshot returns the full herd shape", async () => {
    const snapshot: SessionSnapshot = await client!.snapshot();
    assert.equal(typeof snapshot.version, "string");
    assert.equal(typeof snapshot.protocol, "number");
    assert.ok(Array.isArray(snapshot.workspaces));
    assert.ok(Array.isArray(snapshot.panes));
    assert.ok(Array.isArray(snapshot.agents));
    assert.ok(Array.isArray(snapshot.tabs));

    for (const pane of snapshot.panes) {
      assert.equal(typeof pane.pane_id, "string");
      assert.ok(
        ["idle", "working", "blocked", "done", "unknown"].includes(pane.agent_status),
        `unexpected agent_status ${pane.agent_status}`,
      );
      assert.equal(typeof pane.workspace_id, "string");
    }
    for (const agent of snapshot.agents) {
      assert.equal(typeof agent.agent, "string");
      assert.equal(typeof agent.pane_id, "string");
      assert.equal(typeof agent.agent_status, "string");
      if (agent.agent_session) {
        assert.equal(typeof agent.agent_session.value, "string");
        assert.ok(["id", "path"].includes(agent.agent_session.kind));
      }
    }
  });

  test("unknown method produces a HerdrError with code", async () => {
    await assert.rejects(
      client!.request("definitely.not_a_method"),
      (error: unknown) => {
        assert.ok(error instanceof HerdrError);
        assert.equal(typeof error.code, "string");
        return true;
      },
    );
  });

  test("request timeout is honored on an unreachable socket", async () => {
    const bad = new HerdrClient({ socketPath: "/tmp/definitely-no-herdr.sock", requestTimeoutMs: 300 });
    await assert.rejects(bad.ping(), /socket error|timed out/i);
  });
});

describe("herdr event feed (live socket)", { skip }, () => {
  const feeds: HerdrEventFeed[] = [];

  after(async () => {
    await Promise.all(feeds.map((feed) => feed.stop()));
  });

  test("feed emits an initial snapshot then accepts new events", async () => {
    const received: unknown[] = [];
    const feed = new HerdrEventFeed(socketPath!, (message) => {
      received.push(message);
    });
    feeds.push(feed);
    await feed.start();

    // The initial snapshot is emitted synchronously by start().
    const snap = received.find((message) => (message as { type?: string }).type === "snapshot");
    assert.ok(snap, "expected an initial snapshot message");
    assert.ok(received.length >= 1);
    assert.ok(feed.snapshot, "feed holds the latest snapshot");
  });

  test("feed.stop closes cleanly and stops emitting", async () => {
    const received: unknown[] = [];
    const feed = new HerdrEventFeed(socketPath!, (message) => {
      received.push(message);
    });
    feeds.push(feed);
    await feed.start();
    await feed.stop();
    const before = received.length;
    await new Promise((resolve) => setTimeout(resolve, 250));
    assert.equal(received.length, before, "no events after stop");
  });
});
