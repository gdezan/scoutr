import { test } from "node:test";
import assert from "node:assert/strict";
import { FcmPublisher } from "../src/push/publisher.js";
import type { DeviceRegistry, FcmSender, PingKind, PushDevice } from "../src/push/fcm.js";

/**
 * The edge-trigger contract: one ping per transition, never per event. These
 * cases are the reason the 60s/pane throttle was removed — a timer could
 * swallow the resolve that clears the phone's notification, and this cannot.
 */

function fakeSender(): FcmSender & {
  sent: Array<{ kind: PingKind; paneId: string }>;
  calls: Array<{ tokens: string[]; hostId: string; profileGeneration?: string | null }>;
  stale: string[];
} {
  const sent: Array<{ kind: PingKind; paneId: string }> = [];
  const calls: Array<{ tokens: string[]; hostId: string; profileGeneration?: string | null }> = [];
  const stale: string[] = [];
  return {
    sent,
    calls,
    stale,
    async send(tokens, kind, paneId, hostId, profileGeneration) {
      calls.push({ tokens: [...tokens], hostId, profileGeneration });
      sent.push({ kind, paneId });
      return stale.splice(0, stale.length);
    },
  };
}

function fakeDevices(
  tokens: string[] = ["device-a"],
  generations: Record<string, string | null> = {},
): DeviceRegistry & { unregistered: string[] } {
  const devices: PushDevice[] = tokens.map((token) => ({
    token,
    profileGeneration: generations[token] ?? null,
    updatedAtMs: 0,
  }));
  const unregistered: string[] = [];
  return {
    unregistered,
    list: () => devices,
    async register(token, profileGeneration) {
      devices.push({ token, profileGeneration: profileGeneration ?? null, updatedAtMs: 0 });
    },
    async unregister(token) {
      unregistered.push(token);
      const at = devices.findIndex((device) => device.token === token);
      if (at >= 0) devices.splice(at, 1);
    },
  };
}

function statusEvent(paneId: string, status: string, kind = "pane.agent_status_changed") {
  return { kind, data: { pane_id: paneId, agent: "pi", agent_status: status } };
}

test("repeated blocked events for one pane send exactly one ping", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices());

  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "blocked")), true);
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "blocked")), false);
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "blocked")), false);

  assert.deepEqual(sender.sent, [{ kind: "blocked", paneId: "w1:p1" }]);
});

test("leaving blocked sends exactly one resolve", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices());

  await publisher.handleEvent(statusEvent("w1:p1", "blocked"));
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "working")), true);
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "idle")), false);

  assert.deepEqual(sender.sent, [
    { kind: "blocked", paneId: "w1:p1" },
    { kind: "resolve", paneId: "w1:p1" },
  ]);
});

test("done clears the notification like any other exit from blocked", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices());

  await publisher.handleEvent(statusEvent("w1:p1", "blocked"));
  await publisher.handleEvent(statusEvent("w1:p1", "done"));

  assert.deepEqual(sender.sent, [
    { kind: "blocked", paneId: "w1:p1" },
    { kind: "resolve", paneId: "w1:p1" },
    { kind: "done", paneId: "w1:p1" },
  ]);
});

test("both event spellings are matched", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices());

  await publisher.handleEvent(statusEvent("w1:p1", "blocked", "pane_agent_status_changed"));
  await publisher.handleEvent(statusEvent("w1:p2", "blocked", "pane.agent_status_changed"));

  assert.deepEqual(sender.sent, [
    { kind: "blocked", paneId: "w1:p1" },
    { kind: "blocked", paneId: "w1:p2" },
  ]);
});

test("each registration receives the bridge identity and its own generation", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices(["legacy", "current"], { current: "8" }), "host-a");

  await publisher.handleEvent(statusEvent("w1:p1", "blocked"));

  assert.deepEqual(sender.calls, [
    { tokens: ["legacy"], hostId: "host-a", profileGeneration: null },
    { tokens: ["current"], hostId: "host-a", profileGeneration: "8" },
  ]);
});

test("pruning a blocked pane resolves it; an unblocked one sends nothing", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices());

  await publisher.handleEvent(statusEvent("w1:p1", "blocked"));
  await publisher.handleEvent(statusEvent("w1:p2", "blocked"));
  await publisher.handleEvent(statusEvent("w1:p2", "working"));
  sender.sent.length = 0;

  await publisher.prune(new Set<string>());

  assert.deepEqual(sender.sent, [{ kind: "resolve", paneId: "w1:p1" }]);
});

test("a pruned pane is forgotten, so it can block again", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices());

  await publisher.handleEvent(statusEvent("w1:p1", "blocked"));
  await publisher.prune(new Set<string>());
  sender.sent.length = 0;

  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "blocked")), true);
  assert.deepEqual(sender.sent, [{ kind: "blocked", paneId: "w1:p1" }]);
});

test("non-status events and status-less events are ignored", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices());

  assert.equal(await publisher.handleEvent({ kind: "pane_closed", data: { pane_id: "w1:p1" } }), false);
  assert.equal(await publisher.handleEvent(statusEvent("", "blocked")), false);
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "")), false);

  assert.deepEqual(sender.sent, []);
});

test("with no registered devices nothing is sent, but transitions still track", async () => {
  const sender = fakeSender();
  const devices = fakeDevices([]);
  const publisher = new FcmPublisher(sender, devices);

  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "blocked")), false);
  assert.deepEqual(sender.sent, []);
});

test("a token FCM rejects is unregistered", async () => {
  const sender = fakeSender();
  const devices = fakeDevices(["dead-token"]);
  sender.stale.push("dead-token");
  const publisher = new FcmPublisher(sender, devices);

  await publisher.handleEvent(statusEvent("w1:p1", "blocked"));

  assert.deepEqual(devices.unregistered, ["dead-token"]);
});

test("settling idle onto a failed model call pings errored once", async () => {
  const sender = fakeSender();
  let stopped = true;
  const publisher = new FcmPublisher(sender, fakeDevices(), "host-a", async () => stopped);

  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "idle")), true);
  // herdr only emits on change, but a repeated settle must not re-ping.
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "idle")), false);

  assert.deepEqual(sender.sent, [{ kind: "errored", paneId: "w1:p1" }]);
  assert.equal(stopped, true);
});

test("a clean finish settles idle without any ping", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices(), "host-a", async () => false);

  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "idle")), false);

  assert.deepEqual(sender.sent, []);
});

test("the agent moving again resolves the errored notification and re-arms", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices(), "host-a", async () => true);

  await publisher.handleEvent(statusEvent("w1:p1", "idle"));
  await publisher.handleEvent(statusEvent("w1:p1", "working"));
  sender.sent.length = 0;

  // The user continued; the next failure must notify again, not stay deduped.
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "idle")), true);
  assert.deepEqual(sender.sent, [{ kind: "errored", paneId: "w1:p1" }]);
});

test("blocked and done also clear an errored stop", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices(), "host-a", async () => true);

  await publisher.handleEvent(statusEvent("w1:p1", "idle"));
  await publisher.handleEvent(statusEvent("w1:p1", "blocked"));
  await publisher.handleEvent(statusEvent("w1:p2", "idle"));
  await publisher.handleEvent(statusEvent("w1:p2", "done"));

  assert.deepEqual(sender.sent, [
    { kind: "errored", paneId: "w1:p1" },
    { kind: "resolve", paneId: "w1:p1" },
    { kind: "blocked", paneId: "w1:p1" },
    { kind: "errored", paneId: "w1:p2" },
    { kind: "resolve", paneId: "w1:p2" },
    { kind: "done", paneId: "w1:p2" },
  ]);
});

test("pruning an errored pane resolves it so the tray does not outlive the pane", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices(), "host-a", async () => true);

  await publisher.handleEvent(statusEvent("w1:p1", "idle"));
  sender.sent.length = 0;

  await publisher.prune(new Set<string>());

  assert.deepEqual(sender.sent, [{ kind: "resolve", paneId: "w1:p1" }]);
});

test("without a probe, settling idle sends nothing (pre-error-stop behavior)", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices());

  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "idle")), false);
  assert.deepEqual(sender.sent, []);
});

test("a working event that lands while the probe is in flight resolves the ping", async () => {
  const sender = fakeSender();
  let release!: () => void;
  const gate = new Promise<void>((resolve) => (release = resolve));
  const publisher = new FcmPublisher(sender, fakeDevices(), "host-a", async () => {
    await gate;
    return true;
  });

  // The idle probe is still awaiting the transcript when the agent moves.
  const settle = publisher.handleEvent(statusEvent("w1:p1", "idle"));
  const moved = publisher.handleEvent(statusEvent("w1:p1", "working"));
  release();
  assert.equal(await settle, true);
  assert.equal(await moved, true);

  assert.deepEqual(sender.sent, [
    { kind: "errored", paneId: "w1:p1" },
    { kind: "resolve", paneId: "w1:p1" },
  ]);
});

test("a turn that ends done on a failed model call pings errored, not done", async () => {
  const sender = fakeSender();
  let stopped = true;
  const publisher = new FcmPublisher(sender, fakeDevices(), "host-a", async () => stopped);

  await publisher.handleEvent(statusEvent("w1:p1", "working"));
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "done")), true);

  assert.deepEqual(sender.sent, [{ kind: "errored", paneId: "w1:p1" }]);
});

test("a clean done with a probe still pings done and re-arms after movement", async () => {
  const sender = fakeSender();
  let stopped = false;
  const publisher = new FcmPublisher(sender, fakeDevices(), "host-a", async () => stopped);

  await publisher.handleEvent(statusEvent("w1:p1", "working"));
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "done")), true);
  assert.deepEqual(sender.sent, [{ kind: "done", paneId: "w1:p1" }]);

  // The user continued; this time the model dies again.
  await publisher.handleEvent(statusEvent("w1:p1", "working"));
  stopped = true;
  sender.sent.length = 0;
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "done")), true);
  assert.deepEqual(sender.sent, [{ kind: "errored", paneId: "w1:p1" }]);
});

test("an errored stop is cleared when the agent works again or blocks later", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices(), "host-a", async () => true);

  await publisher.handleEvent(statusEvent("w1:p1", "done"));
  await publisher.handleEvent(statusEvent("w1:p1", "working"));

  assert.deepEqual(sender.sent, [
    { kind: "errored", paneId: "w1:p1" },
    { kind: "resolve", paneId: "w1:p1" },
  ]);
});

test("a probe failure on a done transition falls back to the normal done ping", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(
    sender,
    fakeDevices(),
    "host-a",
    async () => {
      throw new Error("transcript vanished");
    },
  );

  await publisher.handleEvent(statusEvent("w1:p1", "working"));
  assert.equal(await publisher.handleEvent(statusEvent("w1:p1", "done")), true);

  assert.deepEqual(sender.sent, [{ kind: "done", paneId: "w1:p1" }]);
});

test("a nested subagent pane never pings; its parent still does", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(
    sender,
    fakeDevices(),
    "host-a",
    undefined,
    async (paneId) => paneId === "child",
  );

  assert.equal(await publisher.handleEvent(statusEvent("child", "blocked")), false);
  assert.equal(await publisher.handleEvent(statusEvent("parent", "blocked")), true);
  assert.deepEqual(sender.sent, [{ kind: "blocked", paneId: "parent" }]);
});

test("an orphan subagent pane still pings", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(
    sender,
    fakeDevices(),
    "host-a",
    undefined,
    async () => false,
  );

  assert.equal(await publisher.handleEvent(statusEvent("orphan", "blocked")), true);
  assert.deepEqual(sender.sent, [{ kind: "blocked", paneId: "orphan" }]);
});

test("a pane that was already pinged then classifies as nested sends resolve", async () => {
  const sender = fakeSender();
  let nested = false;
  const publisher = new FcmPublisher(
    sender,
    fakeDevices(),
    "host-a",
    undefined,
    async (paneId) => paneId === "child" && nested,
  );

  assert.equal(await publisher.handleEvent(statusEvent("child", "blocked")), true);
  nested = true;
  assert.equal(await publisher.handleEvent(statusEvent("child", "blocked")), true);
  assert.deepEqual(sender.sent, [
    { kind: "blocked", paneId: "child" },
    { kind: "resolve", paneId: "child" },
  ]);
});
