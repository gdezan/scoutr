import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createServer, type IncomingMessage } from "node:http";
import { NtfyPublisher } from "../src/notify.js";
import type { FeedEvent } from "../src/herdr/feed.js";

/** Capture the requests an ntfy server would receive. */
function captureServer() {
  const requests: Array<{ path: string; payload: { topic?: string; title?: string; message?: string } }> = [];
  const server = createServer((req: IncomingMessage, res) => {
    let body = "";
    req.on("data", (chunk) => body += chunk.toString());
    req.on("end", () => {
      let payload: { topic?: string; title?: string; message?: string } = {};
      try { payload = JSON.parse(body); } catch {}
      requests.push({ path: req.url ?? "", payload });
      res.writeHead(200, { "content-type": "application/json" });
      res.end("{}");
    });
  });
  return { server, requests };
}
function blockedEvent(paneId: string, overrides: Record<string, unknown> = {}): FeedEvent {
  return {
    kind: "pane.agent_status_changed",
    data: { pane_id: paneId, agent: "pi", agent_status: "blocked", display_agent: "π", title: "my pane", ...overrides },
  };
}

test("publishes a blocked event with the right payload", async () => {
  const { server, requests } = captureServer();
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  const port = (server.address() as { port: number }).port;

  const publisher = new NtfyPublisher({ baseUrl: `http://127.0.0.1:${port}/ntfy`, topic: "cockpit_test_topic" });
  try {
    const sent = await publisher.handleEvent(blockedEvent("w1:p1"));
    assert.equal(sent, true);
    assert.equal(requests.length, 1);
    assert.equal(requests[0]!.path, "/ntfy/");
    assert.equal(requests[0]!.payload.topic, "cockpit_test_topic");
    assert.equal(requests[0]!.payload.title, "π needs you");
    assert.equal(requests[0]!.payload.message, "my pane");
    assert.equal(requests[0]!.payload.paneId, "w1:p1");
    assert.equal(requests[0]!.payload.click, "cockpit://chat/w1:p1?status=blocked");
    assert.equal(requests[0]!.payload.priority, 4);
  } finally {
    await new Promise((done) => server.close(done));
  }
});

test("ignores non-blocked and non-status events", async () => {
  const { server, requests } = captureServer();
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));

  const publisher = new NtfyPublisher({ baseUrl: "http://127.0.0.1:1/ntfy", topic: "t" });
  try {
    await publisher.handleEvent({ kind: "pane_agent_status_changed", data: { pane_id: "w1:p1", agent_status: "working" } });
    await publisher.handleEvent({ kind: "workspace_created", data: {} });
    await publisher.handleEvent({ kind: "pane_agent_status_changed", data: { agent_status: "blocked" } });
    assert.equal(requests.length, 0);
  } finally {
    await new Promise((done) => server.close(done));
  }
});

test("prune lets a previously-throttled pane publish again immediately", async () => {
  const { server, requests } = captureServer();
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  const port = (server.address() as { port: number }).port;

  const publisher = new NtfyPublisher({ baseUrl: `http://127.0.0.1:${port}`, topic: "t" });
  try {
    await publisher.handleEvent(blockedEvent("w1:p1"));
    await publisher.handleEvent(blockedEvent("w1:p1"));
    assert.equal(requests.length, 1, "second event is throttled");
    publisher.prune(new Set());
    await publisher.handleEvent(blockedEvent("w1:p1"));
    assert.equal(requests.length, 2, "prune clears the throttle; pane publishes again");
    // Pruning a pane that is still alive keeps its throttle.
    await publisher.handleEvent(blockedEvent("w1:p2"));
    assert.equal(requests.length, 3);
    publisher.prune(new Set(["w1:p2"]));
    await publisher.handleEvent(blockedEvent("w1:p2"));
    assert.equal(requests.length, 3, "alive panes keep their throttle after prune");
  } finally {
    await new Promise((done) => server.close(done));
  }
});

test("throttles repeat blocked events for the same pane", async () => {
  const { server, requests } = captureServer();
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  const port = (server.address() as { port: number }).port;

  const publisher = new NtfyPublisher({ baseUrl: `http://127.0.0.1:${port}`, topic: "t" });
  try {
    await publisher.handleEvent(blockedEvent("w1:p1"));
    await publisher.handleEvent(blockedEvent("w1:p1"));
    await publisher.handleEvent(blockedEvent("w1:p1"));
    assert.equal(requests.length, 1);
  } finally {
    await new Promise((done) => server.close(done));
  }
});

test("publishes a done event with the finished headline", async () => {
  const { server, requests } = captureServer();
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  const port = (server.address() as { port: number }).port;

  const publisher = new NtfyPublisher({ baseUrl: `http://127.0.0.1:${port}`, topic: "t" });
  try {
    const sent = await publisher.handleEvent(blockedEvent("w1:p9", { agent_status: "done" }));
    assert.equal(sent, true);
    assert.equal(requests.length, 1);
    assert.equal(requests[0]!.payload.title, "π finished");
    assert.equal(requests[0]!.payload.message, "my pane");
    assert.equal(requests[0]!.payload.paneId, "w1:p9");
    assert.equal(requests[0]!.payload.click, "cockpit://chat/w1:p9?status=working");
    assert.equal(requests[0]!.payload.priority, 3);
  } finally {
    await new Promise((done) => server.close(done));
  }
});

test("publish failure does not throw", async () => {
  const publisher = new NtfyPublisher({ baseUrl: "http://127.0.0.1:1", topic: "t" });
  await publisher.publish({ title: "x", message: "y" });
  assert.ok(true);
});

test("disabled publisher does nothing", async () => {
  const publisher = new NtfyPublisher(null);
  const sent = await publisher.handleEvent(blockedEvent("w1:p1"));
  assert.equal(sent, false);
});
