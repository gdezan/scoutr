import { execFile, spawn } from "node:child_process";
import { readdirSync, realpathSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { isAbsolute, join, resolve, sep } from "node:path";
import { BridgeError } from "./errors.js";
import { FILE_HEAD_MAX_BYTES, capUtf8, isBinaryBuffer, readFileHead } from "./file-head.js";

export { capUtf8 };

/**
 * Read-only git review API.
 *
 * Safety invariants:
 *  - Allow-list: the requested path must resolve (realpath, no symlink
 *    escape) under one of SCOUTR_REPO_ROOTS (default ~/.herdr/worktrees).
 *  - Fixed git subcommands with strictly validated arguments only: the path
 *    is passed via `-C`, and refs are matched against a conservative regex.
 *    No user input ever lands in a flag position and no shell is involved.
 *  - Caps: bounded status entries, log size, diff bytes/lines, and a hard
 *    per-command timeout so a pathological repo cannot hang the bridge.
 *  - Nothing here mutates the repository (no checkout, no apply, no push).
 */

export const REVIEW_DIFF_MAX_BYTES = 64 * 1024;
export const REVIEW_DIFF_MAX_LINES = 800;
/** Per-commit message body cap in the overview log; the subject is separate. */
export const REVIEW_LOG_BODY_MAX_BYTES = 2048;
/** Full-file view cap: the final version of one file, read head-first. */
export const REVIEW_FILE_MAX_BYTES = FILE_HEAD_MAX_BYTES;
export const REVIEW_STATUS_MAX_ENTRIES = 200;
export const REVIEW_LOG_MAX = 50;
export const REVIEW_COMMAND_TIMEOUT_MS = 8_000;

export class ReviewError extends BridgeError {
  constructor(message: string, status: number) {
    super(message, status);
  }
}

export interface ReviewStatusEntry {
  /** Porcelain XY code, e.g. " M", "??", "R ". */
  code: string;
  path: string;
}

export interface ReviewCommit {
  hash: string;
  subject: string;
  /** Full message body (everything below the subject line), byte-capped. */
  body: string;
  author: string;
  /** Unix seconds. */
  date: number;
}

export interface ReviewOverview {
  path: string;
  root: string;
  branch: string | null;
  /** Upstream tracking branch, e.g. "origin/main", when known. */
  upstream: string | null;
  /** Commits ahead of the upstream tracking branch. */
  ahead: number;
  /** Commits behind the upstream tracking branch. */
  behind: number;
  status: ReviewStatusEntry[];
  statusTruncated: boolean;
  log: ReviewCommit[];
  logTruncated: boolean;
}

/** One generated artifact (build output, dependency tree, test report). */
export interface ReviewArtifact {
  path: string;
  size: number;
  mtimeMs: number;
}

export interface ReviewArtifactsResult {
  artifacts: ReviewArtifact[];
  truncated: boolean;
}

export interface ReviewDiffFileStat {
  path: string;
  additions: number;
  deletions: number;
}

/** Stat-only diff listing: every file in the diff, without hunk content. */
export interface ReviewDiffResult {
  stat: ReviewDiffFileStat[];
  truncated: boolean;
}

/** Bounded diff of a single file against the same range as [reviewDiff]. */
export interface ReviewFileDiffResult {
  diff: string;
  truncated: boolean;
}

/** Bounded final version of a single file at the ref (working tree or commit). */
export interface ReviewFileContentResult {
  content: string;
  truncated: boolean;
  binary: boolean;
  exists: boolean;
}

const REF_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,200}$/;

/**
 * Allowed review roots: SCOUTR_REPO_ROOTS when set, else the default
 * worktree root, plus per-request extras. Extras are the live agent
 * workspaces (session cwds) — paths the user has already authorized an
 * agent to run in, so read-only git review of them adds no privilege.
 */
function allowedRoots(extraRoots: string[] = []): string[] {
  const configured = process.env.SCOUTR_REPO_ROOTS?.trim();
  const base = configured
    ? configured.split(",").map((root) => resolve(root.trim())).filter(Boolean)
    : [resolve(homedir(), ".herdr", "worktrees")];
  return [...base, ...extraRoots.map((root) => resolve(root.trim())).filter(Boolean)];
}

/**
 * Resolve a requested path and require it to live under an allowed root.
 * @param extraRoots per-request roots beyond SCOUTR_REPO_ROOTS (e.g. live
 *   agent workspaces); still realpath-checked like the configured roots.
 */
export function resolveAllowedRepoPath(requested: string, extraRoots: string[] = []): string {
  if (!requested || requested.length > 4096) throw new ReviewError("invalid repo path", 400);
  if (!isAbsolute(requested)) throw new ReviewError("repo path must be absolute", 400);
  let target: string;
  try {
    target = realpathSync(resolve(requested));
  } catch {
    throw new ReviewError("no such directory", 404);
  }
  const roots = allowedRoots(extraRoots);
  if (roots.length === 0) throw new ReviewError("no allowed repo roots configured", 500);
  const allowed = roots.some((root) => {
    let resolvedRoot: string;
    try {
      resolvedRoot = realpathSync(root);
    } catch {
      return false;
    }
    return target === resolvedRoot || target.startsWith(resolvedRoot + sep);
  });
  if (!allowed) {
    throw new ReviewError(
      "path outside allowed repo roots — review a running agent's workspace, or add the path to SCOUTR_REPO_ROOTS",
      403,
    );
  }
  return target;
}

function validateRef(ref: string): string {
  const trimmed = ref.trim();
  if (!trimmed || !REF_PATTERN.test(trimmed)) throw new ReviewError("invalid git ref", 400);
  return trimmed;
}

function runGit(path: string, args: string[], maxBytes: number): Promise<string> {
  return new Promise((resolvePromise, reject) => {
    execFile(
      "git",
      ["-C", path, ...args],
      {
        encoding: "utf8",
        maxBuffer: Math.max(maxBytes + 1024, 2 * 1024 * 1024),
        timeout: REVIEW_COMMAND_TIMEOUT_MS,
        env: { ...process.env, GIT_TERMINAL_PROMPT: "0" },
      },
      (error, stdout) => {
        if (error) {
          // SAFETY: a non-Error exit (ENOENT) means git is absent; the ExecFileError
          // carries the node errno code after the failure, so the cast is sound here.
          const code = (error as NodeJS.ErrnoException).code;
          if (code === "ENOENT") return reject(new ReviewError("git is not installed", 500));
          // SAFETY: a rejected child reports a timeout through `killed`; Error is the
          // only shape execFile yields here, so widening to read `killed` is safe.
          const message = (error as Error & { killed?: boolean }).killed
            ? "git command timed out"
            : (error.message ?? "git failed");
          return reject(new ReviewError(message, 502));
        }
        resolvePromise(stdout);
      },
    );
  });
}

/**
 * Like [runGit] but streams stdout up to `maxBytes` and kills the child the
 * moment the cap is reached, so arbitrarily large output degrades to a
 * truncated result instead of an exec-buffer failure. stdout is not utf8-
 * decoded until after capping (byte-exact).
 */
function runGitCapped(
  path: string,
  args: string[],
  maxBytes: number,
): Promise<{ text: string; stoppedEarly: boolean }> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn("git", ["-C", path, ...args], {
      env: { ...process.env, GIT_TERMINAL_PROMPT: "0" },
      stdio: ["ignore", "pipe", "pipe"],
    });
    const chunks: Buffer[] = [];
    let total = 0;
    let stoppedEarly = false;
    let stderr = "";
    let settled = false;
    const settle = (fn: () => void) => {
      if (!settled) {
        settled = true;
        fn();
      }
    };
    const timeout = setTimeout(() => {
      child.kill();
      settle(() => reject(new ReviewError("git command timed out", 502)));
    }, REVIEW_COMMAND_TIMEOUT_MS);
    child.stdout.on("data", (chunk: Buffer) => {
      if (settled) return;
      const room = maxBytes - total;
      if (room <= 0) {
        stoppedEarly = true;
        child.kill();
        return;
      }
      total += chunk.length;
      chunks.push(room >= chunk.length ? chunk : chunk.subarray(0, room));
      if (total >= maxBytes) {
        stoppedEarly = true;
        child.kill();
      }
    });
    child.stderr.on("data", (chunk: Buffer) => {
      if (stderr.length < 2048) stderr += chunk.toString("utf8");
    });
    child.on("error", () => settle(() => reject(new ReviewError("git is not installed", 500))));
    child.on("close", (code) => {
      clearTimeout(timeout);
      if (settled) return;
      if (code !== 0 && !stoppedEarly) {
        return settle(() => reject(new ReviewError(stderr.trim() || "git failed", 502)));
      }
      const bytes = Buffer.concat(chunks);
      settle(() => resolvePromise({ text: bytes.toString("utf8"), stoppedEarly }));
    });
  });
}

function isRepo(path: string): Promise<boolean> {
  return runGit(path, ["rev-parse", "--is-inside-work-tree"], 1024)
    .then((out) => out.trim() === "true")
    .catch(() => false);
}

/**
 * The git repository root containing `path`, or null when `path` is not
 * inside a repository. Used to derive least-privilege review roots from
 * live agent workspaces: a session cwd grants review access only to the
 * repo it lives in, never the whole cwd subtree.
 */
export async function gitRepoRoot(path: string): Promise<string | null> {
  try {
    const out = await runGit(path, ["rev-parse", "--show-toplevel"], 4096);
    const root = out.trim();
    if (!root) return null;
    return realpathSync(root);
  } catch {
    return null;
  }
}

/** Branch name or null when detached. */
async function currentBranch(path: string): Promise<string | null> {
  try {
    const out = await runGit(path, ["rev-parse", "--abbrev-ref", "HEAD"], 1024);
    const branch = out.trim();
    return branch && branch !== "HEAD" ? branch : null;
  } catch {
    return null;
  }
}

interface ParsedStatus {
  entries: ReviewStatusEntry[];
  truncated: boolean;
  branch: string | null;
  upstream: string | null;
  ahead: number;
  behind: number;
}

function parsePorcelainStatus(output: string): ParsedStatus {
  const entries: ReviewStatusEntry[] = [];
  let branch: string | null = null;
  let upstream: string | null = null;
  let ahead = 0;
  let behind = 0;
  const lines = output.split("\n");
  for (const line of lines) {
    if (entries.length >= REVIEW_STATUS_MAX_ENTRIES) return { entries, truncated: true, branch, upstream, ahead, behind };
    if (!line) continue;
    if (line.startsWith("##")) {
      // ## main...origin/main [ahead 1, behind 2] (or ## main [gone])
      const head = line.slice(2).trim();
      const match = head.match(/^(\S+?)(?:\.\.\.(\S+?))?(?:\s*\[(?:ahead (\d+))?(?:, )?(?:behind (\d+))?\])?$/);
      if (match) {
        branch = match[1] || null;
        upstream = match[2] || null;
        ahead = Number(match[3] ?? 0);
        behind = Number(match[4] ?? 0);
      }
      continue;
    }
    if (line.length < 4) continue;
    const code = line.slice(0, 2);
    const path = line.slice(3);
    if (!path) continue;
    entries.push({ code, path });
  }
  return { entries, truncated: false, branch, upstream, ahead, behind };
}

function parseLog(output: string): ReviewCommit[] {
  const commits: ReviewCommit[] = [];
  // Records are separated by \u001e because the body may contain newlines.
  for (const record of output.split("\u001e")) {
    const line = record.trimEnd(); // git ends each record with a newline
    if (!line) continue;
    const fields = line.split("\u001f");
    if (fields.length < 5) continue;
    const [hash, author, dateText, subject, ...bodyParts] = fields;
    const date = Number(dateText);
    if (!hash || !author || Number.isNaN(date)) continue;
    const body = capUtf8(bodyParts.join("\u001f"), REVIEW_LOG_BODY_MAX_BYTES).text;
    commits.push({ hash, author, subject: subject ?? "", body, date });
  }
  return commits;
}

export async function reviewOverview(requestedPath: string, extraRoots: string[] = []): Promise<ReviewOverview> {
  const path = resolveAllowedRepoPath(requestedPath, extraRoots);
  if (!(await isRepo(path))) throw new ReviewError("not a git repository", 404);

  const [branch, statusOut, logOut] = await Promise.all([
    currentBranch(path),
    runGit(path, ["status", "--porcelain=v1", "--branch"], REVIEW_STATUS_MAX_ENTRIES * 256 + 4096),
    runGit(
      path,
      ["log", "--no-color", `--max-count=${REVIEW_LOG_MAX}`, "--pretty=format:%H\u001f%an\u001f%ct\u001f%s\u001f%b\u001e"],
      REVIEW_LOG_MAX * (512 + REVIEW_LOG_BODY_MAX_BYTES) + 4096,
    ),
  ]);

  const status = parsePorcelainStatus(statusOut);
  const commits = parseLog(logOut);
  return {
    path,
    root: realpathSync(path),
    branch: status.branch ?? branch,
    upstream: status.upstream,
    ahead: status.ahead,
    behind: status.behind,
    status: status.entries,
    statusTruncated: status.truncated,
    log: commits,
    logTruncated: commits.length >= REVIEW_LOG_MAX,
  };
}

interface CappedLines { text: string; truncated: boolean; }

function capLines(text: string, maxLines: number): CappedLines {
  const lines = text.split("\n");
  if (lines.length <= maxLines) return { text, truncated: false };
  return { text: lines.slice(0, maxLines).join("\n"), truncated: true };
}

/**
 * Parses `git diff -z --numstat` output. Unlike `--stat`, numstat never
 * abbreviates long paths, so every parsed path stays valid for the per-file
 * endpoints. Rename/copy rows emit `add\tadd\t` + NUL + old + NUL + new; the
 * last non-empty token is the current path.
 */
function parseDiffNumstat(output: string): ReviewDiffFileStat[] {
  const stats: ReviewDiffFileStat[] = [];
  const tokens = output.split("\0");
  let i = 0;
  while (i < tokens.length) {
    const token = tokens[i];
    if (token === undefined) break;
    if (!token.includes("\t")) {
      i++;
      continue;
    }
    const [addPart, delPart, pathPart] = token.split("\t", 3);
    const extra: string[] = [];
    let j = i + 1;
    while (j < tokens.length && !(tokens[j] ?? "").includes("\t")) {
      const candidate = tokens[j] ?? "";
      if (candidate) extra.push(candidate);
      j++;
    }
    i = j;
    const filePath = extra.length > 0 ? extra[extra.length - 1] : pathPart;
    if (!filePath) continue;
    const additions = addPart === "-" || addPart === undefined ? 0 : Number(addPart);
    const deletions = delPart === "-" || delPart === undefined ? 0 : Number(delPart);
    if (Number.isNaN(additions) || Number.isNaN(deletions)) continue;
    stats.push({ path: filePath, additions, deletions });
  }
  return stats;
}

/** The canonical empty-tree hash; used as the parent of a root commit. */
const EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

/**
 * Diff range for one session. "commit" diffs the commit against its parent
 * (`ref^..ref`), falling back to the empty tree for root commits so "Diff vs
 * parent" still works; "working" diffs the working tree against the ref.
 * Always read-only.
 */
async function resolveCommitRange(path: string, safeRef: string, kind: "working" | "commit"): Promise<string[]> {
  if (kind === "working") return [safeRef];
  try {
    await runGit(path, ["rev-parse", "--verify", "--quiet", `${safeRef}^`], 1024);
    return [`${safeRef}^`, safeRef];
  } catch {
    // Root commit: diff against the empty tree so "Diff vs parent" still works.
    return [EMPTY_TREE, safeRef];
  }
}

/**
 * Stat-only listing of the changed files in a diff. Hunk content is served
 * per file by [reviewFileDiff] so no global byte/line cap can make the tail
 * of a large diff unreachable.
 */
export async function reviewDiff(
  requestedPath: string,
  ref: string,
  kind: "working" | "commit" = "working",
  extraRoots: string[] = [],
): Promise<ReviewDiffResult> {
  const path = resolveAllowedRepoPath(requestedPath, extraRoots);
  if (!(await isRepo(path))) throw new ReviewError("not a git repository", 404);
  const safeRef = validateRef(ref);
  const range = await resolveCommitRange(path, safeRef, kind);

  const statOut = await runGit(path, ["diff", "--no-color", "-z", "--numstat", ...range], REVIEW_DIFF_MAX_BYTES);
  const statCapped = capUtf8(statOut, REVIEW_DIFF_MAX_BYTES);
  return { stat: parseDiffNumstat(statCapped.text), truncated: statCapped.truncated };
}

/** File paths arrive from git's own stat output; reject pathspec magic and escapes. */
function validateFilePath(file: string): string {
  const trimmed = file.trim().replace(/^\.\/+/, "");
  if (!trimmed || trimmed.length > 512) throw new ReviewError("invalid file path", 400);
  if (/[\0\n\r]/.test(trimmed) || trimmed.startsWith("/")) throw new ReviewError("invalid file path", 400);
  // A leading :, ! or ^ makes git treat the pathspec as magic ("glob", exclude).
  if (trimmed[0] === ":" || trimmed[0] === "!" || trimmed[0] === "^") {
    throw new ReviewError("invalid file path", 400);
  }
  if (trimmed.split("/").some((part) => part === "..")) throw new ReviewError("invalid file path", 400);
  return trimmed;
}

/**
 * Bounded diff of one file against the same range as [reviewDiff]. No
 * realpath containment check is needed here (unlike the working-tree file
 * read): git diff only reads git objects and the index for the validated
 * pathspec, so there is no filesystem-escape surface.
 */
export async function reviewFileDiff(
  requestedPath: string,
  ref: string,
  kind: "working" | "commit" = "working",
  filePath: string,
  extraRoots: string[] = [],
): Promise<ReviewFileDiffResult> {
  const path = resolveAllowedRepoPath(requestedPath, extraRoots);
  if (!(await isRepo(path))) throw new ReviewError("not a git repository", 404);
  const safeRef = validateRef(ref);
  const file = validateFilePath(filePath);
  const range = await resolveCommitRange(path, safeRef, kind);

  // Stream-capped: a pathological per-file diff (e.g. a huge lockfile change)
  // degrades to a truncated result instead of failing on an exec buffer.
  const diffOut = await runGitCapped(
    path,
    ["diff", "--no-color", "--unified=6", ...range, "--", file],
    REVIEW_DIFF_MAX_BYTES,
  );

  let diff = diffOut.text;
  let truncated = diffOut.stoppedEarly;
  const lineCapped = capLines(diff, REVIEW_DIFF_MAX_LINES);
  if (lineCapped.truncated) {
    diff = lineCapped.text;
    truncated = true;
  }
  const byteCapped = capUtf8(diff, REVIEW_DIFF_MAX_BYTES);
  if (byteCapped.truncated) {
    diff = byteCapped.text;
    truncated = true;
  }
  return { diff, truncated };
}

/**
 * Final version of one file: the working-tree file itself for "working"
 * diffs, or `git show <ref>:<path>` for a commit. Read-only, realpath-checked
 * under the allowed repo, head-capped at [REVIEW_FILE_MAX_BYTES] bytes.
 */
export async function reviewFileContent(
  requestedPath: string,
  ref: string,
  kind: "working" | "commit" = "working",
  filePath: string,
  extraRoots: string[] = [],
): Promise<ReviewFileContentResult> {
  const path = resolveAllowedRepoPath(requestedPath, extraRoots);
  if (!(await isRepo(path))) throw new ReviewError("not a git repository", 404);
  const safeRef = validateRef(ref);
  const file = validateFilePath(filePath);
  if (kind === "commit") return gitShowFile(path, safeRef, file);

  // Working-tree kind: the final version is the file on disk. Symlinks must
  // not resolve outside the allowed repo (same invariant as the path check).
  let target: string;
  try {
    target = realpathSync(join(path, file));
  } catch {
    return { content: "", truncated: false, binary: false, exists: false };
  }
  if (target !== path && !target.startsWith(path + sep)) {
    throw new ReviewError("file resolves outside the repository", 403);
  }
  return readFileHead(target);
}


/**
 * `git show <ref>:<path>` with streamed head-capping: the child is killed as
 * soon as the cap is reached, so arbitrarily large blobs degrade to a
 * truncated result instead of an unbounded buffer or a 502.
 */
function gitShowFile(path: string, ref: string, file: string): Promise<ReviewFileContentResult> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn("git", ["-C", path, "show", `${ref}:${file}`], {
      env: { ...process.env, GIT_TERMINAL_PROMPT: "0" },
      stdio: ["ignore", "pipe", "pipe"],
    });
    const chunks: Buffer[] = [];
    let total = 0;
    let stoppedEarly = false;
    let stderr = "";
    let settled = false;
    const settle = (fn: () => void) => {
      if (!settled) {
        settled = true;
        fn();
      }
    };
    const timeout = setTimeout(() => {
      child.kill();
      settle(() => reject(new ReviewError("git command timed out", 502)));
    }, REVIEW_COMMAND_TIMEOUT_MS);

    child.stdout.on("data", (chunk: Buffer) => {
      if (settled) return;
      const room = REVIEW_FILE_MAX_BYTES - total;
      if (room <= 0) {
        stoppedEarly = true;
        child.kill();
        return;
      }
      total += chunk.length;
      chunks.push(room >= chunk.length ? chunk : chunk.subarray(0, room));
      if (total >= REVIEW_FILE_MAX_BYTES) {
        stoppedEarly = true;
        child.kill();
      }
    });
    child.stderr.on("data", (chunk: Buffer) => {
      if (stderr.length < 2048) stderr += chunk.toString("utf8");
    });
    child.on("error", () => settle(() => reject(new ReviewError("git is not installed", 500))));
    child.on("close", (code) => {
      clearTimeout(timeout);
      if (settled) return;
      if (code !== 0 && !stoppedEarly) {
        if (stderr.includes("does not exist") || stderr.includes("exists on disk, but not in")) {
          return settle(() => resolvePromise({ content: "", truncated: false, binary: false, exists: false }));
        }
        return settle(() => reject(new ReviewError(stderr.trim() || "git failed", 502)));
      }
      const bytes = Buffer.concat(chunks);
      if (isBinaryBuffer(bytes)) {
        return settle(() => resolvePromise({ content: "", truncated: false, binary: true, exists: true }));
      }
      const capped = capUtf8(bytes.toString("utf8"), REVIEW_FILE_MAX_BYTES);
      settle(() =>
        resolvePromise({
          content: capped.text,
          truncated: capped.truncated || stoppedEarly,
          binary: false,
          exists: true,
        }),
      );
    });
  });
}

/**
 * Generated-artifact roots (build output, dependency trees, test reports).
 * Conservative on purpose: these are the dirs a repo's own build and tooling
 * create; anything else stays invisible to the review surface.
 */
const ARTIFACT_DIRS = new Set([
  "build",
  "dist",
  "out",
  "target",
  "coverage",
  ".gradle",
  ".cache",
  ".next",
  "node_modules",
  "__pycache__",
  ".venv",
  "venv",
]);

const ARTIFACTS_MAX = 100;
const ARTIFACTS_MAX_DIRS = 2000;
const ARTIFACTS_MAX_DEPTH = 8;

/**
 * Bounded walk that collects generated-artifact files (size + mtime) without
 * ever following symlinks or escaping the repository root.
 */
export function reviewArtifacts(requestedPath: string, extraRoots: string[] = []): ReviewArtifactsResult {
  const path = resolveAllowedRepoPath(requestedPath, extraRoots);
  const artifacts: ReviewArtifact[] = [];
  const queue: Array<{ dir: string; depth: number; inArtifact: boolean }> = [
    { dir: path, depth: 0, inArtifact: false },
  ];
  let visited = 0;
  let truncated = false;

  while (queue.length > 0 && visited < ARTIFACTS_MAX_DIRS) {
    const { dir, depth, inArtifact } = queue.shift()!;
    if (depth > ARTIFACTS_MAX_DEPTH) continue;
    visited++;
    let entries: string[] = [];
    try {
      entries = readdirSync(dir);
    } catch {
      continue; // unreadable dir (permissions): skip, not fatal
    }
    for (const name of entries) {
      if (name === ".git") continue;
      const full = join(dir, name);
      let stat;
      try {
        stat = statSync(full);
      } catch {
        continue;
      }
      if (stat.isDirectory()) {
        const childArtifact = inArtifact || ARTIFACT_DIRS.has(name);
        if (queue.length < ARTIFACTS_MAX_DIRS) {
          queue.push({ dir: full, depth: depth + 1, inArtifact: childArtifact });
        } else {
          truncated = true;
        }
      } else if (inArtifact) {
        artifacts.push({ path: full, size: stat.size, mtimeMs: stat.mtimeMs });
        if (artifacts.length >= ARTIFACTS_MAX) {
          truncated = true;
          break;
        }
      }
    }
  }
  if (visited >= ARTIFACTS_MAX_DIRS) truncated = true;
  artifacts.sort((a, b) => b.size - a.size);
  return { artifacts, truncated };
}
