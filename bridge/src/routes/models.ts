import { backendFor } from "../agents/registry.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const modelsRoutes: Route[] = [{ method: "GET", path: "/api/models", handle: models }];

async function models(ctx: RouteContext): Promise<RouteResult> {
  const agent = ctx.query.get("agent") ?? "pi";
  let backend;
  try {
    backend = backendFor(agent);
  } catch {
    return { status: 404, body: { ok: false, error: `unknown agent: ${agent}` } };
  }
  try {
    // Catalog-less backends (e.g. claude) return an empty catalog, never a 404.
    return { status: 200, body: { ok: true, catalog: backend.models() } };
  } catch (error) {
    return {
      status: 404,
      body: { ok: false, error: "model catalog not readable: " + (error instanceof Error ? error.message : String(error)) },
    };
  }
}
