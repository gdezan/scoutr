import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { listDirs, DirListingError } from "../src/dirs.js";

describe("listDirs", () => {
  let root: string;

  before(() => {
    root = mkdtempSync(join(tmpdir(), "cockpit-dirs-"));
    mkdirSync(join(root, "Dev", "agents-mobile"), { recursive: true });
    mkdirSync(join(root, "Dev", "pi-workflow"), { recursive: true });
    mkdirSync(join(root, ".hidden"), { recursive: true });
    mkdirSync(join(root, "empty-sub"), { recursive: true });
    // a file that must never appear as a dir listing entry
    writeFileSync(join(root, "not-a-dir"), "");
    symlinkSync("/", join(root, "outside-link"), "dir");
  });

  after(() => rmSync(root, { recursive: true, force: true }));

  it("lists immediate subdirectories, sorted, no dotfiles", () => {
    const listing = listDirs(join(root, "Dev"), root);
    assert.deepEqual(listing.dirs, ["agents-mobile", "pi-workflow"]);
  });

  it("resolves the requested path and echoes it", () => {
    const listing = listDirs(join(root, "Dev", "agents-mobile"), root);
    assert.equal(listing.path, join(root, "Dev", "agents-mobile"));
    assert.deepEqual(listing.dirs, []);
  });

  it("rejects paths outside the root", () => {
    assert.throws(() => listDirs("/tmp", root), DirListingError);
    assert.throws(() => listDirs(join(root, "..", "outside"), root), DirListingError);
  });


  it("rejects symlinks that resolve outside the root", () => {
    assert.throws(() => listDirs(join(root, "outside-link"), root), /outside allowed root/);
  });

  it("rejects missing and non-directory paths", () => {
    assert.throws(() => listDirs(join(root, "nope"), root), /no such directory/);
    assert.throws(() => listDirs(join(root, "not-a-dir"), root), /not a directory/);
  });
});
