import test, { describe } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { symlinkSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { createScoutrServer, type ScoutrServer } from "../src/server.js";
import { fakeHerdr } from "./support/fake-herdr.js";
import { FakeTerminalLauncher } from "./support/fake-terminal.js";
import { REVIEW_ROOTS_TTL_MS } from "../src/routes/review.js";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  reviewOverview,
  reviewDiff,
  reviewFileDiff,
  reviewFileContent,
  ReviewError,
  REVIEW_STATUS_MAX_ENTRIES,
  REVIEW_DIFF_MAX_BYTES,
  REVIEW_DIFF_MAX_LINES,
  REVIEW_FILE_MAX_BYTES,
  capUtf8,
  REVIEW_LOG_MAX,
  reviewArtifacts,
  gitRepoRoot,
} from "../src/review.js";

let repoRoot: string;
let plainRoot: string;
let outsideRoot: string;
let workspaceRoot: string;
let sessionRepo: string;
const originalRoots = process.env.SCOUTR_REPO_ROOTS;

test.before(async () => {
  repoRoot = await mkdtemp(join(tmpdir(), "scoutr-review-repo-"));
  plainRoot = await mkdtemp(join(tmpdir(), "scoutr-review-plain-"));
  outsideRoot = await mkdtemp(join(tmpdir(), "scoutr-review-outside-"));
  process.env.SCOUTR_REPO_ROOTS = `${repoRoot},${plainRoot}`;

  execFileSync("git", ["init", "-q", "-b", "main", repoRoot]);
  execFileSync("git", ["config", "user.email", "test@scoutr.dev"], { cwd: repoRoot });
  execFileSync("git", ["config", "user.name", "Scoutr Test"], { cwd: repoRoot });
  await writeFile(join(repoRoot, "a.txt"), "hello\n");
  execFileSync("git", ["add", "."], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "initial commit"], { cwd: repoRoot });
  await writeFile(join(repoRoot, "a.txt"), "hello\nworld\n");
  await writeFile(join(repoRoot, "b.txt"), "new file\n");
  execFileSync("git", ["add", "."], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "add world and b"], { cwd: repoRoot });

  // A session workspace that lives OUTSIDE SCOUTR_REPO_ROOTS, with the
  // session repo as a subdirectory — the fix-5 shape: the bridge must allow
  // it because an agent is running there, not because it was configured.
  workspaceRoot = await mkdtemp(join(tmpdir(), "scoutr-review-workspace-"));
  sessionRepo = join(workspaceRoot, "repo");
  await mkdir(sessionRepo, { recursive: true });
  execFileSync("git", ["init", "-q", "-b", "main", sessionRepo]);
  execFileSync("git", ["config", "user.email", "test@scoutr.dev"], { cwd: sessionRepo });
  execFileSync("git", ["config", "user.name", "Scoutr Test"], { cwd: sessionRepo });
  await writeFile(join(sessionRepo, "x.txt"), "workspace file\n");
  execFileSync("git", ["add", "."], { cwd: sessionRepo });
  execFileSync("git", ["commit", "-q", "-m", "workspace initial"], { cwd: sessionRepo });
});

test.after(() => {
  if (originalRoots === undefined) delete process.env.SCOUTR_REPO_ROOTS;
  else process.env.SCOUTR_REPO_ROOTS = originalRoots;
});

test("overview reports branch, status, and recent log", async () => {
  await writeFile(join(repoRoot, "c.txt"), "uncommitted\n");
  const overview = await reviewOverview(repoRoot);

  // Clean repo: no upstream, so ahead/behind parse as 0 with the branch name.
  assert.equal(overview.branch, "main");
  assert.equal(overview.ahead, 0);
  assert.equal(overview.behind, 0);
  assert.equal(overview.branch, "main");
  assert.equal(overview.status.length, 1);
  assert.equal(overview.status[0].code, "??");
  assert.equal(overview.status[0].path, "c.txt");
  assert.equal(overview.log.length, 2);
  assert.equal(overview.log[0].subject, "add world and b");
  assert.equal(overview.log[1].subject, "initial commit");
  assert.ok(overview.log[0].hash.length >= 7);
  assert.ok(typeof overview.log[0].date === "number");
  assert.equal(overview.statusTruncated, false);
});

test("diff is stat-only; per-file diff carries the hunks", async () => {
  // Self-contained working-tree change to a tracked file (test order is not
  // guaranteed; untracked files never appear in `git diff`).
  execFileSync("sh", ["-c", "echo draft >> a.txt"], { cwd: repoRoot });
  const result = await reviewDiff(repoRoot, "HEAD");
  assert.equal(result.truncated, false);
  assert.equal("diff" in result, false, "stat-only listing must not carry hunk content");
  const aStat = result.stat.find((s) => s.path === "a.txt");
  assert.ok(aStat, "stat should include a.txt");
  assert.ok((aStat?.additions ?? 0) >= 1, `a.txt should show additions, got ${JSON.stringify(aStat)}`);

  const fileDiff = await reviewFileDiff(repoRoot, "HEAD", "working", "a.txt");
  assert.ok(fileDiff.diff.includes("+draft"), "per-file diff should contain the +draft line");
  assert.equal(fileDiff.truncated, false);
  assert.ok(!fileDiff.diff.includes("b.txt"), "per-file diff must not leak other files");
});

test("per-file diff accepts a hash ref", async () => {
  const overview = await reviewOverview(repoRoot);
  const parentHash = overview.log[1].hash;
  const result = await reviewFileDiff(repoRoot, parentHash, "working", "a.txt");
  assert.ok(result.diff.includes("+world"));
});

test("commit kind diffs the commit against its parent", async () => {
  const overview = await reviewOverview(repoRoot);
  const headHash = overview.log[0]!.hash;
  const result = await reviewFileDiff(repoRoot, headHash, "commit", "b.txt");
  // The second commit added b.txt and +world to a.txt; the working-tree
  // noise (c.txt / d.txt from other tests) must not leak into a commit diff.
  assert.ok(result.diff.includes("+new file"), "commit diff should contain +new file");
  assert.ok(!result.diff.includes("+draft"), "working-tree changes must not leak in");
});

test("stat lists exact un-abbreviated paths and rename targets", async () => {
  // A long path that `git diff --stat` would abbreviate as ".../tail", which
  // would be unusable for the per-file endpoints.
  const rel = "some/really/deeply/nested/directory/that/goes/on/for/a/while/longfilename.txt";
  const longDir = join(repoRoot, "some/really/deeply/nested/directory/that/goes/on/for/a/while");
  await mkdir(longDir, { recursive: true });
  await writeFile(join(repoRoot, rel), "x\n");
  execFileSync("git", ["add", "."], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "add long path"], { cwd: repoRoot });
  await writeFile(join(repoRoot, "rename-me.txt"), "renamed content\n");
  execFileSync("git", ["add", "."], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "add rename source"], { cwd: repoRoot });
  // Working-tree changes: modify the long-path file, rename the other.
  execFileSync("sh", ["-c", "echo extra >> \"$1\"", "sh", join(repoRoot, rel)], { cwd: repoRoot });
  execFileSync("git", ["mv", "rename-me.txt", "renamed-target.txt"], { cwd: repoRoot });

  const result = await reviewDiff(repoRoot, "HEAD");
  const longStat = result.stat.find((s) => s.path === rel);
  assert.ok(longStat, `stat must carry the exact long path, got ${JSON.stringify(result.stat.map((s) => s.path))}`);
  assert.ok(!result.stat.some((s) => s.path.includes("...")), "stat paths must never be abbreviated");
  const renameStat = result.stat.find((s) => s.path === "renamed-target.txt");
  assert.ok(renameStat, "rename rows must list the NEW path");
  assert.ok(!result.stat.some((s) => s.path === "rename-me.txt"), "rename rows must not list the old path");

  // And the exact path must be directly usable by the per-file endpoint.
  const fileDiff = await reviewFileDiff(repoRoot, "HEAD", "working", rel);
  assert.match(fileDiff.diff, /longfilename\.txt/);
});

test("commit kind diffs a root commit against the empty tree", async () => {
  const overview = await reviewOverview(repoRoot);
  const rootHash = overview.log[overview.log.length - 1]!.hash;
  const result = await reviewFileDiff(repoRoot, rootHash, "commit", "a.txt");
  assert.ok(result.diff.includes("+hello"), "root commit should diff against the empty tree");
});

test("oversized per-file diffs truncate instead of failing", async () => {
  await writeFile(join(repoRoot, "big.txt"), "");
  execFileSync("git", ["add", "."], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "add big"], { cwd: repoRoot });
  const lines = Array.from({ length: 9000 }, (_, i) => `line ${i} with some content`);
  await writeFile(join(repoRoot, "big.txt"), lines.join("\n") + "\n");

  const result = await reviewFileDiff(repoRoot, "HEAD", "working", "big.txt");
  assert.equal(result.truncated, true);
  assert.ok(result.diff.length <= REVIEW_DIFF_MAX_BYTES + 3);
});

test("artifacts lists bounded generated files and rejects outside roots", async () => {
  await mkdir(join(repoRoot, "build"));
  await writeFile(join(repoRoot, "build", "app.apk"), "x".repeat(64));
  await writeFile(join(repoRoot, "notes.txt"), "not an artifact\n");

  const result = await reviewArtifacts(repoRoot);
  const paths = result.artifacts.map((a) => a.path);
  assert.ok(paths.some((p) => p.endsWith("build/app.apk")), "build outputs should be listed");
  assert.ok(!paths.some((p) => p.endsWith("notes.txt")), "non-artifact files must not appear");
  const apk = result.artifacts.find((a) => a.path.endsWith("build/app.apk"));
  assert.equal(apk?.size, 64);

  await assert.rejects(async () => reviewArtifacts(outsideRoot), (error: unknown) => {
    assert.ok(error instanceof ReviewError);
    assert.equal(error.status, 403);
    return true;
  });
});

test("rejects paths outside the allow-list", async () => {
  await assert.rejects(() => reviewOverview(outsideRoot), (error: unknown) => {
    assert.ok(error instanceof ReviewError);
    assert.equal(error.status, 403);
    return true;
  });
});

test("allows a live session workspace passed as an extra root", async () => {
  // Same repo that the allow-list rejects, allowed once the bridge passes the
  // agent's workspace cwd as an extra root.
  await assert.rejects(() => reviewOverview(sessionRepo), (error: unknown) => {
    assert.ok(error instanceof ReviewError);
    assert.equal(error.status, 403);
    return true;
  });
  const overview = await reviewOverview(sessionRepo, [workspaceRoot]);
  assert.equal(overview.branch, "main");
  assert.equal(overview.log.length, 1);
});

test("extra roots allow subdirectories of the workspace", async () => {
  // The session repo sits under the workspace root; the realpath check must
  // admit descendants of an extra root just like configured roots.
  const nested = join(sessionRepo, "sub");
  await mkdir(nested, { recursive: true });
  await writeFile(join(nested, "y.txt"), "nested\n");
  execFileSync("git", ["add", "."], { cwd: sessionRepo });
  execFileSync("git", ["commit", "-q", "-m", "nested file"], { cwd: sessionRepo });
  await writeFile(join(nested, "y.txt"), "nested\nedited\n");
  const diff = await reviewFileDiff(nested, "HEAD", "working", "y.txt", [workspaceRoot]);
  assert.match(diff.diff, /y\.txt/);
  assert.match(diff.diff, /nested/);
});

test("403 message names the escape hatch", async () => {
  await assert.rejects(() => reviewOverview(outsideRoot), (error: unknown) => {
    assert.ok(error instanceof ReviewError);
    assert.equal(error.status, 403);
    assert.match(error.message, /SCOUTR_REPO_ROOTS/);
    return true;
  });
});

test("rejects absolute path escapes and garbage refs", async () => {
  await assert.rejects(() => reviewOverview("/etc/passwd"), (error: unknown) => {
    assert.ok(error instanceof ReviewError);
    assert.equal(error.status, 403);
    return true;
  });
  await assert.rejects(() => reviewDiff(repoRoot, "../../etc/passwd"), (error: unknown) => {
    assert.ok(error instanceof ReviewError);
    assert.equal(error.status, 400);
    return true;
  });
  await assert.rejects(() => reviewDiff(repoRoot, "HEAD; rm -rf /"), (error: unknown) => {
    assert.ok(error instanceof ReviewError);
    assert.equal(error.status, 400);
    return true;
  });
});

test("rejects non-git directories", async () => {
  const plain = join(plainRoot, "nonrepo");
  await mkdir(plain);
  await assert.rejects(() => reviewOverview(plain), (error: unknown) => {
    assert.ok(error instanceof ReviewError);
    assert.equal(error.status, 404);
    return true;
  });
});

test("gitRepoRoot resolves a repo subdirectory up to its repo root", async () => {
  const nested = join(sessionRepo, "src", "deep");
  await mkdir(nested, { recursive: true });
  assert.equal(await gitRepoRoot(nested), sessionRepo);
  assert.equal(await gitRepoRoot(sessionRepo), sessionRepo);
});

test("gitRepoRoot returns null for a non-repo directory", async () => {
  // A cwd like $HOME or a scratch dir is not inside any repository, so it
  // contributes no implicit review root (fix 5 least-privilege narrowing).
  assert.equal(await gitRepoRoot(workspaceRoot), null);
});

test("binary and rename changes pass through the diff without errors", async () => {
  // Binary: a committed then modified binary shows "Binary files differ".
  await writeFile(join(repoRoot, "blob.bin"), Buffer.from([0, 1, 2, 3, 255]));
  execFileSync("git", ["add", "blob.bin"], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "add binary"], { cwd: repoRoot });
  await writeFile(join(repoRoot, "blob.bin"), Buffer.from([9, 9, 9]));
  const binary = await reviewFileDiff(repoRoot, "HEAD", "working", "blob.bin");
  assert.ok(binary.diff.includes("Binary files"), "binary diff should pass through");
  // Rename: porcelain status should surface the R code.
  execFileSync("git", ["mv", "blob.bin", "renamed.bin"], { cwd: repoRoot });
  const overview = await reviewOverview(repoRoot);
  const rename = overview.status.find((e) => e.code.startsWith("R"));
  assert.ok(rename, "rename should appear in status");
});

test("caps bound status entries, log size, and per-file diff bytes", async () => {
  assert.ok(REVIEW_STATUS_MAX_ENTRIES >= 1);
  assert.ok(REVIEW_LOG_MAX >= 1);
  // Line cap: ~900 added lines on a tracked file exceed REVIEW_DIFF_MAX_LINES.
  const manyLines = Array.from({ length: 900 }, (_, i) => `line ${i}`).join("\n") + "\n";
  await writeFile(join(repoRoot, "a.txt"), "hello\nworld\n" + manyLines);
  const lineCapped = await reviewFileDiff(repoRoot, "HEAD", "working", "a.txt");
  assert.equal(lineCapped.truncated, true);
  assert.ok(lineCapped.diff.split("\n").length <= REVIEW_DIFF_MAX_LINES + 2);

  // Byte cap: ~8000 added lines exceed REVIEW_DIFF_MAX_BYTES.
  const manyBytes = Array.from({ length: 8000 }, (_, i) => `const x${i} = ${i};`).join("\n") + "\n";
  await writeFile(join(repoRoot, "b.txt"), "new file\n" + manyBytes);
  const byteCapped = await reviewFileDiff(repoRoot, "HEAD", "working", "b.txt");
  assert.equal(byteCapped.truncated, true);
  assert.ok(Buffer.byteLength(byteCapped.diff, "utf8") <= REVIEW_DIFF_MAX_BYTES);
});

test("capUtf8 cuts exactly at the byte cap and drops a straddling code point", () => {
  const ascii = "a".repeat(1025);
  const cut = capUtf8(ascii, 1024);
  assert.equal(Buffer.byteLength(cut.text, "utf8"), 1024);
  assert.equal(cut.truncated, true);

  // A 3-byte char straddling the cut must not leave a half code point.
  const straddle = "a".repeat(1022) + "€"; // 1022 + 3 = 1025 bytes
  const cutStraddle = capUtf8(straddle, 1024);
  assert.equal(Buffer.byteLength(cutStraddle.text, "utf8"), 1022);
  assert.ok(!cutStraddle.text.endsWith("\uFFFD"), "no replacement char in the output");
});

test("overview log carries the message body", async () => {
  // --allow-empty keeps the noisy working tree (from the caps test) out of the commit.
  execFileSync(
    "git",
    ["commit", "-q", "--allow-empty", "-m", "subject with body test", "-m", "body line one", "-m", "body line two"],
    { cwd: repoRoot },
  );
  const overview = await reviewOverview(repoRoot);
  const head = overview.log[0]!;
  assert.equal(head.subject, "subject with body test");
  // Each -m becomes a paragraph; %b keeps the blank separator lines.
  assert.equal(head.body, "body line one\n\nbody line two");
  assert.ok(Buffer.byteLength(head.body, "utf8") <= 2048);
});

test("file content reads the final working-tree version", async () => {
  await writeFile(join(repoRoot, "final.txt"), "line one\nline two\n");
  const result = await reviewFileContent(repoRoot, "HEAD", "working", "final.txt");
  assert.equal(result.exists, true);
  assert.equal(result.content, "line one\nline two\n");
  assert.equal(result.truncated, false);
  assert.equal(result.binary, false);
});

test("file content reports a missing working-tree file", async () => {
  const result = await reviewFileContent(repoRoot, "HEAD", "working", "missing.txt");
  assert.equal(result.exists, false);
  assert.equal(result.content, "");
});

test("file content at a commit reads the committed version and reports deletions", async () => {
  await writeFile(join(repoRoot, "gone.txt"), "will be removed\n");
  // final.txt exists on disk from the previous test; stage it here so it
  // is part of HEAD for the commit-kind read below.
  execFileSync("git", ["add", "gone.txt", "final.txt"], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "add gone and final"], { cwd: repoRoot });
  execFileSync("git", ["rm", "-q", "gone.txt"], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "remove gone"], { cwd: repoRoot });

  const present = await reviewFileContent(repoRoot, "HEAD", "commit", "final.txt");
  assert.equal(present.exists, true);

  // gone.txt was removed by HEAD; the tree at HEAD has no such path.
  const deleted = await reviewFileContent(repoRoot, "HEAD", "commit", "gone.txt");
  assert.equal(deleted.exists, false, "file removed by HEAD must not exist at HEAD");
});

test("file content flags binary files", async () => {
  const bytes = Buffer.from([0, 1, 2, 3, 255, 65, 66, 67]);
  await writeFile(join(repoRoot, "data.bin"), bytes);
  execFileSync("git", ["add", "data.bin"], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "add data"], { cwd: repoRoot });

  const committed = await reviewFileContent(repoRoot, "HEAD", "commit", "data.bin");
  assert.equal(committed.binary, true, "committed binary must be flagged");
  assert.equal(committed.content, "");

  await writeFile(join(repoRoot, "data.bin"), Buffer.from([9, 9, 0, 9, 9]));
  const working = await reviewFileContent(repoRoot, "HEAD", "working", "data.bin");
  assert.equal(working.binary, true, "working-tree binary must be flagged");
});

test("file content truncates at the byte cap", async () => {
  // Untracked files are readable for the working-tree kind; the cap is
  // enforced on the read, not on git.
  await writeFile(join(repoRoot, "huge.txt"), "x".repeat(REVIEW_FILE_MAX_BYTES + 5000));
  const result = await reviewFileContent(repoRoot, "HEAD", "working", "huge.txt");
  assert.equal(result.truncated, true);
  assert.equal(result.content.length, REVIEW_FILE_MAX_BYTES);
});

test("file content rejects a symlink escaping the repo", async () => {
  await writeFile(join(outsideRoot, "target.txt"), "secret\n");
  symlinkSync(join(outsideRoot, "target.txt"), join(repoRoot, "escape.txt"));
  await assert.rejects(() => reviewFileContent(repoRoot, "HEAD", "working", "escape.txt"), (error: unknown) => {
    assert.ok(error instanceof ReviewError);
    assert.equal(error.status, 403);
    return true;
  });
});

test("per-file routes reject pathspec magic and path escapes", async () => {
  for (const bad of ["", "../etc/passwd", ":glob", "!excluded", "^top", "a\nb", "/abs"]) {
    await assert.rejects(() => reviewFileDiff(repoRoot, "HEAD", "working", bad), (error: unknown) => {
      assert.ok(error instanceof ReviewError, `expected ReviewError for ${JSON.stringify(bad)}`);
      assert.equal(error.status, 400);
      return true;
    });
  }
});

test("per-file diff of an unknown path yields an empty diff", async () => {
  const result = await reviewFileDiff(repoRoot, "HEAD", "working", "does-not-exist.txt");
  assert.equal(result.diff, "");
  assert.equal(result.truncated, false);
});

describe("review roots TTL", () => {
  const PORT = 8793;
  const TOKEN = "test_token_for_review_ttl_0001";
  let server: ScoutrServer;
  let sessionRoot: string;
  let repoDir: string;

  test.before(async () => {
    // A real repo outside SCOUTR_REPO_ROOTS, allowed only through the
    // implicit roots derived from a session workspace.
    repoDir = await mkdtemp(join(tmpdir(), "scoutr-review-ttl-repo-"));
    execFileSync("git", ["init", "-q", "-b", "main", repoDir]);
    execFileSync("git", ["config", "user.email", "test@scoutr.dev"], { cwd: repoDir });
    execFileSync("git", ["config", "user.name", "Scoutr Test"], { cwd: repoDir });
    await writeFile(join(repoDir, "a.txt"), "hello\n");
    execFileSync("git", ["add", "."], { cwd: repoDir });
    execFileSync("git", ["commit", "-q", "-m", "initial"], { cwd: repoDir });

    sessionRoot = await mkdtemp(join(tmpdir(), "scoutr-review-ttl-sessions-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = sessionRoot;
    process.env.CLAUDECONFIGDIR = await mkdtemp(join(tmpdir(), "scoutr-review-ttl-claude-"));
    const project = join(sessionRoot, "project");
    await mkdir(project, { recursive: true });
    await writeFile(
      join(project, "session.jsonl"),
      `${JSON.stringify({ type: "session", version: 3, id: "ttl-session", timestamp: "2026-01-01T00:00:00.000Z", cwd: repoDir })}\n`,
    );

    const fake = fakeHerdr();
    const feed = { onMessage: () => {}, removeMessage: () => {}, stop: async () => {}, start: async () => {} };
    server = createScoutrServer(
      {
        herdr: fake,
        feed: feed as never,
        usage: { all: async () => ({}) } as never,
        config: { configDir: "/tmp/scoutr-test-config", token: TOKEN, port: PORT },
        terminal: new FakeTerminalLauncher(),
      },
      { listen: true },
    );
  });

  test.after(async () => {
    await server.close();
    await rm(sessionRoot, { recursive: true, force: true });
  });

  async function repoGet(path: string): Promise<{ status: number; data: any }> {
    const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    return { status: response.status, data: await response.json() };
  }

  test("cached roots survive a workspace removal within the TTL and expire after it", async () => {
  test("concurrent review requests share the roots computation", async () => {
    // The Review screen's open burst (overview + artifacts + diff): all three
    // requests can arrive while the first is still computing. The in-flight
    // coalescing must share one catalog scan across the burst, and every
    // request must see the same result.
    const [a, b, c] = await Promise.all([
      repoGet(`/api/repo?path=${encodeURIComponent(repoDir)}`),
      repoGet(`/api/repo?path=${encodeURIComponent(repoDir)}`),
      repoGet(`/api/repo?path=${encodeURIComponent(repoDir)}`),
    ]);
    assert.equal(a.status, 200, JSON.stringify(a.data));
    assert.equal(b.status, 200, JSON.stringify(b.data));
    assert.equal(c.status, 200, JSON.stringify(c.data));
  });

    // First call derives the implicit root from the catalog (one scan).
    const first = await repoGet(`/api/repo?path=${encodeURIComponent(repoDir)}`);
    assert.equal(first.status, 200, "repo must be reviewable while the session exists");

    // Remove the only catalog evidence of the workspace; the cached root set
    // must still allow the repo within the TTL window.
    await rm(join(sessionRoot, "project", "session.jsonl"));
    const withinTtl = await repoGet(`/api/repo?path=${encodeURIComponent(repoDir)}`);
    assert.equal(withinTtl.status, 200, "cached roots must stay effective inside the TTL");

    // Once the TTL passes, the allow-list must be rebuilt without the
    // removed workspace.
    await new Promise((resolve) => setTimeout(resolve, REVIEW_ROOTS_TTL_MS + 250));
    const expired = await repoGet(`/api/repo?path=${encodeURIComponent(repoDir)}`);
    assert.equal(expired.status, 403, "expired TTL must drop the removed workspace");
  });
});

describe("review file routes over HTTP", () => {
  const PORT = 8794;
  const TOKEN = "test_token_for_review_file_routes_0001";
  let server: ScoutrServer;
  let repoDir: string;

  test.before(async () => {
    // plainRoot is a configured allow-list root, so this repo stays
    // reviewable without implicit session roots.
    repoDir = await mkdtemp(join(plainRoot, "wired-"));
    execFileSync("git", ["init", "-q", "-b", "main", repoDir]);
    execFileSync("git", ["config", "user.email", "test@scoutr.dev"], { cwd: repoDir });
    execFileSync("git", ["config", "user.name", "Scoutr Test"], { cwd: repoDir });
    await writeFile(join(repoDir, "route.txt"), "base\n");
    await writeFile(join(repoDir, "gone.txt"), "will be removed\n");
    execFileSync("git", ["add", "."], { cwd: repoDir });
    execFileSync("git", ["commit", "-q", "-m", "route base"], { cwd: repoDir });
    execFileSync("git", ["rm", "-q", "gone.txt"], { cwd: repoDir });
    execFileSync("git", ["commit", "-q", "-m", "remove gone"], { cwd: repoDir });
    await writeFile(join(repoDir, "route.txt"), "base\nchanged\n");

    const fake = fakeHerdr();
    const feed = { onMessage: () => {}, removeMessage: () => {}, stop: async () => {}, start: async () => {} };
    server = createScoutrServer(
      {
        herdr: fake,
        feed: feed as never,
        usage: { all: async () => ({}) } as never,
        config: { configDir: "/tmp/scoutr-test-config", token: TOKEN, port: PORT },
        terminal: new FakeTerminalLauncher(),
      },
      { listen: true },
    );
  });

  test.after(async () => {
    await server.close();
    await rm(repoDir, { recursive: true, force: true });
  });

  async function repoGet(path: string): Promise<{ status: number; data: any }> {
    const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    return { status: response.status, data: await response.json() };
  }

  test("stat-only diff, per-file diff, and file content wire up", async () => {
    const diff = await repoGet(`/api/repo/diff?path=${encodeURIComponent(repoDir)}&base=HEAD&kind=working`);
    assert.equal(diff.status, 200, JSON.stringify(diff.data));
    assert.equal("diff" in diff.data, false, "diff route must be stat-only");
    assert.ok(diff.data.stat.some((s: any) => s.path === "route.txt"), "stat should list route.txt");

    const fileDiff = await repoGet(
      `/api/repo/diff/file?path=${encodeURIComponent(repoDir)}&base=HEAD&kind=working&file=route.txt`,
    );
    assert.equal(fileDiff.status, 200, JSON.stringify(fileDiff.data));
    assert.ok(fileDiff.data.diff.includes("+changed"), "per-file diff should contain the hunk");

    const file = await repoGet(`/api/repo/file?path=${encodeURIComponent(repoDir)}&base=HEAD&kind=working&file=route.txt`);
    assert.equal(file.status, 200, JSON.stringify(file.data));
    assert.equal(file.data.content, "base\nchanged\n");
    assert.equal(file.data.exists, true);
  });

  test("file content at a commit reads the committed version and reports deletions", async () => {
    const file = await repoGet(`/api/repo/file?path=${encodeURIComponent(repoDir)}&base=HEAD&kind=commit&file=route.txt`);
    assert.equal(file.status, 200, JSON.stringify(file.data));
    assert.equal(file.data.content, "base\n", "must read the committed version, not the working tree");

    const gone = await repoGet(`/api/repo/file?path=${encodeURIComponent(repoDir)}&base=HEAD&kind=commit&file=gone.txt`);
    assert.equal(gone.status, 200, JSON.stringify(gone.data));
    assert.equal(gone.data.exists, false, "file removed by HEAD must not exist at HEAD");
  });

  test("file routes reject invalid file paths", async () => {
    const bad = await repoGet(
      `/api/repo/file?path=${encodeURIComponent(repoDir)}&base=HEAD&kind=working&file=${encodeURIComponent("../escape")}`,
    );
    assert.equal(bad.status, 400);
  });
});
