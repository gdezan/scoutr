import { canonicalPath } from "../dirs.js";
import { BridgeError } from "../errors.js";
import { FILE_HEAD_MAX_BYTES } from "../file-head.js";
import { listFiles, readWorkspaceFile, statWorkspaceFile, type FileReadOptions } from "../files.js";
import type { SessionSnapshot } from "../herdr/types.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const filesRoutes: Route[] = [
  { method: "GET", path: "/api/files", handle: files },
  { method: "GET", path: "/api/file", handle: file },
  { method: "GET", path: "/api/file/bytes", handle: fileBytes },
];

/**
 * Candidate paths for the composer's `@` completion. Authorization matches
 * /api/commands: the cwd must belong to an agent in the current snapshot, so
 * the phone can only enumerate the workspace of a session it is looking at.
 */
async function files(ctx: RouteContext): Promise<RouteResult> {
  const cwd = ctx.query.get("cwd");
  if (!cwd) return { status: 400, body: { ok: false, error: "missing cwd" } };
  if (cwd.length > 4096 || /\p{Cc}/u.test(cwd)) {
    return { status: 400, body: { ok: false, error: "invalid cwd" } };
  }
  const cwds = activeAgentCwds(ctx.deps.feed.snapshot);
  if (!cwds.includes(canonicalPath(cwd))) {
    return { status: 403, body: { ok: false, error: "cwd is not attached to an active agent" } };
  }
  try {
    return {
      status: 200,
      body: { ok: true, listing: await listFiles(cwd, ctx.query.get("hidden") === "1") },
    };
  } catch (error) {
    const status = error instanceof BridgeError ? error.status : 500;
    return {
      status,
      body: { ok: false, error: error instanceof Error ? error.message : String(error) },
    };
  }
}

async function file(ctx: RouteContext): Promise<RouteResult> {
  const requested = ctx.query.get("path");
  if (!requested) return { status: 400, body: { ok: false, error: "missing path" } };
  const offset = parsePageNumber(ctx.query.get("offset"));
  const limit = parsePageLimit(ctx.query.get("limit"));
  if (offset === -1 || limit === -1) {
    return { status: 400, body: { ok: false, error: "invalid file page" } };
  }
  const options: FileReadOptions | undefined = offset === undefined && limit === undefined
    ? undefined
    : { offset: offset ?? 0, limit: limit ?? FILE_HEAD_MAX_BYTES };
  const cwds = activeAgentCwds(ctx.deps.feed.snapshot);
  try {
    return { status: 200, body: { ok: true, ...readWorkspaceFile(requested, cwds, options) } };
  } catch (error) {
    const status = error instanceof BridgeError ? error.status : 500;
    return {
      status,
      body: { ok: false, error: error instanceof Error ? error.message : String(error) },
    };
  }
}

/**
 * Raw workspace bytes for the phone's viewers (images, HTML handoff, PDFs).
 * Same workspace containment as /api/file; the wire layer (sendFile) owns
 * Range, so resume works without the route knowing about headers.
 * Missing paths and non-files read 404, oversized files 413 — the phone
 * triages on those before downloading.
 */
async function fileBytes(ctx: RouteContext): Promise<RouteResult> {
  const requested = ctx.query.get("path");
  if (!requested) return { status: 400, body: { ok: false, error: "missing path" } };
  const cwds = activeAgentCwds(ctx.deps.feed.snapshot);
  try {
    const stat = statWorkspaceFile(requested, cwds);
    return {
      status: 200,
      body: { ok: true },
      file: { path: stat.path, size: stat.sizeBytes, contentType: stat.mime, filename: stat.filename },
    };
  } catch (error) {
    const status = error instanceof BridgeError ? error.status : 500;
    return {
      status,
      body: { ok: false, error: error instanceof Error ? error.message : String(error) },
    };
  }
}

function parsePageNumber(value: string | null): number | undefined {
  if (value === null) return undefined;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : -1;
}

function parsePageLimit(value: string | null): number | undefined {
  if (value === null) return undefined;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 && parsed <= FILE_HEAD_MAX_BYTES ? parsed : -1;
}

function activeAgentCwds(snapshot: SessionSnapshot | null): string[] {
  if (!snapshot) return [];
  return snapshot.agents
    .map((agent) => agent.cwd)
    .filter((cwd): cwd is string => Boolean(cwd))
    .map((cwd) => canonicalPath(cwd));
}
