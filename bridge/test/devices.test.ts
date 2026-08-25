import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { createScoutrServer, type ScoutrServer } from "../src/server.js";
import { JsonDeviceRegistry, MAX_TOKEN_LENGTH } from "../src/push/devices.js";
import type { JsonBody } from "../src/routes/types.js";
import { fakeHerdr } from "./support/fake-herdr.js";
import { fakeFeed } from "./support/fake-feed.js";
import { FakeTerminalLauncher } from "./support/fake-terminal.js";

/** POST /api/devices — the only way a phone tells the bridge how to reach it. */

const PORT = 8798;
const TOKEN = "test_token_for_devices_00001";

interface DeviceRequestBody extends JsonBody {
  profileGeneration?: string | number | null;
}

interface PushHealthResponse {
  push: { fcm: boolean };
}

let server: ScoutrServer;
let devices: JsonDeviceRegistry;

before(async () => {
  devices = await JsonDeviceRegistry.open(await mkdtemp(join(tmpdir(), "scoutr-devices-route-")));
  server = createScoutrServer({
    herdr: fakeHerdr({ panes: [] }),
    feed: fakeFeed({
      version: "0.8.0",
      protocol: 19,
      focused_workspace_id: null,
      focused_tab_id: null,
      focused_pane_id: null,
      workspaces: [],
      tabs: [],
      panes: [],
      agents: [],
      layouts: [],
    }),
    // SAFETY: these tests never call usage; the stub fills an unused ServerDeps field.
    usage: { all: async () => ({}) } as never,
    config: { configDir: "/tmp/scoutr-test-config", hostId: "host_test", token: TOKEN, port: PORT, exposure: { kind: "tailscale" } },
    terminal: new FakeTerminalLauncher(),
    devices,
  });
});

after(async () => {
  await server.close();
});

async function post(
  body: DeviceRequestBody,
  token = TOKEN,
  path = "/api/devices",
): Promise<{ status: number; data: any }> {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return { status: response.status, data: await response.json() };
}

describe("POST /api/devices", () => {
  it("registers a token and is idempotent", async () => {
    const first = await post({ fcmToken: "device-token-1" });
    assert.equal(first.status, 200);
    assert.equal(first.data.ok, true);

    await post({ fcmToken: "device-token-1" });
    assert.deepEqual(devices.list().map((device) => device.token), ["device-token-1"]);
  });

  it("rejects a missing or blank token", async () => {
    for (const body of [{}, { fcmToken: "" }, { fcmToken: "   " }]) {
      const { status, data } = await post(body);
      assert.equal(status, 400);
      assert.equal(data.ok, false);
    }
  });

  it("rejects an absurdly long token", async () => {
    const { status, data } = await post({ fcmToken: "x".repeat(MAX_TOKEN_LENGTH + 1) });
    assert.equal(status, 400);
    assert.match(data.error, /exceeds/);
  });

  it("accepts only positive decimal-string profile generations", async () => {
    const valid = await post({ fcmToken: "generation-token", profileGeneration: "42" });
    assert.equal(valid.status, 200);
    assert.deepEqual(devices.list().find((device) => device.token === "generation-token"), {
      token: "generation-token",
      profileGeneration: "42",
      updatedAtMs: devices.list().find((device) => device.token === "generation-token")?.updatedAtMs,
    });
    const replaced = await post({ fcmToken: "generation-token", profileGeneration: "43" });
    assert.equal(replaced.status, 200);
    assert.equal(devices.list().find((device) => device.token === "generation-token")?.profileGeneration, "43");

    for (const profileGeneration of ["", "0", "-1", "1.5", 1, null]) {
      const { status, data } = await post({ fcmToken: `invalid-generation-${String(profileGeneration)}`, profileGeneration });
      assert.equal(status, 400);
      assert.match(data.error, /profileGeneration/);
    }
  });

  it("refuses an unauthenticated caller", async () => {
    const { status } = await post({ fcmToken: "device-token-2" }, "wrong_token");
    assert.equal(status, 401);
  });

  it("unregisters an existing or already-absent token", async () => {
    const registered = await post({ fcmToken: "token-to-unregister", profileGeneration: "9" });
    assert.equal(registered.status, 200);

    const first = await post({ fcmToken: "token-to-unregister" }, TOKEN, "/api/devices/unregister");
    assert.deepEqual(first, { status: 200, data: { ok: true } });
    assert.equal(devices.list().some((device) => device.token === "token-to-unregister"), false);

    const second = await post({ fcmToken: "token-to-unregister" }, TOKEN, "/api/devices/unregister");
    assert.deepEqual(second, { status: 200, data: { ok: true } });

    const unauthorized = await post({ fcmToken: "generation-token" }, "wrong_token", "/api/devices/unregister");
    assert.equal(unauthorized.status, 401);
  });

  it("reports push state through /api/health", async () => {
    const response = await fetch(`http://127.0.0.1:${PORT}/api/health`, {
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    const health: PushHealthResponse = await response.json();
    // This bridge has no service-account path configured.
    assert.deepEqual(health.push, { fcm: false });
  });
});
