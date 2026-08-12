import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { HerdrClient, defaultSocketPath, HerdrError, herdrRequest, herdrSubscribe } from "../src/herdr/client.js";
import { HerdrEventFeed } from "../src/herdr/feed.js";
import type { SessionSnapshot } from "../src/herdr/types.js";

/**
 * Integration tests against the live herdr socket (herdr 0.8.0, protocol 19).
 * Skipped with a clear message when no herdr server is reachable.
 *
 * Every live-socket suite carries LIVE_SOCKET_TIMEOUT_MS. A real herdr under
 * load can leave `snapshot()`/`subscribe()` pending indefinitely, and without a
 * bound that stalls the whole run rather than failing one suite. `npm test`
 * also sets --test-timeout as a backstop.
 */
const LIVE_SOCKET_TIMEOUT_MS = 20_000;

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

// --- Offline resilience tests: the failure modes this suite cannot produce
// against a live herdr (it refuses to die or be absent).

test("herdrSubscribe rejects when the socket path does not exist", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cockpit-herdr-missing-"));
  try {
    await assert.rejects(
      herdrSubscribe(join(directory, "missing.sock"), [], {}),
      /herdr socket error/,
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("herdrSubscribe rejects when the server closes before the ack", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cockpit-herdr-noack-"));
  const path = join(directory, "herdr.sock");
  const server = createServer((socket) => {
    socket.resume();
    // Accept, then hang up without ever writing the ack line.
    socket.on("data", () => socket.end());
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(path, resolve);
  });
  try {
    await assert.rejects(
      herdrSubscribe(path, [], {}),
      /closed the connection before the subscription ack/,
    );
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  }
});

test("herdrSubscribe rejects after the ack timeout when the server stalls", { timeout: 8_000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), "cockpit-herdr-stall-"));
  const path = join(directory, "herdr.sock");
  const server = createServer((socket) => {
    socket.resume();
    // Accept and hold the connection open without ever writing the ack.
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(path, resolve);
  });
  try {
    const startedAt = Date.now();
    await assert.rejects(
      herdrSubscribe(path, [], {}),
      /no subscription ack from herdr/,
    );
    assert.ok(Date.now() - startedAt >= 2_500, "rejection waits for the ack timeout, not an instant close");
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  }
});

test("herdrRequest rejects when the server closes without responding", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cockpit-herdr-noreply-"));
  const path = join(directory, "herdr.sock");
  const server = createServer((socket) => {
    socket.resume();
    socket.on("data", () => socket.end());
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(path, resolve);
  });
  try {
    await assert.rejects(
      herdrRequest(path, "session.snapshot", {}),
      /closed the connection without responding/,
    );
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  }
});

test("a feed whose subscription fails emits feed_error and retries, never wedging", { timeout: 5_000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), "cockpit-herdr-feed-"));
  const path = join(directory, "herdr.sock");
  const server = createServer((socket) => {
    socket.resume();
    let buffer = "";
    socket.on("data", (chunk) => {
      buffer += chunk.toString("utf8");
      let newlineIndex: number;
      while ((newlineIndex = buffer.indexOf("\n")) !== -1) {
        const line = buffer.slice(0, newlineIndex);
        buffer = buffer.slice(newlineIndex + 1);
        let request: { id?: string; method?: string };
        try {
          request = JSON.parse(line);
        } catch {
          continue;
        }
        if (request.method === "session.snapshot") {
          socket.write(JSON.stringify({ id: request.id, result: { type: "snapshot", snapshot: { version: "0.8.0", protocol: 19, focused_workspace_id: "ws1", focused_tab_id: "t1", focused_pane_id: null, workspaces: [], tabs: [], panes: [], agents: [], layouts: [] } } }) + "\n");
        } else {
          // events.subscribe: refuse by hanging up before the ack.
          socket.end();
        }
      }
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(path, resolve);
  });
  const errors: string[] = [];
  const feed = new HerdrEventFeed(path, (message) => {
    if ("kind" in message && message.kind === "feed_error") {
      errors.push(String(message.data.message));
    }
  });
  try {
    // start() resolves: the snapshot succeeds, the refused subscription is
    // reported and retried instead of hanging start forever.
    await feed.start();
    const deadline = Date.now() + 3_000;
    while (errors.length === 0 && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.ok(errors.length > 0, "feed_error should be emitted when subscribe fails");
    await feed.stop();
    const countAfterStop = errors.length;
    await new Promise((resolve) => setTimeout(resolve, 1_500));
    assert.equal(errors.length, countAfterStop, "no feed_error after stop()");
  } finally {
    await feed.stop();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  }
});

test("herdrSubscribe rejects on a response that is not the subscription ack", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cockpit-herdr-badack-"));
  const path = join(directory, "herdr.sock");
  const server = createServer((socket) => {
    socket.resume();
    socket.on("data", () => {
      // Wrong id and wrong shape: a response for some other request must
      // not be accepted as the subscription ack.
      socket.write(JSON.stringify({ id: "some-other-request", result: { type: "subscription_started" } }) + "\n");
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(path, resolve);
  });
  try {
    await assert.rejects(
      herdrSubscribe(path, [], {}),
      /unexpected response before the subscription ack/,
    );
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  }
});

test("a feed whose subscription ack arrives after stop() closes the late handle", { timeout: 8_000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), "cockpit-herdr-lateack-"));
  const path = join(directory, "herdr.sock");
  let markSubscribeSeen: () => void = () => undefined;
  const subscribeSeen = new Promise<void>((resolve) => { markSubscribeSeen = resolve; });
  let subscribeId = "";
  let subscribeSocket: import("node:net").Socket | null = null;
  let subscribeClosed = false;
  const emptySnapshot = { version: "0.8.0", protocol: 19, focused_workspace_id: "ws1", focused_tab_id: "t1", focused_pane_id: null, workspaces: [], tabs: [], panes: [], agents: [], layouts: [] };
  const server = createServer((socket) => {
    socket.resume();
    let buffer = "";
    socket.on("data", (chunk) => {
      buffer += chunk.toString("utf8");
      let newlineIndex: number;
      while ((newlineIndex = buffer.indexOf("\n")) !== -1) {
        const line = buffer.slice(0, newlineIndex);
        buffer = buffer.slice(newlineIndex + 1);
        let request: { id?: string; method?: string };
        try {
          request = JSON.parse(line);
        } catch {
          continue;
        }
        if (request.method === "session.snapshot") {
          socket.write(JSON.stringify({ id: request.id, result: { type: "snapshot", snapshot: emptySnapshot } }) + "\n");
        } else if (request.method === "events.subscribe") {
          // Hold the ack: the test delivers it after stop().
          subscribeId = request.id ?? "";
          subscribeSocket = socket;
          socket.on("close", () => { subscribeClosed = true; });
          markSubscribeSeen();
        }
      }
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(path, resolve);
  });
  const feed = new HerdrEventFeed(path, () => undefined);
  try {
    const started = feed.start();
    await subscribeSeen;
    await feed.stop();
    // Deliver the ack only now: the resolved subscription must be closed,
    // never installed on a stopped feed.
    subscribeSocket!.write(JSON.stringify({ id: subscribeId, result: { type: "subscription_started" } }) + "\n");
    await started;
    const deadline = Date.now() + 2_000;
    while (!subscribeClosed && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.ok(subscribeClosed, "late subscription handle must be closed after stop()");
  } finally {
    await feed.stop();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  }
});

test("stop() clears delayed reconnect timers so a restarted feed is not rebuilt by stale timers", { timeout: 10_000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), "cockpit-herdr-reconnect-"));
  const path = join(directory, "herdr.sock");
  let subscribeCount = 0;
  let secondSubscribeClosed = false;
  const emptySnapshot = { version: "0.8.0", protocol: 19, focused_workspace_id: "ws1", focused_tab_id: "t1", focused_pane_id: null, workspaces: [], tabs: [], panes: [], agents: [], layouts: [] };
  const server = createServer((socket) => {
    socket.resume();
    let buffer = "";
    socket.on("data", (chunk) => {
      buffer += chunk.toString("utf8");
      let newlineIndex: number;
      while ((newlineIndex = buffer.indexOf("\n")) !== -1) {
        const line = buffer.slice(0, newlineIndex);
        buffer = buffer.slice(newlineIndex + 1);
        let request: { id?: string; method?: string };
        try {
          request = JSON.parse(line);
        } catch {
          continue;
        }
        if (request.method === "session.snapshot") {
          socket.write(JSON.stringify({ id: request.id, result: { type: "snapshot", snapshot: emptySnapshot } }) + "\n");
        } else if (request.method === "events.subscribe") {
          subscribeCount += 1;
          socket.write(JSON.stringify({ id: request.id, result: { type: "subscription_started" } }) + "\n");
          if (subscribeCount === 1) {
            // Kill the first subscription right after its ack so the feed
            // schedules its 1s reconnect timer.
            socket.end();
          } else if (subscribeCount === 2) {
            socket.on("close", () => { secondSubscribeClosed = true; });
          }
        }
      }
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(path, resolve);
  });
  const feed = new HerdrEventFeed(path, () => undefined);
  try {
    await feed.start();
    // Let the socket death reach the feed and its reconnect timer be scheduled.
    await new Promise((resolve) => setTimeout(resolve, 100));
    await feed.stop();
    await feed.start();
    // Wait past the 1s window the stale reconnect timer would have fired in.
    await new Promise((resolve) => setTimeout(resolve, 2_000));
    assert.equal(subscribeCount, 2, "no spurious rebuild after a restart");
    assert.equal(secondSubscribeClosed, false, "restarted subscription must survive the stale reconnect timer");
    await feed.stop();
  } finally {
    await feed.stop();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  }
});

describe("herdr client (live socket)", { skip, timeout: LIVE_SOCKET_TIMEOUT_MS }, () => {
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

describe("herdr event feed (live socket)", { skip, timeout: LIVE_SOCKET_TIMEOUT_MS }, () => {
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
