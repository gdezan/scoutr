import { canonicalPath } from "../dirs.js";
import type { SessionSnapshot } from "../herdr/types.js";
import { backendFor } from "../agents/registry.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const commandsRoutes: Route[] = [{ method: "GET", path: "/api/commands", handle: commands }];

async function commands(ctx: RouteContext): Promise<RouteResult> {
  const cwd = ctx.query.get("cwd") ?? undefined;
  if (cwd && (cwd.length > 4096 || /[\u0000-\u001f\u007f]/.test(cwd))) {
    return { status: 400, body: { ok: false, error: "invalid cwd" } };
  }
  if (cwd) {
    const snapshot = ctx.deps.feed.snapshot;
    const requestedCwd = canonicalPath(cwd);
    const belongsToActiveAgent = snapshot && snapshot.agents.some((agent) => (
      agent.cwd !== null && canonicalPath(agent.cwd) === requestedCwd
    ));
    if (!belongsToActiveAgent) {
      return { status: 403, body: { ok: false, error: "cwd is not attached to an active agent" } };
    }
  }
  const agent = ctx.query.get("agent") ?? "pi";
  let backend;
  try {
    backend = backendFor(agent);
  } catch {
    return { status: 404, body: { ok: false, error: `unknown agent: ${agent}` } };
  }
  // Catalog-less backends (e.g. claude) return an empty catalog, never a 404.
  return { status: 200, body: { ok: true, catalog: await backend.commands(cwd) } };
}
