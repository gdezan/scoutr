import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, copyFile, rm } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

// scripts/version.mjs lives two levels above bridge/test/. It runs every git
// command with `-C` on its own directory, so copying it into a temp checkout's
// scripts/ directory makes it operate on that checkout — a self-contained way
// to exercise the real script without touching the scoutr working tree.
const VERSION_SCRIPT = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "scripts", "version.mjs");

async function makeRepo(): Promise<string> {
  const repo = await mkdtemp(join(tmpdir(), "scoutr-version-"));
  execFileSync("git", ["init", "-q", "-b", "main", repo]);
  execFileSync("git", ["config", "user.email", "test@scoutr.dev"], { cwd: repo });
  execFileSync("git", ["config", "user.name", "Scoutr Test"], { cwd: repo });
  await mkdir(join(repo, "scripts"), { recursive: true });
  await copyFile(VERSION_SCRIPT, join(repo, "scripts", "version.mjs"));
  return repo;
}

function commit(repo: string, message: string): void {
  execFileSync("git", ["-C", repo, "commit", "--allow-empty", "-q", "-m", message]);
}

function versionKey(repo: string, key: string): string {
  return execFileSync(process.execPath, [join(repo, "scripts", "version.mjs"), "--key", key], {
    encoding: "utf8",
  }).trim();
}

// Every scenario starts from a tagged v0.1.0 baseline and commits on top, so
// each test proves exactly one bump rule rather than chaining them.
async function fromTaggedBaseline(fn: (repo: string) => void | Promise<void>): Promise<void> {
  const repo = await makeRepo();
  try {
    commit(repo, "chore: initial");
    execFileSync("git", ["-C", repo, "tag", "v0.1.0"]);
    await fn(repo);
  } finally {
    await rm(repo, { recursive: true, force: true });
  }
}

test("a feat commit bumps minor", async () => {
  await fromTaggedBaseline((repo) => {
    commit(repo, "feat(app): add update button");
    assert.equal(versionKey(repo, "version"), "0.2.0");
  });
});

test("a fix commit bumps patch", async () => {
  await fromTaggedBaseline((repo) => {
    commit(repo, "fix: stop the crash");
    assert.equal(versionKey(repo, "version"), "0.1.1");
  });
});

test("a non-conventional subject bumps patch", async () => {
  await fromTaggedBaseline((repo) => {
    commit(repo, "tidy up the wiring");
    assert.equal(versionKey(repo, "version"), "0.1.1");
  });
});

test("a feat! subject bumps major", async () => {
  await fromTaggedBaseline((repo) => {
    commit(repo, "feat!: rework pairing protocol");
    assert.equal(versionKey(repo, "version"), "1.0.0");
  });
});

test("a BREAKING CHANGE footer bumps major", async () => {
  await fromTaggedBaseline((repo) => {
    commit(repo, "feat: add a capability\n\nBREAKING CHANGE: the API shape changes");
    assert.equal(versionKey(repo, "version"), "1.0.0");
  });
});

test("the strongest bump since the tag wins, not a running tally", async () => {
  await fromTaggedBaseline((repo) => {
    commit(repo, "fix: stop the crash");
    commit(repo, "feat: add a thing");
    commit(repo, "fix: another crash");
    // feat (minor) dominates both patch fixes -> 0.2.0, not 0.1.2.
    assert.equal(versionKey(repo, "version"), "0.2.0");
  });
});

test("version.mjs floors an untagged versionCode to 1", async () => {
  const repo = await makeRepo();
  try {
    commit(repo, "chore: initial");
    assert.equal(versionKey(repo, "version"), "0.0.0");
    // 0.0.0 -> code 0, but Android requires versionCode >= 1.
    assert.equal(versionKey(repo, "versionCode"), "1");
  } finally {
    await rm(repo, { recursive: true, force: true });
  }
});
