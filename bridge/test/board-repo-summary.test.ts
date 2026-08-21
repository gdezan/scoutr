import test, { describe } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtemp, rm, writeFile, mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BoardRepoSummaryCache, deriveRepoSummary } from "../src/board-repo-summary.js";

interface Repo {
  path: string;
  git: (...args: string[]) => void;
  write: (name: string, content: string) => Promise<void>;
}

async function initRepo(name: string): Promise<Repo> {
  const path = await mkdtemp(join(tmpdir(), `scoutr-board-summary-${name}-`));
  const git = (...args: string[]) =>
    execFileSync("git", ["-C", path, ...args], { stdio: "pipe" });
  git("init", "-q", "-b", "main");
  git("config", "user.email", "test@scoutr.dev");
  git("config", "user.name", "Scoutr Test");
  return {
    path,
    git,
    write: (fileName, content) => writeFile(join(path, fileName), content),
  };
}

/** A fake clock the tests advance explicitly, so TTL behavior is exact. */
function fakeClock() {
  let now = 1_000_000;
  return {
    now: () => now,
    advance: (ms: number) => {
      now += ms;
    },
  };
}

describe("deriveRepoSummary", () => {
  test("counts the union of status and diff paths, including renames", () => {
    const summary = deriveRepoSummary(
      "/repo",
      {
        path: "/repo",
        root: "/repo",
        branch: "main",
        upstream: "origin/main",
        ahead: 2,
        behind: 1,
        statusTruncated: false,
        log: [],
        logTruncated: false,
        status: [
          { code: " M", path: "src/a.ts" },
          { code: "R ", path: "old.ts -> new.ts" },
          { code: "??", path: "notes.txt" },
        ],
      },
      {
        truncated: true,
        stat: [
          { path: "src/a.ts", additions: 10, deletions: 3 },
          { path: "new.ts", additions: 5, deletions: 5 },
        ],
      },
    );
    assert.equal(summary.changedFiles, 4); // a.ts, old+new rename pair, notes.txt
    assert.equal(summary.additions, 15);
    assert.equal(summary.deletions, 8);
    assert.equal(summary.dirty, true);
    assert.equal(summary.statusTruncated, false);
    assert.equal(summary.diffTruncated, true);
    assert.equal(summary.branch, "main");
    assert.equal(summary.ahead, 2);
    assert.equal(summary.behind, 1);
  });
});

describe("BoardRepoSummaryCache", () => {
  test("clean tracked repo: not dirty, zero changes, branch and tracking known", async () => {
    const repo = await initRepo("clean");
    try {
      await repo.write("a.txt", "hello\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "initial");

      const cache = new BoardRepoSummaryCache();
      const summary = await cache.summaryFor(repo.path);
      assert.ok(summary);
      assert.equal(summary.repoRoot, repo.path);
      assert.equal(summary.branch, "main");
      assert.equal(summary.dirty, false);
      assert.equal(summary.changedFiles, 0);
      assert.equal(summary.additions, 0);
      assert.equal(summary.deletions, 0);
    } finally {
      await rm(repo.path, { recursive: true, force: true });
    }
  });

  test("tracked edits plus untracked files are reflected honestly", async () => {
    const repo = await initRepo("dirty");
    try {
      await repo.write("a.txt", "one\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "initial");
      await repo.write("a.txt", "one\ntwo\nthree\n");
      await repo.write("untracked.txt", "new\n");

      const cache = new BoardRepoSummaryCache();
      const summary = await cache.summaryFor(repo.path);
      assert.ok(summary);
      assert.equal(summary.dirty, true);
      // Union rule: the untracked file must count even though it is absent
      // from the working-tree diff stat.
      assert.equal(summary.changedFiles, 2);
      assert.equal(summary.additions, 2);
      assert.equal(summary.deletions, 0);
      assert.equal(summary.branch, "main");
    } finally {
      await rm(repo.path, { recursive: true, force: true });
    }
  });

  test("a repository with no commits yet keeps its status evidence", async () => {
    const repo = await initRepo("unborn");
    try {
      await repo.write("draft.txt", "uncommitted\n");

      const cache = new BoardRepoSummaryCache();
      const summary = await cache.summaryFor(repo.path);
      assert.ok(summary, "unborn HEAD must not erase valid status evidence");
      assert.equal(summary.dirty, true);
      assert.equal(summary.changedFiles, 1);
      assert.equal(summary.branch, null);
      assert.equal(summary.upstream, null);
    } finally {
      await rm(repo.path, { recursive: true, force: true });
    }
  });

  test("a filename containing ' -> ' is one changed file, not two", async () => {
    const repo = await initRepo("arrowname");
    try {
      await repo.write("notes -> draft.md", "content\n");

      const cache = new BoardRepoSummaryCache();
      const summary = await cache.summaryFor(repo.path);
      assert.ok(summary);
      assert.equal(summary.changedFiles, 1);
      assert.equal(summary.dirty, true);
    } finally {
      await rm(repo.path, { recursive: true, force: true });
    }
  });

  test("ahead/behind come from the upstream tracking branch", async () => {
    const repo = await initRepo("tracking");
    // A bare remote accepts pushes to its checked-out branch.
    const upstreamPath = await mkdtemp(join(tmpdir(), "scoutr-board-summary-tracking-origin-"));
    execFileSync("git", ["init", "-q", "--bare", "-b", "main", upstreamPath]);
    const upstream = { path: upstreamPath };
    try {
      await repo.write("a.txt", "hello\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "initial");
      repo.git("remote", "add", "origin", upstream.path);
      repo.git("push", "-q", "-u", "origin", "main");
      // Commit B is published (the remote's tip), then the local branch
      // rewinds to A and commits C instead: ahead 1, behind 1.
      await repo.write("b.txt", "remote work\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "remote commit");
      repo.git("push", "-q", "origin", "main");
      repo.git("reset", "-q", "--hard", "HEAD~1");
      await repo.write("c.txt", "local work\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "local commit");

      const cache = new BoardRepoSummaryCache();
      const summary = await cache.summaryFor(repo.path);
      assert.ok(summary);
      assert.equal(summary.upstream, "origin/main");
      assert.equal(summary.ahead, 1);
      assert.equal(summary.behind, 1);
    } finally {
      await rm(repo.path, { recursive: true, force: true });
      await rm(upstream.path, { recursive: true, force: true });
    }
  });

  test("no upstream: tracking fields exist but stay zeroed for the UI to omit", async () => {
    const repo = await initRepo("noupstream");
    try {
      await repo.write("a.txt", "hello\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "initial");

      const cache = new BoardRepoSummaryCache();
      const summary = await cache.summaryFor(repo.path);
      assert.ok(summary);
      assert.equal(summary.upstream, null);
      assert.equal(summary.ahead, 0);
      assert.equal(summary.behind, 0);
    } finally {
      await rm(repo.path, { recursive: true, force: true });
    }
  });

  test("non-repo cwd returns null", async () => {
    const plain = await mkdtemp(join(tmpdir(), "scoutr-board-summary-plain-"));
    try {
      const cache = new BoardRepoSummaryCache();
      assert.equal(await cache.summaryFor(plain), null);
      assert.equal(await cache.summaryFor(""), null);
      assert.equal(await cache.summaryFor("/definitely/not/there"), null);
    } finally {
      await rm(plain, { recursive: true, force: true });
    }
  });

  test("summaries are cached within the TTL and recomputed after it expires", async () => {
    const repo = await initRepo("ttl");
    const clock = fakeClock();
    try {
      await repo.write("a.txt", "v1\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "initial");

      const cache = new BoardRepoSummaryCache({ now: clock.now });
      const first = await cache.summaryFor(repo.path);
      assert.ok(first);
      assert.equal(first.additions, 0);

      // The repo changes underneath; the TTL window still serves the snapshot.
      await repo.write("a.txt", "v1\nv2\n");
      const second = await cache.summaryFor(repo.path);
      assert.equal(second, first);

      clock.advance(8_000);
      const third = await cache.summaryFor(repo.path);
      assert.ok(third);
      assert.notEqual(third, first);
      assert.equal(third.additions, 1);
      assert.equal(third.dirty, true);
    } finally {
      await rm(repo.path, { recursive: true, force: true });
    }
  });

  test("two session cwds in one repo share a single cache entry", async () => {
    const repo = await initRepo("shared");
    try {
      await repo.write("a.txt", "hello\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "initial");
      const subdir = join(repo.path, "packages", "app");
      await mkdir(subdir, { recursive: true });

      const cache = new BoardRepoSummaryCache();
      const [fromRoot, fromSubdir] = await Promise.all([
        cache.summaryFor(repo.path),
        cache.summaryFor(subdir),
      ]);
      assert.ok(fromRoot);
      assert.ok(fromSubdir);
      assert.equal(fromSubdir.repoRoot, repo.path);
      assert.equal(cache.size, 1);
    } finally {
      await rm(repo.path, { recursive: true, force: true });
    }
  });

  test("concurrent callers for the same repo share one in-flight computation", async () => {
    const repo = await initRepo("inflight");
    const clock = fakeClock();
    try {
      await repo.write("a.txt", "hello\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "initial");

      let computations = 0;
      const cache = new BoardRepoSummaryCache({ now: clock.now });
      // Wrap summarize indirectly by racing many callers at once; the shared
      // in-flight promise means they all receive the identical object.
      const results = await Promise.all(
        Array.from({ length: 8 }, () => cache.summaryFor(repo.path)),
      );
      computations = new Set(results).size;
      assert.equal(computations, 1);
      assert.equal(cache.size, 1);
    } finally {
      await rm(repo.path, { recursive: true, force: true });
    }
  });

  test("a repo that disappears degrades to null on the next TTL window", async () => {
    const repo = await initRepo("vanish");
    const clock = fakeClock();
    try {
      await repo.write("a.txt", "hello\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "initial");

      const cache = new BoardRepoSummaryCache({ now: clock.now });
      assert.ok(await cache.summaryFor(repo.path));

      clock.advance(8_000);
      await rm(repo.path, { recursive: true, force: true });
      assert.equal(await cache.summaryFor(repo.path), null);
    } finally {
      await rm(repo.path, { recursive: true, force: true });
    }
  });

  test("prune drops entries whose root is no longer live", async () => {
    const repo = await initRepo("prune");
    try {
      await repo.write("a.txt", "hello\n");
      repo.git("add", ".");
      repo.git("commit", "-q", "-m", "initial");

      const cache = new BoardRepoSummaryCache();
      assert.ok(await cache.summaryFor(repo.path));
      assert.equal(cache.size, 1);
      cache.prune(new Set([repo.path]));
      assert.equal(cache.size, 1);
      cache.prune(new Set());
      assert.equal(cache.size, 0);
    } finally {
      await rm(repo.path, { recursive: true, force: true });
    }
  });
});
