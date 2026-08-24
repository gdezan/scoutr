import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { HttpV1FcmSender } from "../src/push/fcm.js";
import { JsonDeviceRegistry } from "../src/push/devices.js";

/** Capture every FCM request without touching the network. */
function stubFetch(respond: (call: number) => { status: number; body?: string }) {
  const calls: Array<{ url: string; authorization: string; body: unknown }> = [];
  const fetchImpl: typeof fetch = async (url, init) => {
    const headers = new Headers(init?.headers);
    calls.push({
      url: String(url),
      authorization: headers.get("authorization") ?? "",
      body: JSON.parse(String(init?.body)),
    });
    const { status, body = "{}" } = respond(calls.length);
    return new Response(body, { status });
  };
  return { fetchImpl, calls };
}

function senderWith(fetchImpl: typeof fetch): HttpV1FcmSender {
  return new HttpV1FcmSender({ projectId: "scoutr-test", accessToken: async () => "ya29.stub", fetchImpl });
}

test("a blocked ping is contentless, high priority, and short-lived", async () => {
  const { fetchImpl, calls } = stubFetch(() => ({ status: 200 }));
  const stale = await senderWith(fetchImpl).send(["device-a"], "blocked", "w1:p1", "host-a");

  assert.deepEqual(stale, []);
  assert.equal(calls.length, 1);
  assert.equal(calls[0]!.url, "https://fcm.googleapis.com/v1/projects/scoutr-test/messages:send");
  assert.equal(calls[0]!.authorization, "Bearer ya29.stub");
  assert.deepEqual(calls[0]!.body, {
    message: {
      token: "device-a",
      data: { kind: "blocked", paneId: "w1:p1" },
      android: { priority: "high", ttl: "900s" },
    },
  });
});

test("a generation-qualified ping carries exactly the host and profile generation", async () => {
  const { fetchImpl, calls } = stubFetch(() => ({ status: 200 }));
  await senderWith(fetchImpl).send(["device-a"], "blocked", "w1:p1", "host-a", "17");

  assert.deepEqual(calls[0]!.body, {
    message: {
      token: "device-a",
      data: { kind: "blocked", hostId: "host-a", profileGeneration: "17", paneId: "w1:p1" },
      android: { priority: "high", ttl: "900s" },
    },
  });
  const data = (calls[0]!.body as { message: { data: Record<string, unknown> } }).message.data;
  assert.deepEqual(Object.keys(data).sort(), ["hostId", "kind", "paneId", "profileGeneration"]);
});

test("a resolve ping rides normal priority with a long ttl", async () => {
  const { fetchImpl, calls } = stubFetch(() => ({ status: 200 }));
  await senderWith(fetchImpl).send(["device-a"], "resolve", "w1:p1", "host-a");

  assert.deepEqual(calls[0]!.body, {
    message: {
      token: "device-a",
      data: { kind: "resolve", paneId: "w1:p1" },
      android: { priority: "normal", ttl: "3600s" },
    },
  });
});

test("no payload ever carries a notification block or agent text", async () => {
  const { fetchImpl, calls } = stubFetch(() => ({ status: 200 }));
  await senderWith(fetchImpl).send(["device-a"], "blocked", "w1:p1", "host-a");

  const message = (calls[0]!.body as { message: Record<string, unknown> }).message;
  assert.equal(message.notification, undefined);
  assert.deepEqual(Object.keys(message.data as object).sort(), ["kind", "paneId"]);
});

test("a 404 reports the token for unregistration, other failures do not", async () => {
  const { fetchImpl } = stubFetch((call) => (call === 1 ? { status: 404 } : { status: 503 }));
  const stale = await senderWith(fetchImpl).send(["dead-token", "live-token"], "blocked", "w1:p1", "host-a");

  assert.deepEqual(stale, ["dead-token"]);
});

test("an UNREGISTERED error body reports the token too", async () => {
  const { fetchImpl } = stubFetch(() => ({
    status: 400,
    body: JSON.stringify({ error: { status: "UNREGISTERED" } }),
  }));
  const stale = await senderWith(fetchImpl).send(["dead-token"], "blocked", "w1:p1", "host-a");

  assert.deepEqual(stale, ["dead-token"]);
});

test("a send with no registered devices makes no request", async () => {
  const { fetchImpl, calls } = stubFetch(() => ({ status: 200 }));
  const stale = await senderWith(fetchImpl).send([], "blocked", "w1:p1", "host-a");

  assert.deepEqual(stale, []);
  assert.equal(calls.length, 0);
});

test("the registry persists 0600, dedupes, and reloads", async () => {
  const dir = await mkdtemp(join(tmpdir(), "scoutr-devices-"));
  const registry = await JsonDeviceRegistry.open(dir);

  await registry.register("token-a");
  await registry.register("token-b", "4");
  await registry.register("token-a", "7");
  assert.deepEqual(registry.list().map((device) => device.token), ["token-a", "token-b"]);
  assert.equal(registry.list().find((device) => device.token === "token-a")?.profileGeneration, "7");
  assert.equal(registry.list().find((device) => device.token === "token-b")?.profileGeneration, "4");

  const file = join(dir, "devices.json");
  assert.equal((await stat(file)).mode & 0o777, 0o600);
  assert.equal(JSON.parse(await readFile(file, "utf8")).length, 2);

  await registry.unregister("token-a");
  const reopened = await JsonDeviceRegistry.open(dir);
  assert.deepEqual(reopened.list().map((device) => device.token), ["token-b"]);
});

test("bare-token registry entries load as legacy null-generation records", async () => {
  const dir = await mkdtemp(join(tmpdir(), "scoutr-devices-"));
  await writeFile(
    join(dir, "devices.json"),
    JSON.stringify([{ token: "legacy-token", updatedAtMs: 12 }]),
  );

  const registry = await JsonDeviceRegistry.open(dir);
  assert.deepEqual(registry.list(), [{ token: "legacy-token", profileGeneration: null, updatedAtMs: 12 }]);
});

test("a corrupt registry file starts empty rather than throwing", async () => {
  const dir = await mkdtemp(join(tmpdir(), "scoutr-devices-"));
  await writeFile(join(dir, "devices.json"), "not json at all");

  const registry = await JsonDeviceRegistry.open(dir);
  assert.deepEqual(registry.list(), []);
});
