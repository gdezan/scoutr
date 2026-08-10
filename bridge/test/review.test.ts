import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  reviewOverview,
  reviewDiff,
  ReviewError,
  REVIEW_STATUS_MAX_ENTRIES,
  REVIEW_DIFF_MAX_BYTES,
  REVIEW_LOG_MAX,
  reviewArtifacts,
  gitRepoRoot,
} from "../src/review.js";

let repoRoot: string;
let plainRoot: string;
let outsideRoot: string;
let workspaceRoot: string;
let sessionRepo: string;
const originalRoots = process.env.COCKPIT_REPO_ROOTS;

test.before(async () => {
  repoRoot = await mkdtemp(join(tmpdir(), "cockpit-review-repo-"));
  plainRoot = await mkdtemp(join(tmpdir(), "cockpit-review-plain-"));
  outsideRoot = await mkdtemp(join(tmpdir(), "cockpit-review-outside-"));
  process.env.COCKPIT_REPO_ROOTS = `${repoRoot},${plainRoot}`;

  execFileSync("git", ["init", "-q", "-b", "main", repoRoot]);
  execFileSync("git", ["config", "user.email", "test@cockpit.dev"], { cwd: repoRoot });
  execFileSync("git", ["config", "user.name", "Cockpit Test"], { cwd: repoRoot });
  await writeFile(join(repoRoot, "a.txt"), "hello\n");
  execFileSync("git", ["add", "."], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "initial commit"], { cwd: repoRoot });
  await writeFile(join(repoRoot, "a.txt"), "hello\nworld\n");
  await writeFile(join(repoRoot, "b.txt"), "new file\n");
  execFileSync("git", ["add", "."], { cwd: repoRoot });
  execFileSync("git", ["commit", "-q", "-m", "add world and b"], { cwd: repoRoot });

  // A session workspace that lives OUTSIDE COCKPIT_REPO_ROOTS, with the
  // session repo as a subdirectory — the fix-5 shape: the bridge must allow
  // it because an agent is running there, not because it was configured.
  workspaceRoot = await mkdtemp(join(tmpdir(), "cockpit-review-workspace-"));
  sessionRepo = join(workspaceRoot, "repo");
  await mkdir(sessionRepo, { recursive: true });
  execFileSync("git", ["init", "-q", "-b", "main", sessionRepo]);
  execFileSync("git", ["config", "user.email", "test@cockpit.dev"], { cwd: sessionRepo });
  execFileSync("git", ["config", "user.name", "Cockpit Test"], { cwd: sessionRepo });
  await writeFile(join(sessionRepo, "x.txt"), "workspace file\n");
  execFileSync("git", ["add", "."], { cwd: sessionRepo });
  execFileSync("git", ["commit", "-q", "-m", "workspace initial"], { cwd: sessionRepo });
});

test.after(() => {
  if (originalRoots === undefined) delete process.env.COCKPIT_REPO_ROOTS;
  else process.env.COCKPIT_REPO_ROOTS = originalRoots;
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

test("diff against HEAD shows bounded changes with stat", async () => {
  // Self-contained working-tree change to a tracked file (test order is not
  // guaranteed; untracked files never appear in `git diff`).
  execFileSync("sh", ["-c", "echo draft >> a.txt"], { cwd: repoRoot });
  const result = await reviewDiff(repoRoot, "HEAD");
  assert.ok(result.diff.includes("+draft"), "diff should contain the +draft line");
  assert.equal(result.truncated, false);
  assert.ok(result.stat.length >= 1, `expected stat entries, got ${result.stat.length}`);
  const aStat = result.stat.find((s) => s.path === "a.txt");
  assert.ok(aStat, "stat should include a.txt");
});

test("diff accepts a hash ref", async () => {
  const overview = await reviewOverview(repoRoot);
  const parentHash = overview.log[1].hash;
  const result = await reviewDiff(repoRoot, parentHash);
  assert.ok(result.diff.includes("+world"));
});

test("commit kind diffs the commit against its parent", async () => {
  const overview = await reviewOverview(repoRoot);
  const headHash = overview.log[0]!.hash;
  const result = await reviewDiff(repoRoot, headHash, "commit");
  // The second commit added b.txt and +world to a.txt; the working-tree
  // noise (c.txt / d.txt from other tests) must not leak into a commit diff.
  assert.ok(result.diff.includes("+new file"), "commit diff should contain +new file");
  assert.ok(!result.diff.includes("+draft"), "working-tree changes must not leak in");
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
  const diff = await reviewDiff(nested, "HEAD", "working", [workspaceRoot]);
  assert.match(diff.diff, /y\.txt/);
  assert.match(diff.diff, /nested/);
});

test("403 message names the escape hatch", async () => {
  await assert.rejects(() => reviewOverview(outsideRoot), (error: unknown) => {
    assert.ok(error instanceof ReviewError);
    assert.equal(error.status, 403);
    assert.match(error.message, /COCKPIT_REPO_ROOTS/);
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
  const binary = await reviewDiff(repoRoot, "HEAD");
  assert.ok(binary.diff.includes("Binary files"), "binary diff should pass through");
  // Rename: porcelain status should surface the R code.
  execFileSync("git", ["mv", "blob.bin", "renamed.bin"], { cwd: repoRoot });
  const overview = await reviewOverview(repoRoot);
  const rename = overview.status.find((e) => e.code.startsWith("R"));
  assert.ok(rename, "rename should appear in status");
});

test("caps bound status entries, log size, and diff bytes", async () => {
  assert.ok(REVIEW_STATUS_MAX_ENTRIES >= 1);
  assert.ok(REVIEW_LOG_MAX >= 1);
  const result = await reviewDiff(repoRoot, "HEAD");
  assert.ok(Buffer.byteLength(result.diff, "utf8") <= REVIEW_DIFF_MAX_BYTES);
});
