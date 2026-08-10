import { basename, isAbsolute, relative, resolve } from "node:path";
import { appendFile, open, readdir, realpath, stat, unlink } from "node:fs/promises";
import { randomBytes } from "node:crypto";

const MAX_CANDIDATES = 2_000;
const MAX_SCANNED_FILES = 500;
const MAX_HEAD_BYTES = 128 * 1024;
const MAX_TAIL_BYTES = 64 * 1024;
const MAX_LIMIT = 200;
const DEFAULT_LIMIT = 100;
const MAX_QUERY_LENGTH = 200;
const MAX_TITLE_LENGTH = 100;
const MAX_PREVIEW_LENGTH = 240;

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
  size: number;
}

interface ParsedCatalogFile {
  id: string;
  cwd: string;
  title: string;
  preview: string;
  createdAt: number;
  model: string | null;
}

export class SessionCatalogError extends Error {
  constructor(message: string, public readonly status = 400) {
    super(message);
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
  if (!cleanName || cleanName.length > MAX_TITLE_LENGTH || /[\u0000-\u001f\u007f]/.test(cleanName)) {
    throw new SessionCatalogError(`name must be 1 to ${MAX_TITLE_LENGTH} printable characters`);
  }
  const target = await resolveCatalogSessionPath(path, root);
  const record = {
    type: "session_info",
    id: randomBytes(4).toString("hex"),
    parentId: null,
    timestamp: new Date().toISOString(),
    name: cleanName,
  };
  await appendFile(target, `${JSON.stringify(record)}\n`, "utf8");
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
    if (info.isFile()) paths.set(path, { path, mtimeMs: info.mtimeMs, size: info.size });
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
      title: active?.title?.trim().slice(0, MAX_TITLE_LENGTH) || parsed.title,
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
  if (info.isFile()) files.push({ path: canonical, mtimeMs: info.mtimeMs, size: info.size });
}

async function readCatalogFile(file: SessionFile): Promise<ParsedCatalogFile | null> {
  const handle = await open(file.path, "r");
  try {
    const headSize = Math.min(file.size, MAX_HEAD_BYTES);
    const head = Buffer.alloc(headSize);
    await handle.read(head, 0, headSize, 0);

    const tailStart = Math.max(headSize, file.size - MAX_TAIL_BYTES);
    const tailSize = Math.max(0, file.size - tailStart);
    const tail = Buffer.alloc(tailSize);
    if (tailSize > 0) await handle.read(tail, 0, tailSize, tailStart);

    return parseCatalogText(`${head.toString("utf8")}\n${tail.toString("utf8")}`, file.mtimeMs);
  } finally {
    await handle.close();
  }
}

function parseCatalogText(text: string, mtimeMs: number): ParsedCatalogFile | null {
  let id = "";
  let cwd = "";
  let createdAt = mtimeMs;
  let name = "";
  let preview = "";
  let model: string | null = null;

  for (const rawLine of text.split("\n")) {
    let record: Record<string, unknown>;
    try {
      record = JSON.parse(rawLine) as Record<string, unknown>;
    } catch {
      continue;
    }
    if (record.type === "session") {
      id = typeof record.id === "string" ? record.id : id;
      cwd = typeof record.cwd === "string" ? record.cwd : cwd;
      const timestamp = typeof record.timestamp === "string" ? Date.parse(record.timestamp) : Number.NaN;
      if (Number.isFinite(timestamp)) createdAt = timestamp;
    } else if (record.type === "session_info" && typeof record.name === "string") {
      name = cleanText(record.name, MAX_TITLE_LENGTH);
    } else if (record.type === "model_change") {
      const provider = typeof record.provider === "string" ? record.provider : "";
      const modelId = typeof record.modelId === "string" ? record.modelId : "";
      if (provider && modelId) model = `${provider}/${modelId}`;
    } else if (!preview && record.type === "message") {
      preview = firstUserText(record);
    }
  }

  if (!id || !cwd) return null;
  const title = name || cleanText(preview, MAX_TITLE_LENGTH) || basename(cwd) || "Untitled session";
  return { id, cwd, title, preview, createdAt, model };
}

function firstUserText(record: Record<string, unknown>): string {
  const message = record.message;
  if (!message || typeof message !== "object" || Array.isArray(message)) return "";
  const value = message as Record<string, unknown>;
  if (value.role !== "user") return "";
  if (typeof value.content === "string") return cleanText(value.content, MAX_PREVIEW_LENGTH);
  if (!Array.isArray(value.content)) return "";
  const text = value.content
    .filter((block): block is Record<string, unknown> => Boolean(block) && typeof block === "object" && !Array.isArray(block))
    .filter((block) => block.type === "text" && typeof block.text === "string")
    .map((block) => block.text as string)
    .join(" ");
  return cleanText(text, MAX_PREVIEW_LENGTH);
}

function cleanText(value: string, limit: number): string {
  return value.replace(/\s+/g, " ").trim().slice(0, limit);
}

function catalogSearchText(session: CatalogSession): string {
  return `${session.title}\n${session.preview}\n${session.cwd}\n${session.model ?? ""}`.toLocaleLowerCase();
}

function isInside(root: string, target: string): boolean {
  const path = relative(root, target);
  return path !== "" && !path.startsWith("..") && !isAbsolute(path);
}
