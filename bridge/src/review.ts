import { execFile } from "node:child_process";
import { readdirSync, realpathSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { isAbsolute, join, resolve, sep } from "node:path";

/**
 * Read-only git review API.
 *
 * Safety invariants:
 *  - Allow-list: the requested path must resolve (realpath, no symlink
 *    escape) under one of COCKPIT_REPO_ROOTS (default ~/.herdr/worktrees).
 *  - Fixed git subcommands with strictly validated arguments only: the path
 *    is passed via `-C`, and refs are matched against a conservative regex.
 *    No user input ever lands in a flag position and no shell is involved.
 *  - Caps: bounded status entries, log size, diff bytes/lines, and a hard
 *    per-command timeout so a pathological repo cannot hang the bridge.
 *  - Nothing here mutates the repository (no checkout, no apply, no push).
 */

export const REVIEW_DIFF_MAX_BYTES = 64 * 1024;
export const REVIEW_DIFF_MAX_LINES = 800;
export const REVIEW_STATUS_MAX_ENTRIES = 200;
export const REVIEW_LOG_MAX = 50;
export const REVIEW_COMMAND_TIMEOUT_MS = 8_000;

export class ReviewError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
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

export interface ReviewDiffResult {
  diff: string;
  truncated: boolean;
  stat: ReviewDiffFileStat[];
}

const REF_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,200}$/;

function allowedRoots(): string[] {
  const configured = process.env.COCKPIT_REPO_ROOTS?.trim();
  if (!configured) return [resolve(homedir(), ".herdr", "worktrees")];
  return configured.split(",").map((root) => resolve(root.trim())).filter(Boolean);
}

/** Resolve a requested path and require it to live under an allowed root. */
export function resolveAllowedRepoPath(requested: string): string {
  if (!requested || requested.length > 4096) throw new ReviewError("invalid repo path", 400);
  if (!isAbsolute(requested)) throw new ReviewError("repo path must be absolute", 400);
  let target: string;
  try {
    target = realpathSync(resolve(requested));
  } catch {
    throw new ReviewError("no such directory", 404);
  }
  const roots = allowedRoots();
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
  if (!allowed) throw new ReviewError("path outside allowed repo roots", 403);
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
          const code = (error as NodeJS.ErrnoException).code;
          if (code === "ENOENT") return reject(new ReviewError("git is not installed", 500));
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

function isRepo(path: string): Promise<boolean> {
  return runGit(path, ["rev-parse", "--is-inside-work-tree"], 1024)
    .then((out) => out.trim() === "true")
    .catch(() => false);
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

function parsePorcelainStatus(output: string): {
  entries: ReviewStatusEntry[];
  truncated: boolean;
  branch: string | null;
  upstream: string | null;
  ahead: number;
  behind: number;
} {
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
  for (const line of output.split("\n")) {
    if (!line) continue;
    const fields = line.split("\u001f");
    if (fields.length < 4) continue;
    const [hash, author, dateText, ...subjectParts] = fields;
    const date = Number(dateText);
    if (!hash || !author || Number.isNaN(date)) continue;
    commits.push({ hash, author, subject: subjectParts.join("\u001f").trim(), date });
  }
  return commits;
}

export async function reviewOverview(requestedPath: string): Promise<ReviewOverview> {
  const path = resolveAllowedRepoPath(requestedPath);
  if (!(await isRepo(path))) throw new ReviewError("not a git repository", 404);

  const [branch, statusOut, logOut] = await Promise.all([
    currentBranch(path),
    runGit(path, ["status", "--porcelain=v1", "--branch"], REVIEW_STATUS_MAX_ENTRIES * 256 + 4096),
    runGit(
      path,
      ["log", "--no-color", `--max-count=${REVIEW_LOG_MAX}`, "--pretty=format:%H\u001f%an\u001f%ct\u001f%s"],
      REVIEW_LOG_MAX * 512 + 4096,
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

function capUtf8(text: string, maxBytes: number): { text: string; truncated: boolean } {
  if (Buffer.byteLength(text, "utf8") <= maxBytes) return { text, truncated: false };
  let truncated = text;
  while (Buffer.byteLength(truncated, "utf8") > maxBytes) {
    truncated = truncated.slice(0, Math.floor(truncated.length / 2));
  }
  return { text: truncated, truncated: true };
}

function capLines(text: string, maxLines: number): { text: string; truncated: boolean } {
  const lines = text.split("\n");
  if (lines.length <= maxLines) return { text, truncated: false };
  return { text: lines.slice(0, maxLines).join("\n"), truncated: true };
}

function parseDiffStat(output: string): ReviewDiffFileStat[] {
  const stats: ReviewDiffFileStat[] = [];
  for (const line of output.split("\n")) {
    const match = line.match(/^\s*(\S.*?)\s*\|\s*(\d+)\s*(.*)$/);
    if (!match) continue;
    const filePath = match[1]?.trim();
    if (!filePath) continue;
    const additions = (match[3]?.match(/\+/g) ?? []).length;
    const deletions = (match[3]?.match(/-/g) ?? []).length;
    stats.push({ path: filePath, additions, deletions });
  }
  return stats;
}

export async function reviewDiff(
  requestedPath: string,
  ref: string,
  kind: "working" | "commit" = "working",
): Promise<ReviewDiffResult> {
  const path = resolveAllowedRepoPath(requestedPath);
  if (!(await isRepo(path))) throw new ReviewError("not a git repository", 404);
  const safeRef = validateRef(ref);
  // "commit" diffs the commit against its parent (ref^..ref); "working"
  // diffs the working tree against the ref. Both stay read-only.
  const range = kind === "commit" ? [`${safeRef}^`, safeRef] : [safeRef];

  const statOut = await runGit(path, ["diff", "--no-color", "--stat", ...range], REVIEW_DIFF_MAX_BYTES);
  const diffOut = await runGit(
    path,
    ["diff", "--no-color", "--unified=6", ...range],
    REVIEW_DIFF_MAX_BYTES * 2,
  );

  let diff = diffOut;
  let truncated = false;
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
  return { diff, truncated, stat: parseDiffStat(statOut) };
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
export function reviewArtifacts(requestedPath: string): ReviewArtifactsResult {
  const path = resolveAllowedRepoPath(requestedPath);
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
