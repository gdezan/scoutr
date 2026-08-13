import { canonicalPath } from "../dirs.js";
import { listFiles, FileListingError } from "../files.js";
import type { SessionSnapshot } from "../herdr/types.js";
import { deriveAgentCards } from "./agents.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const filesRoutes: Route[] = [{ method: "GET", path: "/api/files", handle: files }];

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
  const snapshot = ctx.deps.feed.snapshot as SessionSnapshot | null;
  const requestedCwd = canonicalPath(cwd);
  const belongsToActiveAgent = snapshot && deriveAgentCards(snapshot).some((agent) => (
    agent.cwd !== undefined && canonicalPath(agent.cwd) === requestedCwd
  ));
  if (!belongsToActiveAgent) {
    return { status: 403, body: { ok: false, error: "cwd is not attached to an active agent" } };
  }
  try {
    return { status: 200, body: { ok: true, listing: await listFiles(cwd) } };
  } catch (error) {
    const status = error instanceof FileListingError ? error.status : 500;
    return {
      status,
      body: { ok: false, error: error instanceof Error ? error.message : String(error) },
    };
  }
}
