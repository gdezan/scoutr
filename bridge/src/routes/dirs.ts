import { listDirs, DirListingError } from "../dirs.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const dirsRoutes: Route[] = [{ method: "GET", path: "/api/dirs", handle: dirs }];

async function dirs(ctx: RouteContext): Promise<RouteResult> {
  const requested = ctx.query.get("path") ?? undefined;
  try {
    return { status: 200, body: { ok: true, listing: listDirs(requested ?? process.env.HOME ?? "") } };
  } catch (error) {
    const status = error instanceof DirListingError ? 400 : 500;
    return {
      status,
      body: { ok: false, error: error instanceof Error ? error.message : String(error) },
    };
  }
}
