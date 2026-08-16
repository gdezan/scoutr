import { canonicalPath } from "../dirs.js";
import { BridgeError } from "../errors.js";
import { listFiles, readWorkspaceFile } from "../files.js";
import type { SessionSnapshot } from "../herdr/types.js";
import { deriveAgentCards } from "./agents.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const filesRoutes: Route[] = [
  { method: "GET", path: "/api/files", handle: files },
  { method: "GET", path: "/api/file", handle: file },
];

/**
 * Candidate paths for the composer's `@` completion. Authorization matches
 * /api/commands: the cwd must belong to an agent in the current snapshot, so
 * the phone can only enumerate the workspace of a session it is looking at.
 */
async function files(ctx: RouteContext): Promise<RouteResult> {
  const cwd = ctx.query.get("cwd");
  if (!cwd) return { status: 400, body: { ok: false, error: "missing cwd" } };
  if (cwd.length > 4096 || /[\u0000-\u001f\u007f]/.test(cwd)) {
    return { status: 400, body: { ok: false, error: "invalid cwd" } };
  }
  const cwds = activeAgentCwds(ctx.deps.feed.snapshot as SessionSnapshot | null);
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
  const cwds = activeAgentCwds(ctx.deps.feed.snapshot as SessionSnapshot | null);
  try {
    return { status: 200, body: { ok: true, ...readWorkspaceFile(requested, cwds) } };
  } catch (error) {
    const status = error instanceof BridgeError ? error.status : 500;
    return {
      status,
      body: { ok: false, error: error instanceof Error ? error.message : String(error) },
    };
  }
}

function activeAgentCwds(snapshot: SessionSnapshot | null): string[] {
  if (!snapshot) return [];
  return deriveAgentCards(snapshot)
    .map((agent) => agent.cwd)
    .filter((cwd): cwd is string => Boolean(cwd))
    .map((cwd) => canonicalPath(cwd));
}
