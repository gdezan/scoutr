import type { Route, RouteContext, RouteResult } from "./types.js";

export const usageRoutes: Route[] = [{ method: "GET", path: "/api/usage", handle: usage }];

async function usage(ctx: RouteContext): Promise<RouteResult> {
  return { status: 200, body: { ok: true, usage: await ctx.deps.usage.all() } };
}
