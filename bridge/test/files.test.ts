import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  FILE_BYTES_MAX_BYTES,
  FileListingError,
  FileReadError,
  listFiles,
  readWorkspaceFile,
  statWorkspaceFile,
  workspaceMimeForPath,
} from "../src/files.js";

function git(cwd: string, ...args: string[]): void {
  execFileSync("git", ["-C", cwd, ...args], { stdio: "ignore" });
}

describe("listFiles", () => {
  let repo: string;
  let plain: string;

  before(() => {
    repo = mkdtempSync(join(tmpdir(), "scoutr-files-repo-"));
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

    plain = mkdtempSync(join(tmpdir(), "scoutr-files-plain-"));
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

  it("includes dot entries only in browser mode and still skips heavy dirs", async () => {
    const listing = await listFiles(plain, true);
    assert.deepEqual(listing.files, [".dotfile", ".hidden/secret.txt", "a/b/deep.txt", "top.txt"]);
    assert.equal(listing.files.some((file) => file.startsWith("build/")), false);
  });

  it("flags a walk that was cut off by the depth cap", async () => {
    const deep = mkdtempSync(join(tmpdir(), "scoutr-files-deep-"));
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

describe("readWorkspaceFile", () => {
  let workspace: string;
  let outside: string;

  before(() => {
    workspace = mkdtempSync(join(tmpdir(), "scoutr-file-read-"));
    outside = mkdtempSync(join(tmpdir(), "scoutr-file-outside-"));
    writeFileSync(join(workspace, "README.md"), "# Hello\n\nThis is a file.\n");
    writeFileSync(join(workspace, "binary.dat"), Buffer.from([0x23, 0x00, 0x61]));
    mkdirSync(join(outside, "nested"));
    writeFileSync(join(outside, "secret.txt"), "do not expose");
    symlinkSync(join(outside, "secret.txt"), join(workspace, "escape.txt"));
    symlinkSync(join(outside, "nested"), join(workspace, "escape-dir"));
  });

  after(() => {
    rmSync(workspace, { recursive: true, force: true });
    rmSync(outside, { recursive: true, force: true });
  });

  it("reads an authorized regular file and reports missing files", () => {
    assert.deepEqual(readWorkspaceFile(join(workspace, "README.md"), [workspace]), {
      content: "# Hello\n\nThis is a file.\n",
      truncated: false,
      binary: false,
      exists: true,
      sizeBytes: 25,
      mime: "text/markdown; charset=utf-8",
    });
    assert.equal(readWorkspaceFile(join(workspace, "missing.md"), [workspace]).exists, false);
  });

  it("rejects lexical and symlink escapes before returning file contents", () => {
    assert.throws(() => readWorkspaceFile(join(outside, "secret.txt"), [workspace]), (error) => {
      assert.ok(error instanceof FileReadError);
      assert.equal(error.status, 403);
      return true;
    });
    assert.throws(() => readWorkspaceFile(join(workspace, "escape.txt"), [workspace]), (error) => {
      assert.ok(error instanceof FileReadError);
      assert.equal(error.status, 403);
      return true;
    });
    assert.throws(() => readWorkspaceFile(join(workspace, "escape-dir", "missing.md"), [workspace]), (error) => {
      assert.ok(error instanceof FileReadError);
      assert.equal(error.status, 403);
      return true;
    });
  });

  it("marks binary files without returning their contents", () => {
    assert.deepEqual(readWorkspaceFile(join(workspace, "binary.dat"), [workspace]), {
      content: "",
      truncated: false,
      binary: true,
      exists: true,
      sizeBytes: 3,
      mime: "application/octet-stream",
    });
    const paged = readWorkspaceFile(join(workspace, "binary.dat"), [workspace], { offset: 0, limit: 2 });
    assert.equal(paged.binary, true);
    assert.equal(paged.offset, 0);
    assert.equal(paged.nextOffset, null);
  });

  it("returns non-files as missing and caps regular file heads", () => {
    assert.equal(readWorkspaceFile(workspace, [workspace]).exists, false);
    const large = join(workspace, "large.txt");
    writeFileSync(large, "a".repeat(256 * 1024 + 17));
    const result = readWorkspaceFile(large, [workspace]);
    assert.equal(result.exists, true);
    assert.equal(result.binary, false);
    assert.equal(result.truncated, true);
    assert.equal(Buffer.byteLength(result.content, "utf8"), 256 * 1024);

    const page = readWorkspaceFile(large, [workspace], { offset: 256 * 1024, limit: 17 });
    assert.equal(page.content, "a".repeat(17));
    assert.equal(page.offset, 256 * 1024);
    assert.equal(page.nextOffset, null);
    assert.equal(page.totalBytes, 256 * 1024 + 17);
    assert.equal(page.truncated, false);
  });
  it("keeps UTF-8 characters intact across page boundaries", () => {
    const unicode = join(workspace, "unicode.txt");
    const expected = "a".repeat(253) + "\uFFFD" + "🙂" + "z".repeat(257);
    writeFileSync(unicode, expected);

    const pages: string[] = [];
    let offset = 0;
    while (true) {
      const page = readWorkspaceFile(unicode, [workspace], { offset, limit: 256 });
      pages.push(page.content);
      if (page.nextOffset === null) break;
      offset = page.nextOffset;
    }

    assert.equal(pages.join(""), expected);
  });

  it("rejects invalid paths", () => {
    assert.throws(() => readWorkspaceFile("relative.txt", [workspace]), (error) => {
      assert.ok(error instanceof FileReadError);
      assert.equal(error.status, 400);
      return true;
    });
    assert.throws(() => readWorkspaceFile(join(workspace, "bad\u0000name"), [workspace]), FileReadError);
  });
});

describe("statWorkspaceFile", () => {
  let workspace: string;
  let outside: string;

  before(() => {
    workspace = mkdtempSync(join(tmpdir(), "scoutr-file-stat-"));
    outside = mkdtempSync(join(tmpdir(), "scoutr-file-stat-outside-"));
    writeFileSync(join(workspace, "report.html"), "<html></html>");
    writeFileSync(join(workspace, "shot.png"), Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
    mkdirSync(join(workspace, "sub"));
    writeFileSync(join(outside, "secret.txt"), "do not expose");
    symlinkSync(join(outside, "secret.txt"), join(workspace, "escape.txt"));
  });

  after(() => {
    rmSync(workspace, { recursive: true, force: true });
    rmSync(outside, { recursive: true, force: true });
  });

  it("stats an authorized file with its download name and mime", () => {
    const stat = statWorkspaceFile(join(workspace, "report.html"), [workspace]);
    assert.equal(stat.filename, "report.html");
    assert.equal(stat.mime, "text/html; charset=utf-8");
    assert.equal(stat.sizeBytes, 13);
    assert.equal(stat.path, join(workspace, "report.html"));
  });

  it("rejects invalid and relative paths before touching the filesystem", () => {
    for (const bad of ["relative.txt", "", join(workspace, "bad\u0000name")]) {
      assert.throws(() => statWorkspaceFile(bad, [workspace]), (error) => {
        assert.ok(error instanceof FileReadError);
        assert.equal(error.status, 400);
        return true;
      });
    }
  });

  it("rejects lexical and symlink escapes with 403, even for missing paths", () => {
    assert.throws(() => statWorkspaceFile(join(outside, "secret.txt"), [workspace]), (error) => {
      assert.ok(error instanceof FileReadError);
      assert.equal(error.status, 403);
      return true;
    });
    assert.throws(() => statWorkspaceFile(join(workspace, "escape.txt"), [workspace]), (error) => {
      assert.ok(error instanceof FileReadError);
      assert.equal(error.status, 403);
      return true;
    });
    assert.throws(() => statWorkspaceFile(join(workspace, "escape.txt", "missing.md"), [workspace]), (error) => {
      assert.ok(error instanceof FileReadError);
      assert.equal(error.status, 403);
      return true;
    });
  });

  it("reports missing paths and directories as 404", () => {
    for (const missing of [join(workspace, "missing.md"), join(workspace, "sub"), workspace]) {
      assert.throws(() => statWorkspaceFile(missing, [workspace]), (error) => {
        assert.ok(error instanceof FileReadError);
        assert.equal(error.status, 404);
        return true;
      });
    }
  });

  it("reports files past the bytes cap as 413", () => {
    const large = join(workspace, "large.bin");
    writeFileSync(large, Buffer.alloc(FILE_BYTES_MAX_BYTES + 1));
    try {
      assert.throws(() => statWorkspaceFile(large, [workspace]), (error) => {
        assert.ok(error instanceof FileReadError);
        assert.equal(error.status, 413);
        return true;
      });
    } finally {
      rmSync(large, { force: true });
    }
  });

  it("maps viewer extensions and defaults unknown types to bytes", () => {
    assert.equal(workspaceMimeForPath("/w/shot.png"), "image/png");
    assert.equal(workspaceMimeForPath("/w/photo.JPG"), "image/jpeg");
    assert.equal(workspaceMimeForPath("/w/notes.md"), "text/markdown; charset=utf-8");
    assert.equal(workspaceMimeForPath("/w/report.HTML"), "text/html; charset=utf-8");
    assert.equal(workspaceMimeForPath("/w/doc.pdf"), "application/pdf");
    assert.equal(workspaceMimeForPath("/w/no-extension"), "application/octet-stream");
    assert.equal(workspaceMimeForPath("/w/archive.tar.gz"), "application/octet-stream");
  });
});
