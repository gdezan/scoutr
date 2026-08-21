import { test } from "node:test";
import assert from "node:assert/strict";
import { FcmPublisher } from "../src/push/publisher.js";
import type { DeviceRegistry, FcmSender, PingKind, PushDevice } from "../src/push/fcm.js";

/**
 * The edge-trigger contract: one ping per transition, never per event. These
 * cases are the reason the 60s/pane throttle was removed — a timer could
 * swallow the resolve that clears the phone's notification, and this cannot.
 */

function fakeSender(): FcmSender & { sent: Array<{ kind: PingKind; paneId: string }>; stale: string[] } {
  const sent: Array<{ kind: PingKind; paneId: string }> = [];
  const stale: string[] = [];
  return {
    sent,
    stale,
    async send(_tokens, kind, paneId) {
      sent.push({ kind, paneId });
      return stale.splice(0, stale.length);
    },
  };
}

function fakeDevices(tokens: string[] = ["device-a"]): DeviceRegistry & { unregistered: string[] } {
  const devices: PushDevice[] = tokens.map((token) => ({ token, updatedAtMs: 0 }));
  const unregistered: string[] = [];
  return {
    unregistered,
    list: () => devices,
    async register(token) {
      devices.push({ token, updatedAtMs: 0 });
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

test("pruning a blocked pane resolves it; an unblocked one sends nothing", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices());

  await publisher.handleEvent(statusEvent("w1:p1", "blocked"));
  await publisher.handleEvent(statusEvent("w1:p2", "blocked"));
  await publisher.handleEvent(statusEvent("w1:p2", "working"));
  sender.sent.length = 0;

  publisher.prune(new Set<string>());
  await Promise.resolve();

  assert.deepEqual(sender.sent, [{ kind: "resolve", paneId: "w1:p1" }]);
});

test("a pruned pane is forgotten, so it can block again", async () => {
  const sender = fakeSender();
  const publisher = new FcmPublisher(sender, fakeDevices());

  await publisher.handleEvent(statusEvent("w1:p1", "blocked"));
  publisher.prune(new Set<string>());
  await Promise.resolve();
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
