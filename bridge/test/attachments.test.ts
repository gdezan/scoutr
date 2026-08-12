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

/** Minimal real PNG header (89 50 4E 47 0D 0A 1A 0A) plus payload. */
function pngBytes(): Buffer {
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.from("PNGDATA")]);
}

test("uploadsDir sits next to the config file", () => {
  assert.equal(uploadsDir("/home/u/.config/cockpit/config.json"), "/home/u/.config/cockpit/uploads");
});

test("storeAttachment saves an image and returns its host path", async () => {
  const path = storeAttachment(dir, "photo.png", pngBytes(), "image/png");
  assert.ok(path.startsWith(dir));
  assert.ok(existsSync(path));
  assert.equal(await readFile(path, "utf8"), "\uFFFDPNG\r\n\u001A\nPNGDATA");
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

test("rejects image-typed bodies whose bytes do not match the extension", () => {
  
  // Text bytes declared as a PNG: the sniff must reject, not the extension.
  assert.throws(() => storeAttachment(dir, "photo.png", Buffer.from("hello world"), "image/png"), (e: unknown) => {
    assert.ok(e instanceof AttachmentError);
    assert.equal((e as AttachmentError).status, 400);
    assert.match((e as AttachmentError).message, /bytes do not match/);
    return true;
  });
  // A JPEG header under a .gif name is a family mismatch too.
  assert.throws(
    () => storeAttachment(dir, "photo.gif", Buffer.from([0xff, 0xd8, 0xff, 0xe0]), "image/gif"),
    (e: unknown) => {
      assert.ok(e instanceof AttachmentError);
      return true;
    },
  );
});

test("accepts minimal real headers for jpeg, gif, and webp", () => {
  
  assert.ok(storeAttachment(dir, "a.jpg", Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]), "image/jpeg").endsWith(".jpg"));
  assert.ok(storeAttachment(dir, "a.gif", Buffer.from("GIF89a\x01\x00\x01\x00"), "image/gif").endsWith(".gif"));
  assert.ok(storeAttachment(dir, "a.webp", Buffer.from("RIFF\x24\x00\x00\x00WEBPVP8 "), "image/webp").endsWith(".webp"));
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
  storeAttachment(dir, "new.png", pngBytes(), "image/png");
  const remaining = await readdir(dir);
  assert.ok(remaining.length <= 200, `expected at most 200 uploads, got ${remaining.length}`);
  assert.ok(remaining.some((name) => name.includes("new.png")), "the new attachment survives pruning");
});
