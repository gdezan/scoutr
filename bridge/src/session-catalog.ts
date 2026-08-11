import { basename, isAbsolute, relative, resolve } from "node:path";
import { readdir, realpath, stat, unlink } from "node:fs/promises";
import { BridgeError } from "./errors.js";
import { MAX_SESSION_TITLE_LENGTH } from "./transcript.js";
import { backendForSessionPath, knownBackends } from "./agents/registry.js";

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
  agentKind: string;
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
  /** Roots to scan; defaults to every registered backend's session root. */
  roots?: string[];
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

/** The pi session root (env-honoring), kept for tests and env-based setups. */
export function sessionCatalogRoot(): string {
  const sessionRoot = process.env.PI_CODING_AGENT_SESSION_DIR?.trim();
  if (sessionRoot) return resolve(sessionRoot);
  const agentRoot = process.env.PI_CODING_AGENT_DIR?.trim() || `${process.env.HOME ?? ""}/.pi/agent`;
  return resolve(agentRoot, "sessions");
}

/** Every registered backend's session store, deduplicated. */
export function sessionCatalogRoots(): string[] {
  return [...new Set(knownBackends().map((backend) => resolve(backend.sessionRoot())))];
}

/**
 * Canonicalize a session path and require a registered backend to claim it.
 * The claiming backend owns the read; this keeps the least-privilege property
 * while making the allow-list exactly the registered adapters.
 */
export async function resolveCatalogSessionPath(path: string): Promise<{ path: string; backend: NonNullable<ReturnType<typeof backendForSessionPath>> }> {
  const target = await realpath(resolve(path)).catch(() => {
    throw new SessionCatalogError("session not found", 404);
  });
  const backend = backendForSessionPath(target);
  if (!backend) {
    throw new SessionCatalogError("session path is outside a registered session store", 403);
  }
  return { path: target, backend };
}

export async function renameStoredSession(path: string, name: string): Promise<void> {
  const cleanName = name.trim();
  if (!cleanName || cleanName.length > MAX_SESSION_TITLE_LENGTH || /[\u0000-\u001f\u007f]/.test(cleanName)) {
    throw new SessionCatalogError(`name must be 1 to ${MAX_SESSION_TITLE_LENGTH} printable characters`);
  }
  const { path: target, backend } = await resolveCatalogSessionPath(path);
  if (!backend.renameStoredSession) {
    throw new SessionCatalogError(`${backend.displayName} sessions cannot be renamed`, 400);
  }
  await backend.renameStoredSession(target, cleanName);
}

export async function deleteStoredSession(path: string): Promise<void> {
  const { path: target } = await resolveCatalogSessionPath(path);
  await unlink(target);
}

/** List bounded session metadata across every registered backend store, joined with Herdr's live pane state. */
export async function listSessionCatalog(options: ListSessionCatalogOptions = {}): Promise<SessionCatalog> {
  const limit = options.limit ?? DEFAULT_LIMIT;
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
    throw new SessionCatalogError(`limit must be an integer from 1 to ${MAX_LIMIT}`);
  }
  const query = options.query?.trim() ?? "";
  if (query.length > MAX_QUERY_LENGTH || /[\u0000-\u001f\u007f]/.test(query)) {
    throw new SessionCatalogError("invalid query");
  }

  const roots = options.roots ?? sessionCatalogRoots();

  const activeByPath = new Map<string, ActiveSessionRef>();
  for (const active of options.active ?? []) {
    try {
      const path = await realpath(active.path);
      activeByPath.set(path, active);
    } catch {
      // A live pane may not have created its session file yet.
    }
  }

  const { files: candidates, truncated: scanTruncated } = await findSessionFiles(roots);
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
      agentKind: parsed.agentKind,
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
    truncated: scanTruncated || candidates.length >= MAX_CANDIDATES || files.length > MAX_SCANNED_FILES || sessions.length > limit,
  };
}

async function findSessionFiles(roots: string[]): Promise<{ files: SessionFile[]; truncated: boolean }> {
  const files: SessionFile[] = [];
  // Fair per-root budget: the global candidate cap is split evenly so a giant
  // first store (e.g. pi) can never starve later roots (e.g. claude).
  const perRoot = Math.max(1, Math.floor(MAX_CANDIDATES / Math.max(1, roots.length)));
  let truncated = false;
  for (const root of roots) {
    let canonicalRoot: string;
    try {
      canonicalRoot = await realpath(resolve(root));
    } catch {
      continue; // a missing store is an empty store
    }
    const rootStart = files.length;
    await scanRoot(canonicalRoot, files, rootStart + perRoot);
    if (files.length >= rootStart + perRoot) truncated = true;
    if (files.length >= MAX_CANDIDATES) break;
  }
  return { files, truncated };
}

async function scanRoot(root: string, files: SessionFile[], stopAt: number): Promise<void> {
  const rootEntries = await readdir(root, { withFileTypes: true });
  for (const entry of rootEntries) {
    if (files.length >= stopAt) break;
    if (entry.name.startsWith(".")) continue;
    const path = resolve(root, entry.name);
    if (entry.isFile() && entry.name.endsWith(".jsonl")) {
      await addSessionFile(root, path, files);
      continue;
    }
    if (!entry.isDirectory()) continue;
    const children = await readdir(path, { withFileTypes: true });
    for (const child of children) {
      if (files.length >= stopAt) break;
      if (child.isFile() && child.name.endsWith(".jsonl")) {
        await addSessionFile(root, resolve(path, child.name), files);
      }
    }
  }
}

async function addSessionFile(root: string, path: string, files: SessionFile[]): Promise<void> {
  const canonical = await realpath(path);
  if (!isInside(root, canonical)) return;
  const info = await stat(canonical);
  if (info.isFile()) files.push({ path: canonical, mtimeMs: info.mtimeMs });
}

/**
 * List metadata for one session file through its owning backend. The backend
 * does the reading — bounded to a window at each end of the file — so the
 * catalog understands each agent's JSONL vocabulary exactly as the chat and
 * board views do.
 */
async function readCatalogFile(file: SessionFile): Promise<(ParsedCatalogFile & { agentKind: string }) | null> {
  const backend = backendForSessionPath(file.path);
  if (!backend) return null;
  const transcript = await backend.readTranscript(file.path, { metadataOnly: true });
  if (!transcript.id || !transcript.cwd) return null;
  const createdAt = Date.parse(transcript.timestamp);
  const preview = transcript.preview;
  return {
    id: transcript.id,
    agentKind: backend.id,
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
