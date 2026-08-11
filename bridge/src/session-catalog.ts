import { basename, isAbsolute, relative, resolve } from "node:path";
import { readdir, realpath, stat, unlink } from "node:fs/promises";
import { BridgeError } from "./errors.js";
import { MAX_SESSION_TITLE_LENGTH, readTranscript, writeSessionTitle } from "./transcript.js";

const MAX_CANDIDATES = 2_000;
const MAX_SCANNED_FILES = 500;
const MAX_LIMIT = 200;
const DEFAULT_LIMIT = 100;
const MAX_QUERY_LENGTH = 200;

export interface ActiveSessionRef {
  path: string;
  paneId: string;
  workspaceId: string;
  status: string;
  title?: string;
}

export interface CatalogSession {
  id: string;
  path: string;
  cwd: string;
  title: string;
  preview: string;
  createdAt: number;
  updatedAt: number;
  model: string | null;
  active: boolean;
  paneId: string | null;
  workspaceId: string | null;
  status: string;
}

export interface SessionCatalog {
  sessions: CatalogSession[];
  truncated: boolean;
}

export interface ListSessionCatalogOptions {
  root?: string;
  active?: ActiveSessionRef[];
  query?: string;
  limit?: number;
}

interface SessionFile {
  path: string;
  mtimeMs: number;
}

interface ParsedCatalogFile {
  id: string;
  cwd: string;
  title: string;
  preview: string;
  createdAt: number;
  model: string | null;
}

export class SessionCatalogError extends BridgeError {
  constructor(message: string, status = 400) {
    super(message, status);
  }
}

export function sessionCatalogRoot(): string {
  const sessionRoot = process.env.PI_CODING_AGENT_SESSION_DIR?.trim();
  if (sessionRoot) return resolve(sessionRoot);
  const agentRoot = process.env.PI_CODING_AGENT_DIR?.trim() || `${process.env.HOME ?? ""}/.pi/agent`;
  return resolve(agentRoot, "sessions");
}

export async function resolveCatalogSessionPath(path: string, requestedRoot?: string): Promise<string> {
  const root = await realpath(resolve(requestedRoot ?? sessionCatalogRoot())).catch(() => {
    throw new SessionCatalogError("session store is unavailable", 404);
  });
  const target = await realpath(resolve(path)).catch(() => {
    throw new SessionCatalogError("session not found", 404);
  });
  if (!isInside(root, target) || !target.endsWith(".jsonl")) {
    throw new SessionCatalogError("session path is outside the session store", 403);
  }
  return target;
}

export async function renameStoredSession(path: string, name: string, root?: string): Promise<void> {
  const cleanName = name.trim();
  if (!cleanName || cleanName.length > MAX_SESSION_TITLE_LENGTH || /[\u0000-\u001f\u007f]/.test(cleanName)) {
    throw new SessionCatalogError(`name must be 1 to ${MAX_SESSION_TITLE_LENGTH} printable characters`);
  }
  await writeSessionTitle(await resolveCatalogSessionPath(path, root), cleanName);
}

export async function deleteStoredSession(path: string, root?: string): Promise<void> {
  await unlink(await resolveCatalogSessionPath(path, root));
}

/** List bounded pi session metadata and join it with Herdr's live pane state. */
export async function listSessionCatalog(options: ListSessionCatalogOptions = {}): Promise<SessionCatalog> {
  const limit = options.limit ?? DEFAULT_LIMIT;
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
    throw new SessionCatalogError(`limit must be an integer from 1 to ${MAX_LIMIT}`);
  }
  const query = options.query?.trim() ?? "";
  if (query.length > MAX_QUERY_LENGTH || /[\u0000-\u001f\u007f]/.test(query)) {
    throw new SessionCatalogError("invalid query");
  }

  let root: string;
  try {
    root = await realpath(resolve(options.root ?? sessionCatalogRoot()));
  } catch {
    return { sessions: [], truncated: false };
  }

  const activeByPath = new Map<string, ActiveSessionRef>();
  for (const active of options.active ?? []) {
    try {
      const path = await realpath(active.path);
      if (isInside(root, path)) activeByPath.set(path, active);
    } catch {
      // A live pane may not have created its session file yet.
    }
  }

  const candidates = await findSessionFiles(root);
  const paths = new Map(candidates.map((file) => [file.path, file]));
  for (const path of activeByPath.keys()) {
    if (paths.has(path)) continue;
    const info = await stat(path);
    if (info.isFile()) paths.set(path, { path, mtimeMs: info.mtimeMs });
  }

  const files = [...paths.values()].sort((a, b) => b.mtimeMs - a.mtimeMs);
  const scanned = files.slice(0, MAX_SCANNED_FILES);
  const sessions: CatalogSession[] = [];
  const needle = query.toLocaleLowerCase();

  for (const file of scanned) {
    const parsed = await readCatalogFile(file).catch(() => null);
    if (!parsed) continue;
    const active = activeByPath.get(file.path);
    const session: CatalogSession = {
      id: parsed.id,
      path: file.path,
      cwd: parsed.cwd,
      title: active?.title?.trim().slice(0, MAX_SESSION_TITLE_LENGTH) || parsed.title,
      preview: parsed.preview,
      createdAt: parsed.createdAt,
      updatedAt: file.mtimeMs,
      model: parsed.model,
      active: active !== undefined,
      paneId: active?.paneId ?? null,
      workspaceId: active?.workspaceId ?? null,
      status: active?.status ?? "completed",
    };
    if (!needle || catalogSearchText(session).includes(needle)) sessions.push(session);
  }

  return {
    sessions: sessions.slice(0, limit),
    truncated: candidates.length >= MAX_CANDIDATES || files.length > MAX_SCANNED_FILES || sessions.length > limit,
  };
}

async function findSessionFiles(root: string): Promise<SessionFile[]> {
  const files: SessionFile[] = [];
  const rootEntries = await readdir(root, { withFileTypes: true });
  for (const entry of rootEntries) {
    if (files.length >= MAX_CANDIDATES) break;
    if (entry.name.startsWith(".")) continue;
    const path = resolve(root, entry.name);
    if (entry.isFile() && entry.name.endsWith(".jsonl")) {
      await addSessionFile(root, path, files);
      continue;
    }
    if (!entry.isDirectory()) continue;
    const children = await readdir(path, { withFileTypes: true });
    for (const child of children) {
      if (files.length >= MAX_CANDIDATES) break;
      if (child.isFile() && child.name.endsWith(".jsonl")) {
        await addSessionFile(root, resolve(path, child.name), files);
      }
    }
  }
  return files;
}

async function addSessionFile(root: string, path: string, files: SessionFile[]): Promise<void> {
  const canonical = await realpath(path);
  if (!isInside(root, canonical)) return;
  const info = await stat(canonical);
  if (info.isFile()) files.push({ path: canonical, mtimeMs: info.mtimeMs });
}

/**
 * List metadata for one session file. The transcript module does the reading —
 * bounded to a window at each end of the file — so the catalog understands the
 * JSONL vocabulary exactly as the chat and board views do.
 */
async function readCatalogFile(file: SessionFile): Promise<ParsedCatalogFile | null> {
  const transcript = await readTranscript(file.path, { metadataOnly: true });
  if (!transcript.id || !transcript.cwd) return null;
  const createdAt = Date.parse(transcript.timestamp);
  const preview = transcript.preview;
  return {
    id: transcript.id,
    cwd: transcript.cwd,
    title:
      transcript.title
      || preview.slice(0, MAX_SESSION_TITLE_LENGTH)
      || basename(transcript.cwd)
      || "Untitled session",
    preview,
    createdAt: Number.isFinite(createdAt) ? createdAt : file.mtimeMs,
    model: transcript.model,
  };
}

function catalogSearchText(session: CatalogSession): string {
  return `${session.title}\n${session.preview}\n${session.cwd}\n${session.model ?? ""}`.toLocaleLowerCase();
}

function isInside(root: string, target: string): boolean {
  const path = relative(root, target);
  return path !== "" && !path.startsWith("..") && !isAbsolute(path);
}
