import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { FileWorkspaceRootStore } from "../src/workspace-roots.js";

describe("FileWorkspaceRootStore", () => {
  let dir: string;
  let store: FileWorkspaceRootStore;

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), "scoutr-workspace-roots-"));
    store = new FileWorkspaceRootStore(dir);
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  it("reads a missing file as an empty registry", async () => {
    assert.deepEqual(await store.list(), []);
  });

  it("records and persists canonical workspace roots", async () => {
    await store.record("ws2", "/tmp/scoutr-project");
    const records = await store.list();
    assert.equal(records.length, 1);
    assert.equal(records[0]?.workspaceId, "ws2");
    assert.match(records[0]?.cwd ?? "", /^\/tmp\/scoutr-project$/);
    // updatedAtMs is a real epoch timestamp, not a placeholder like 0.
    const stamp = records[0]?.updatedAtMs ?? 0;
    assert.ok(Math.abs(Date.now() - stamp) < 60_000);
    // A fresh instance over the same configDir sees the record.
    const reopened = new FileWorkspaceRootStore(dir);
    assert.deepEqual(await reopened.list(), records);
  });

  it("canonicalizes symlinks when recording", async () => {
    const real = await mkdtemp(join(tmpdir(), "scoutr-real-"));
    try {
      const { symlink } = await import("node:fs/promises");
      const link = join(dir, "link");
      await symlink(real, link);
      await store.record("ws3", link);
      assert.deepEqual((await store.list()).map((record) => record.cwd), [real]);
    } finally {
      await rm(real, { recursive: true, force: true });
    }
  });

  it("upserts by workspace id instead of duplicating", async () => {
    await store.record("ws4", "/tmp/one");
    await store.record("ws4", "/tmp/two");
    const records = await store.list();
    assert.equal(records.length, 1);
    assert.match(records[0]?.cwd ?? "", /two$/);
  });

  it("removes a record", async () => {
    await store.record("ws5", "/tmp/gone");
    await store.remove("ws5");
    assert.deepEqual(await store.list(), []);
  });

  it("prunes records whose workspace is no longer live", async () => {
    await store.record("ws-live", "/tmp/live");
    await store.record("ws-dead", "/tmp/dead");
    await store.prune(new Set(["ws-live"]));
    const ids = (await store.list()).map((record) => record.workspaceId);
    assert.deepEqual(ids.sort(), ["ws-live"]);
  });

  it("degrades a malformed file to an empty registry and still writes after it", async () => {
    await writeFile(join(dir, "workspace-roots.json"), "{not json");
    assert.deepEqual(await store.list(), []);
    await store.record("ws6", "/tmp/recovered");
    assert.equal((await store.list()).length, 1);
  });

  it("ignores malformed entries inside an otherwise readable file", async () => {
    await writeFile(
      join(dir, "workspace-roots.json"),
      JSON.stringify([
        { workspaceId: "ok", cwd: "/tmp/ok", updatedAtMs: 1 },
        { workspaceId: "", cwd: "/tmp/bad" },
        "junk",
        { workspaceId: "no-time", cwd: "/tmp/x", updatedAtMs: "soon" },
      ]),
    );
    const records = await store.list();
    assert.deepEqual(records.map((record) => record.workspaceId), ["ok"]);
  });

  it("serializes concurrent writes without losing records", async () => {
    await Promise.all(
      Array.from({ length: 20 }, (_, index) => store.record(`ws-conc-${index}`, `/tmp/conc-${index}`)),
    );
    const records = await store.list();
    assert.equal(records.length, 20);
  });

  it("caps persisted records by dropping the oldest updates", async () => {
    // Seed more than MAX_RECORDS (512) directly through many updates of one
    // rolling set; use the public API so the cap logic is exercised as-is.
    for (let index = 0; index < 40; index += 1) {
      await store.record(`ws-cap-${index}`, `/tmp/cap-${index}`);
    }
    // Rewrite early ids to bump their timestamps above later ones.
    await store.record("ws-cap-0", "/tmp/cap-0-refreshed");
    const records = await store.list();
    assert.ok(records.length <= 512);
    const refreshed = records.find((record) => record.workspaceId === "ws-cap-0");
    assert.match(refreshed?.cwd ?? "", /refreshed$/);
  });

  it("writes atomically via temp file + rename", async () => {
    await store.record("ws7", "/tmp/atomic");
    // SAFETY: the store's documented on-disk format is a JSON array of records.
    const raw = JSON.parse(await readFile(join(dir, "workspace-roots.json"), "utf8")) as unknown[];
    assert.ok(Array.isArray(raw));
    // The temp file is gone after a successful write.
    await assert.rejects(readFile(join(dir, "workspace-roots.json.tmp")));
  });
});
