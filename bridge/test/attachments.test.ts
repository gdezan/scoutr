import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  AttachmentError,
  readAttachmentBody,
  storeAttachment,
  uploadsDir,
} from "../src/attachments.js";

let dir: string;

test.before(async () => {
  dir = join(await mkdtemp(join(tmpdir(), "cockpit-uploads-")), "uploads");
  await mkdir(dir, { recursive: true });
});

test.after(async () => {
  await rm(dir, { recursive: true, force: true });
});

test("uploadsDir sits next to the config file", () => {
  assert.equal(uploadsDir("/home/u/.config/cockpit/config.json"), "/home/u/.config/cockpit/uploads");
});

test("storeAttachment saves an image and returns its host path", async () => {
  const path = storeAttachment(dir, "photo.png", Buffer.from("PNGDATA"), "image/png");
  assert.ok(path.startsWith(dir));
  assert.ok(existsSync(path));
  assert.equal(await readFile(path, "utf8"), "PNGDATA");
});

test("rejects non-image content types and unsupported extensions", () => {
  assert.throws(() => storeAttachment(dir, "evil.exe", Buffer.from("MZ"), "application/octet-stream"), (e: unknown) => {
    assert.ok(e instanceof AttachmentError);
    assert.equal(e.status, 400);
    return true;
  });
  assert.throws(() => storeAttachment(dir, "photo.bmp", Buffer.from("BM"), "image/bmp"), (e: unknown) => {
    assert.ok(e instanceof AttachmentError);
    assert.equal(e.status, 400);
    return true;
  });
});

test("readAttachmentBody caps oversize bodies", async () => {
  const chunk = Buffer.alloc(1024, 1);
  const stream = (async function* () {
    for (let i = 0; i < 20; i++) yield chunk;
  })();
  await assert.rejects(() => readAttachmentBody(stream, 10 * 1024), (e: unknown) => {
    assert.ok(e instanceof AttachmentError);
    assert.equal(e.status, 413);
    return true;
  });
});

test("readAttachmentBody rejects empty bodies", async () => {
  const stream = (async function* () {})();
  await assert.rejects(() => readAttachmentBody(stream), (e: unknown) => {
    assert.ok(e instanceof AttachmentError);
    assert.equal(e.status, 400);
    return true;
  });
});

test("prunes uploads beyond the file cap", async () => {
  for (let i = 0; i < 205; i++) {
    await writeFile(join(dir, `old_${i}.png`), "x");
  }
  storeAttachment(dir, "new.png", Buffer.from("PNGDATA"), "image/png");
  const remaining = await readdir(dir);
  assert.ok(remaining.length <= 200, `expected at most 200 uploads, got ${remaining.length}`);
  assert.ok(remaining.some((name) => name.includes("new.png")), "the new attachment survives pruning");
});
