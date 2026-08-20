import { basename, isAbsolute, relative, resolve } from "node:path";
import type { Dirent } from "node:fs";
import { readdir, realpath, stat, unlink } from "node:fs/promises";
import { BridgeError } from "./errors.js";
import { MAX_SESSION_TITLE_LENGTH } from "./transcript.js";
import { backendForSessionPath, knownBackends } from "./agents/registry.js";
import { keyForStoredSession, type SessionDescriptor, type SessionKey } from "./session-model.js";

const MAX_CANDIDATES = 2_000;
const MAX_SCANNED_FILES = 500;
const MAX_LIMIT = 200;
const DEFAULT_LIMIT = 100;
const MAX_QUERY_LENGTH = 200;

export interface ActiveSessionRef {
  path: string;
  paneId: string;
  workspaceId: string;
  tabId: string;
  status: string;
  statusSinceMs?: number;
  title?: string;
}

export interface CatalogSession {
  session: SessionDescriptor;
  createdAtMs: number;
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
  size: number;
}

interface ParsedCatalogFile {
  cwd: string;
  title: string;
  preview: string;
  createdAt: number;
  model: string | null;
  thinkingLevel: string | null;
}

/**
 * Memo of parsed catalog metadata keyed by (mtimeMs, size) — an unchanged
 * store costs 500 stats and zero reads per listSessionCatalog call instead
 * of up to ~96 MB of window reads. The history screen polls every 8s and the
 * command palette per debounced keystroke.
 */
interface CatalogMemoEntry {
  mtimeMs: number;
  size: number;
  parsed: ParsedCatalogFile | null;
}

const CATALOG_MEMO_CAP = 600; // just above MAX_SCANNED_FILES
const catalogMemo = new Map<string, CatalogMemoEntry>();

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

/** Resolve a client-supplied durable key through both its path and backend namespace. */
export async function resolveCatalogSessionKey(key: SessionKey): Promise<{ path: string; backend: NonNullable<ReturnType<typeof backendForSessionPath>> }> {
  if (typeof key?.agentKind !== "string" || !key.agentKind || typeof key?.path !== "string" || !key.path) {
    throw new SessionCatalogError("session key is required");
  }
  const resolved = await resolveCatalogSessionPath(key.path);
  if (resolved.backend.id !== key.agentKind) {
    throw new SessionCatalogError("session key backend does not own path", 403);
  }
  return resolved;
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
    try {
      const info = await stat(path);
      if (info.isFile()) paths.set(path, { path, mtimeMs: info.mtimeMs, size: info.size });
    } catch {
      continue; // not on disk yet — treat as inactive
    }
  }

  const files = [...paths.values()].sort((a, b) => b.mtimeMs - a.mtimeMs);
  const scanned = files.slice(0, MAX_SCANNED_FILES);
  const sessions: CatalogSession[] = [];
  const needle = query.toLocaleLowerCase();

  for (const file of scanned) {
    const parsed = await readCatalogFile(file).catch(() => null);
    if (!parsed) continue;
    const active = activeByPath.get(file.path);
    const backend = backendForSessionPath(file.path);
    if (!backend) continue;
    const key = keyForStoredSession(backend, file.path);
    if (!key) continue;
    const session: CatalogSession = {
      session: {
        key,
        agentKind: key.agentKind,
        displayName: backend.displayName,
        title: active?.title?.trim() || parsed.title,
        cwd: parsed.cwd,
        model: parsed.model,
        thinkingLevel: parsed.thinkingLevel,
        capabilities: [...backend.capabilities],
        updatedAtMs: file.mtimeMs,
        transcriptMtimeMs: file.mtimeMs,
        transcriptSize: file.size,
        latestActivity: parsed.preview || null,
        // The catalog is a history surface: it reads stored files, not the
        // board's live ask state, so it never claims a session wants the user.
        attention: null,
        live: active ? {
          paneId: active.paneId,
          workspaceId: active.workspaceId,
          tabId: active.tabId,
          status: active.status,
          statusSinceMs: active.statusSinceMs ?? null,
        } : null,
      },
      createdAtMs: parsed.createdAt,
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
  // store (e.g. pi) can never starve another one (e.g. claude).
  const perRoot = Math.max(1, Math.floor(MAX_CANDIDATES / Math.max(1, roots.length)));
  let truncated = false;
  for (const root of roots) {
    let canonicalRoot: string;
    try {
      canonicalRoot = await realpath(resolve(root));
    } catch {
      continue; // a missing store is an empty store
    }
    // Each root is scanned into its own bucket and trimmed *by recency*, not by
    // directory order. Cutting the walk short instead dropped whichever files
    // readdir happened to hand over last, which is how a claude store of ~1000
    // transcripts hid today's sessions behind months-old ones.
    const rootFiles: SessionFile[] = [];
    await scanRoot(canonicalRoot, rootFiles, MAX_CANDIDATES);
    if (rootFiles.length >= MAX_CANDIDATES) truncated = true;
    rootFiles.sort((a, b) => b.mtimeMs - a.mtimeMs);
    if (rootFiles.length > perRoot) {
      rootFiles.length = perRoot;
      truncated = true;
    }
    files.push(...rootFiles);
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
    // AGY stores sessions at <conversation-id>/.system_generated/logs/transcript.jsonl
    await addSessionFile(root, resolve(path, ".system_generated", "logs", "transcript.jsonl"), files);
    let children: Dirent[];
    try {
      children = await readdir(path, { withFileTypes: true });
    } catch {
      continue; // unreadable directory — skip it
    }
    for (const child of children) {
      if (files.length >= stopAt) break;
      if (child.isFile() && child.name.endsWith(".jsonl")) {
        await addSessionFile(root, resolve(path, child.name), files);
      }
    }
  }
}

async function addSessionFile(root: string, path: string, files: SessionFile[]): Promise<void> {
  try {
    const canonical = await realpath(path);
    if (!isInside(root, canonical)) return;
    const info = await stat(canonical);
    if (info.isFile()) files.push({ path: canonical, mtimeMs: info.mtimeMs, size: info.size });
  } catch {
    // Vanished file, dangling link, EPERM: a bad entry must never abort the
    // whole scan (that 500s the Sessions tab).
  }
}

/**
 * List metadata for one session file through its owning backend. The backend
 * does the reading — bounded to a window at each end of the file — so the
 * catalog understands each agent's JSONL vocabulary exactly as the chat and
 * board views do.
 */
async function readCatalogFile(file: SessionFile): Promise<ParsedCatalogFile | null> {
  const backend = backendForSessionPath(file.path);
  if (!backend) return null;
  const cached = catalogMemo.get(file.path);
  if (cached && cached.mtimeMs === file.mtimeMs && cached.size === file.size) {
    return cached.parsed;
  }
  const transcript = await backend.readTranscript(file.path, { metadataOnly: true });
  let parsed: ParsedCatalogFile | null = null;
  if (transcript.id && transcript.cwd) {
    const createdAt = Date.parse(transcript.timestamp);
    const preview = transcript.preview;
    parsed = {
      cwd: transcript.cwd,
      title:
        transcript.title
        || preview.slice(0, MAX_SESSION_TITLE_LENGTH)
        || basename(transcript.cwd)
        || "Untitled session",
      preview,
      createdAt: Number.isFinite(createdAt) ? createdAt : file.mtimeMs,
      model: transcript.model,
      thinkingLevel: transcript.thinkingLevel,
    };
  }
  catalogMemo.set(file.path, { mtimeMs: file.mtimeMs, size: file.size, parsed });
  if (catalogMemo.size > CATALOG_MEMO_CAP) {
    const oldest = catalogMemo.keys().next().value;
    if (oldest !== undefined) catalogMemo.delete(oldest);
  }
  return parsed;
}

function catalogSearchText(session: CatalogSession): string {
  const descriptor = session.session;
  return `${descriptor.title}\n${descriptor.latestActivity ?? ""}\n${descriptor.cwd ?? ""}\n${descriptor.model ?? ""}`.toLocaleLowerCase();
}

function isInside(root: string, target: string): boolean {
  const path = relative(root, target);
  return path !== "" && !path.startsWith("..") && !isAbsolute(path);
}
