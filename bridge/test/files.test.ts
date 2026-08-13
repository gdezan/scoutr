import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { listFiles, FileListingError } from "../src/files.js";

function git(cwd: string, ...args: string[]): void {
  execFileSync("git", ["-C", cwd, ...args], { stdio: "ignore" });
}

describe("listFiles", () => {
  let repo: string;
  let plain: string;

  before(() => {
    repo = mkdtempSync(join(tmpdir(), "cockpit-files-repo-"));
    mkdirSync(join(repo, "src", "ui"), { recursive: true });
    mkdirSync(join(repo, "node_modules", "junk"), { recursive: true });
    writeFileSync(join(repo, "README.md"), "");
    writeFileSync(join(repo, ".gitignore"), "ignored.txt\nnode_modules/\n");
    writeFileSync(join(repo, "ignored.txt"), "");
    writeFileSync(join(repo, "node_modules", "junk", "index.js"), "");
    writeFileSync(join(repo, "src", "tracked.ts"), "");
    writeFileSync(join(repo, "src", "ui", "Screen.tsx"), "");
    git(repo, "init", "-q");
    git(repo, "add", "README.md", ".gitignore", "src/tracked.ts", "src/ui/Screen.tsx");
    // Untracked but not ignored: written after the commit, never staged.
    writeFileSync(join(repo, "src", "brand-new.ts"), "");

    plain = mkdtempSync(join(tmpdir(), "cockpit-files-plain-"));
    mkdirSync(join(plain, "a", "b"), { recursive: true });
    mkdirSync(join(plain, ".hidden"), { recursive: true });
    mkdirSync(join(plain, "build"), { recursive: true });
    writeFileSync(join(plain, "top.txt"), "");
    writeFileSync(join(plain, "a", "b", "deep.txt"), "");
    writeFileSync(join(plain, ".hidden", "secret.txt"), "");
    writeFileSync(join(plain, "build", "output.o"), "");
    writeFileSync(join(plain, ".dotfile"), "");
  });

  after(() => {
    rmSync(repo, { recursive: true, force: true });
    rmSync(plain, { recursive: true, force: true });
  });

  it("lists tracked and untracked-not-ignored files, sorted", async () => {
    const listing = await listFiles(repo);
    assert.deepEqual(listing.files, [
      ".gitignore",
      "README.md",
      "src/brand-new.ts",
      "src/tracked.ts",
      "src/ui/Screen.tsx",
    ]);
    assert.equal(listing.truncated, false);
  });

  it("omits gitignored paths", async () => {
    const listing = await listFiles(repo);
    assert.ok(!listing.files.includes("ignored.txt"));
    assert.ok(!listing.files.some((file) => file.startsWith("node_modules/")));
  });

  it("scopes to a subdirectory and returns paths relative to it", async () => {
    const listing = await listFiles(join(repo, "src"));
    assert.deepEqual(listing.files, ["brand-new.ts", "tracked.ts", "ui/Screen.tsx"]);
    assert.equal(listing.path, join(repo, "src"));
  });

  it("walks a non-repo directory, skipping hidden and heavy dirs", async () => {
    const listing = await listFiles(plain);
    assert.deepEqual(listing.files, ["a/b/deep.txt", "top.txt"]);
    assert.equal(listing.truncated, false);
  });

  it("flags a walk that was cut off by the depth cap", async () => {
    const deep = mkdtempSync(join(tmpdir(), "cockpit-files-deep-"));
    try {
      // One level past MAX_WALK_DEPTH, so the leaf file is never listed.
      const nested = join(deep, "a", "b", "c", "d", "e", "f", "g");
      mkdirSync(nested, { recursive: true });
      writeFileSync(join(nested, "buried.txt"), "");
      writeFileSync(join(deep, "shallow.txt"), "");
      const listing = await listFiles(deep);
      assert.deepEqual(listing.files, ["shallow.txt"]);
      assert.equal(listing.truncated, true);
    } finally {
      rmSync(deep, { recursive: true, force: true });
    }
  });

  it("rejects missing and non-directory paths", async () => {
    await assert.rejects(() => listFiles(join(plain, "nope")), FileListingError);
    await assert.rejects(() => listFiles(join(plain, "top.txt")), /not a directory/);
  });
});
