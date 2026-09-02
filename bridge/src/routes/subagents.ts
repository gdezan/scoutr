import { BridgeError } from "../errors.js";
import { isValidPiSubagentRunId, readPiSubagentProgress } from "../pi-subagents/run-store.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const subagentsRoutes: Route[] = [
  { method: "GET", path: "/api/subagents/:runId", handle: subagentProgress },
];

/** One PI-workflow run's progress.json + result.json, joined by runId. */
async function subagentProgress(ctx: RouteContext): Promise<RouteResult> {
  const runId = decodeURIComponent(ctx.params.runId ?? "");
  if (!isValidPiSubagentRunId(runId)) {
    throw new BridgeError("invalid run id", 400);
  }
  const progress = await readPiSubagentProgress(runId);
  if (!progress) {
    throw new BridgeError("run not found", 404);
  }
  return { status: 200, body: { ok: true, ...progress } };
}
