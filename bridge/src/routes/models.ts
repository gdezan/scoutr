import { readModelsCatalog } from "../pi/models.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const modelsRoutes: Route[] = [{ method: "GET", path: "/api/models", handle: models }];

async function models(_ctx: RouteContext): Promise<RouteResult> {
  try {
    return { status: 200, body: { ok: true, catalog: readModelsCatalog() } };
  } catch (error) {
    return {
      status: 404,
      body: { ok: false, error: "models-store.json not readable: " + (error instanceof Error ? error.message : String(error)) },
    };
  }
}
