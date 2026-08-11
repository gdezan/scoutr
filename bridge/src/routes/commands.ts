import { canonicalPath } from "../dirs.js";
import type { SessionSnapshot } from "../herdr/types.js";
import { readCommandsCatalog } from "../pi/commands.js";
import { deriveAgentCards } from "./agents.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const commandsRoutes: Route[] = [{ method: "GET", path: "/api/commands", handle: commands }];

async function commands(ctx: RouteContext): Promise<RouteResult> {
  const cwd = ctx.query.get("cwd") ?? undefined;
  if (cwd && (cwd.length > 4096 || /[\u0000-\u001f\u007f]/.test(cwd))) {
    return { status: 400, body: { ok: false, error: "invalid cwd" } };
  }
  if (cwd) {
    const snapshot = ctx.deps.feed.snapshot as SessionSnapshot | null;
    const requestedCwd = canonicalPath(cwd);
    const belongsToActiveAgent = snapshot && deriveAgentCards(snapshot).some((agent) => (
      agent.cwd !== undefined && canonicalPath(agent.cwd) === requestedCwd
    ));
    if (!belongsToActiveAgent) {
      return { status: 403, body: { ok: false, error: "cwd is not attached to an active agent" } };
    }
  }
  return { status: 200, body: { ok: true, catalog: await readCommandsCatalog(cwd) } };
}
