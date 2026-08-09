import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { PiRpcManager } from "../src/pi/rpc.js";

const here = dirname(fileURLToPath(import.meta.url));

before(() => {
  process.env.PI_BIN = join(here, "fixtures", "fake-pi.mjs");
});

const manager = new PiRpcManager();

after(async () => {
  manager.disposeAll();
});

test("spawns a session and reports running", async () => {
  const session = await manager.create("test-session");
  try {
    assert.equal(session.status, "running");
    assert.equal(session.info.name, "test-session");
    assert.equal(session.info.uiRequests.length, 0);
  } finally {
    manager.dispose(session.id);
  }
});

test("prompt produces entries with a durable cursor", async () => {
  const session = await manager.create();
  try {
    await session.prompt("hello");
    const first = await session.getEntries();
    assert.ok(first.entries.length >= 2);
    assert.equal(first.entries[0]!.role, "user");
    assert.equal(first.entries[0]!.content[0]!.type, "text");
    const since = first.leafId;

    await session.prompt("again");
    const delta = await session.getEntries(since);
    assert.equal(delta.entries.length, 2);
    assert.equal(delta.entries.at(-1)!.role, "assistant");
  } finally {
    manager.dispose(session.id);
  }
});

test("extension_ui_request is surfaced and answered programmatically", async () => {
  const session = await manager.create();
  const uiRequests: string[] = [];
  session.onUiRequest = (request) => uiRequests.push(request.id);
  try {
    await session.prompt("ASK");
    assert.equal(uiRequests.length, 1);
    const info = session.info;
    assert.equal(info.uiRequests.length, 1);
    assert.equal(info.uiRequests[0]!.method, "select");
    assert.deepEqual(info.uiRequests[0]!.options, ["Approve", "Reject"]);

    session.respondToUi("ui-1", { value: "Approve" });
    await new Promise((done) => setTimeout(done, 100));
    const entries = await session.getEntries();
    const last = entries.entries.at(-1)!;
    assert.equal(last.role, "assistant");
    assert.match((last.content[0] as { text?: string }).text ?? "", /Approved: Approve/);
    assert.equal(session.info.uiRequests.length, 0);
  } finally {
    manager.dispose(session.id);
  }
});

test("unknown commands surface the error", async () => {
  const session = await manager.create();
  try {
    await assert.rejects(
      () => (session as unknown as { request(cmd: string): Promise<unknown> }).request.call(session, "bogus"),
      /unknown command/,
    );
  } finally {
    manager.dispose(session.id);
  }
});

test("a stopped session rejects new commands", async () => {
  const session = await manager.create();
  session.stop();
  await new Promise((done) => setTimeout(done, 100));
  await assert.rejects(() => session.prompt("x"), /not running|exited/);
  manager.dispose(session.id);
});
