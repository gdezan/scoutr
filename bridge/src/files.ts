import { spawn } from "node:child_process";
import { readdirSync, realpathSync, statSync } from "node:fs";
import { dirname, basename, isAbsolute, join, resolve, sep } from "node:path";
import { BridgeError } from "./errors.js";
import { isMissingFileError, readFileHead, readFilePage } from "./file-head.js";

/**
 * File listing for the chat composer's `@` mention completion.
 *
 * Tracked *and* untracked-but-not-ignored files, so a file the agent wrote a
 * moment ago is mentionable before it is staged. `git ls-files -C <dir>` scopes
 * the listing to `dir` and prints paths relative to it, which is exactly the
 * cwd-relative form the composer inserts — no rebasing needed even when the
 * agent's cwd is a subdirectory of the repo root.
 *
 * Directories are not listed: the app derives them from these paths.
 */
export interface FileListing {
  /** Absolute, resolved directory the listing is for. */
  path: string;
  /** Slash-separated paths relative to [path], sorted, no directories. */
  files: string[];
  /** True when the listing hit [MAX_FILES] and more paths exist. */
  truncated: boolean;
}

export interface FileRead {
  content: string;
  truncated: boolean;
  binary: boolean;
  exists: boolean;
  /** Present when the caller requests a paged read. */
  offset?: number;
  nextOffset?: number | null;
  totalBytes?: number;
  /** Best-effort host stat for viewer triage; absent when the file is missing. */
  sizeBytes?: number;
  /** Extension-based content type for viewer triage; absent when the file is missing. */
  mime?: string;
}

export interface FileReadOptions {
  offset?: number;
  limit?: number;
}

export class FileListingError extends BridgeError {
  constructor(message: string) {
    super(message, 400);
    this.name = "FileListingError";
  }
}

export class FileReadError extends BridgeError {
  constructor(message: string, status: number) {
    super(message, status);
    this.name = "FileReadError";
  }
}

/** Path cap shared by both the git and walk paths. */
export const MAX_FILES = 20_000;
/** Raw-bytes cap for GET /api/file/bytes: streams past this answer 413 instead. */
export const FILE_BYTES_MAX_BYTES = 20 * 1024 * 1024;

/** Depth cap for the non-repo walk; `1` means the directory's own children. */
const MAX_WALK_DEPTH = 6;

/** Never worth walking, and never worth mentioning to an agent. */
const SKIP_DIRS = new Set([".git", "node_modules", "build", "dist", "target"]);

const GIT_TIMEOUT_MS = 5_000;

/** Enough for MAX_FILES paths of ordinary length; the cap kills git early. */
const GIT_MAX_BYTES = 4 * 1024 * 1024;

export async function listFiles(requested: string, includeHidden = false): Promise<FileListing> {
  const path = resolveDir(requested);
  if (includeHidden) return { path, ...walkFiles(path, true) };

  const tracked = await gitFiles(path);
  const { files, truncated } = tracked ?? walkFiles(path);
  return { path, files, truncated };
}

/**
 * Read a file inside one of the already-authorized active-agent workspaces.
 * Lexical containment is checked before realpath so outside paths do not reveal
 * whether a file exists. Paged reads keep each filesystem response bounded.
 */
export function readWorkspaceFile(requested: string, cwds: string[], options?: FileReadOptions): FileRead {
  const lexicalTarget = checkWorkspacePath(requested, cwds);

  let target: string;
  try {
    target = realpathSync(lexicalTarget);
  } catch (error) {
    if (isMissingFileError(error)) return missingWorkspaceFile(lexicalTarget, cwds, options);
    throw error;
  }
  if (!cwds.some((cwd) => isWithin(target, cwd))) {
    throw new FileReadError("file resolves outside an active agent workspace", 403);
  }
  const read = !options ? readFileHead(target) : readFilePage(target, options.offset ?? 0, options.limit);
  return withWorkspaceFileMeta(target, read);
}

/** Extension-based content types for workspace previews; unknown types stream as bytes. */
const WORKSPACE_MIME_BY_EXTENSION = new Map<string, string>([
  [".png", "image/png"],
  [".jpg", "image/jpeg"],
  [".jpeg", "image/jpeg"],
  [".gif", "image/gif"],
  [".webp", "image/webp"],
  [".svg", "image/svg+xml"],
  [".html", "text/html; charset=utf-8"],
  [".htm", "text/html; charset=utf-8"],
  [".pdf", "application/pdf"],
  [".md", "text/markdown; charset=utf-8"],
  [".markdown", "text/markdown; charset=utf-8"],
  [".txt", "text/plain; charset=utf-8"],
  [".log", "text/plain; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".csv", "text/csv; charset=utf-8"],
]);

/** Extension-based content type for a workspace path; unknown types stream as bytes. */
export function workspaceMimeForPath(path: string): string {
  const dot = path.lastIndexOf(".");
  const slash = path.lastIndexOf("/");
  const ext = dot > slash ? path.slice(dot).toLowerCase() : "";
  return WORKSPACE_MIME_BY_EXTENSION.get(ext) ?? "application/octet-stream";
}

/**
 * Shared lexical gate for workspace file access: shape and absolute-path
 * checks plus containment before realpath, so outside paths never reveal
 * whether a file exists. Returns the lexically-resolved target.
 */
function checkWorkspacePath(requested: string, cwds: string[]): string {
  if (!requested || requested.length > 4096 || /\p{Cc}/u.test(requested)) {
    throw new FileReadError("invalid file path", 400);
  }
  if (!isAbsolute(requested)) throw new FileReadError("file path must be absolute", 400);
  const lexicalTarget = resolve(requested);
  if (!cwds.some((cwd) => isWithin(lexicalTarget, cwd))) {
    throw new FileReadError("file is outside an active agent workspace", 403);
  }
  return lexicalTarget;
}

/** Stat result backing GET /api/file/bytes: the real path, its size, and its download name. */
export interface WorkspaceFileStat {
  path: string;
  sizeBytes: number;
  mime: string;
  filename: string;
}

/**
 * Stat a regular file inside one of the already-authorized active-agent
 * workspaces. Same containment as readWorkspaceFile (lexical first, then
 * realpath, with missing-path ancestors checked so a symlink escape reads
 * 403 instead of 404). Missing paths and non-files read 404; files past
 * FILE_BYTES_MAX_BYTES read 413 so the phone can triage before downloading.
 */
export function statWorkspaceFile(requested: string, cwds: string[]): WorkspaceFileStat {
  const lexicalTarget = checkWorkspacePath(requested, cwds);
  let target: string;
  try {
    target = realpathSync(lexicalTarget);
  } catch (error) {
    if (!isMissingFileError(error)) throw error;
    assertMissingWithinWorkspace(lexicalTarget, cwds);
    throw new FileReadError("no such file", 404);
  }
  if (!cwds.some((cwd) => isWithin(target, cwd))) {
    throw new FileReadError("file resolves outside an active agent workspace", 403);
  }
  let sizeBytes: number;
  try {
    const info = statSync(target);
    if (!info.isFile()) throw new FileReadError("not a file", 404);
    sizeBytes = info.size;
  } catch (error) {
    if (error instanceof FileReadError) throw error;
    if (isMissingFileError(error)) throw new FileReadError("no such file", 404);
    throw error;
  }
  if (sizeBytes > FILE_BYTES_MAX_BYTES) throw new FileReadError("file too large", 413);
  return { path: target, sizeBytes, mime: workspaceMimeForPath(target), filename: basename(target) };
}

/** Missing-file twin of the escape check: 403 when an ancestor escapes, else silent. */
function assertMissingWithinWorkspace(path: string, cwds: string[]): void {
  let ancestor = path;
  while (true) {
    try {
      const resolved = realpathSync(ancestor);
      if (!cwds.some((cwd) => isWithin(resolved, cwd))) {
        throw new FileReadError("file resolves outside an active agent workspace", 403);
      }
      return;
    } catch (error) {
      if (error instanceof FileReadError) throw error;
      if (!isMissingFileError(error)) throw error;
      const parent = dirname(ancestor);
      if (parent === ancestor) return;
      ancestor = parent;
    }
  }
}

/** Attach best-effort stat triage to a file read; missing files stay bare. */
function withWorkspaceFileMeta(target: string, read: FileRead): FileRead {
  if (!read.exists) return read;
  try {
    return { ...read, sizeBytes: statSync(target).size, mime: workspaceMimeForPath(target) };
  } catch {
    return read;
  }
}
/** Check resolvable parents before reporting a missing path. */
function missingWorkspaceFile(path: string, cwds: string[], options?: FileReadOptions): FileRead {
  let ancestor = path;
  while (true) {
    try {
      const resolved = realpathSync(ancestor);
      if (!cwds.some((cwd) => isWithin(resolved, cwd))) {
        throw new FileReadError("file resolves outside an active agent workspace", 403);
      }
      return options ? pagedEmptyFileRead(options.offset ?? 0) : emptyFileRead();
    } catch (error) {
      if (!isMissingFileError(error)) throw error;
      const parent = dirname(ancestor);
      if (parent === ancestor) return options ? pagedEmptyFileRead(options.offset ?? 0) : emptyFileRead();
      ancestor = parent;
    }
  }
}

function emptyFileRead(): FileRead {
  return { content: "", truncated: false, binary: false, exists: false };
}

function pagedEmptyFileRead(offset: number): FileRead {
  return { ...emptyFileRead(), offset, nextOffset: null, totalBytes: 0 };
}

function isWithin(target: string, root: string): boolean {
  const normalizedRoot = resolve(root);
  return target === normalizedRoot || target.startsWith(normalizedRoot.endsWith(sep) ? normalizedRoot : normalizedRoot + sep);
}

function resolveDir(requested: string): string {
  let stat;
  const target = resolve(requested);
  try {
    stat = statSync(target);
  } catch {
    throw new FileListingError("no such directory");
  }
  if (!stat.isDirectory()) throw new FileListingError("not a directory");
  return target;
}

interface Collected {
  files: string[];
  truncated: boolean;
}

/**
 * `git ls-files` output for `path`, or null when it is not a repository (or
 * git is unavailable). Either way the caller falls back to walking, so a
 * missing git degrades to a usable listing rather than an error.
 */
function gitFiles(path: string): Promise<Collected | null> {
  return new Promise((settleWith) => {
    const child = spawn(
      "git",
      ["-C", path, "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
      { env: { ...process.env, GIT_TERMINAL_PROMPT: "0" }, stdio: ["ignore", "pipe", "pipe"] },
    );
    const chunks: Buffer[] = [];
    let total = 0;
    let stoppedEarly = false;
    let settled = false;
    const settle = (value: Collected | null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      settleWith(value);
    };
    const timeout = setTimeout(() => {
      child.kill();
      settle(null);
    }, GIT_TIMEOUT_MS);
    child.stdout.on("data", (chunk: Buffer) => {
      if (settled) return;
      const room = GIT_MAX_BYTES - total;
      if (room <= 0) {
        stoppedEarly = true;
        child.kill();
        return;
      }
      total += chunk.length;
      chunks.push(room >= chunk.length ? chunk : chunk.subarray(0, room));
      if (total >= GIT_MAX_BYTES) {
        stoppedEarly = true;
        child.kill();
      }
    });
    child.on("error", () => settle(null)); // git not installed
    child.on("close", (code) => {
      if (settled) return;
      if (code !== 0 && !stoppedEarly) return settle(null); // not a repository
      // A byte-capped read can end mid-path; drop the trailing partial record
      // (NUL-terminated output means a complete record always ends in NUL).
      const text = Buffer.concat(chunks).toString("utf8");
      const records = text.split("\0");
      if (stoppedEarly) records.pop();
      const files = records.filter((entry) => entry.length > 0);
      const capped = files.length > MAX_FILES;
      settle({
        files: (capped ? files.slice(0, MAX_FILES) : files).sort((a, b) => a.localeCompare(b)),
        truncated: capped || stoppedEarly,
      });
    });
  });
}

/**
 * Bounded recursive walk for directories that are not git repositories, or
 * for the browser's hidden-inclusive mode. Hidden entries are skipped unless
 * [includeHidden] is true; [SKIP_DIRS] is always skipped.
 */
function walkFiles(root: string, includeHidden = false): Collected {
  const files: string[] = [];
  // `truncated` reports an incomplete listing (path cap *or* depth cap);
  // `stopped` is the hard stop once no further file can be collected.
  let truncated = false;
  let stopped = false;
  const visit = (dir: string, prefix: string, depth: number) => {
    if (stopped) return;
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return; // unreadable directory: skip, not fatal
    }
    for (const entry of entries) {
      if (stopped) return;
      if ((!includeHidden && entry.name.startsWith(".")) || SKIP_DIRS.has(entry.name)) continue;
      const relativePath = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isFile()) {
        if (files.length >= MAX_FILES) {
          stopped = true;
          truncated = true;
          return;
        }
        files.push(relativePath);
      } else if (entry.isDirectory()) {
        if (depth < MAX_WALK_DEPTH) visit(join(dir, entry.name), relativePath, depth + 1);
        // Anything below the depth cap is unlisted, so the result is partial.
        else truncated = true;
      }
    }
  };
  visit(root, "", 1);
  return { files: files.sort((a, b) => a.localeCompare(b)), truncated };
}
