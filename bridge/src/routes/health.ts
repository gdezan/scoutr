import type { SessionSnapshot } from "../herdr/types.js";
import { SCOUTR_API_FEATURES, SCOUTR_API_PROTOCOL } from "../api-protocol.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const healthRoutes: Route[] = [
  { method: "GET", path: "/api/health", handle: health },
  { method: "GET", path: "/api/snapshot", handle: snapshot },
];

async function health(ctx: RouteContext): Promise<RouteResult> {
  const { herdr, config, terminalBroker } = ctx.deps;
  let herdrConnected = false;
  let herdrVersion = "";
  let herdrProtocol: number | undefined;
  try {
    const pong = await herdr.ping();
    herdrConnected = true;
    herdrVersion = pong.version;
    herdrProtocol = pong.protocol;
  } catch {
    herdrConnected = false;
  }
  return {
    status: 200,
    body: {
      ok: true,
      service: "scoutr-bridge",
      version: "0.1.0",
      api: { protocol: SCOUTR_API_PROTOCOL, features: [...SCOUTR_API_FEATURES] },
      herdr: { connected: herdrConnected, version: herdrVersion, protocol: herdrProtocol },
      terminal: { capability: terminalBroker.capability() },
      push: { fcm: Boolean(config.fcmServiceAccountPath) },
    },
  };
}

function snapshot(ctx: RouteContext): RouteResult {
  const current = ctx.deps.feed.snapshot as SessionSnapshot | null;
  if (!current) {
    return { status: 503, body: { ok: false, error: "no herdr snapshot yet" } };
  }
  return { status: 200, body: { ok: true, snapshot: current } };
}
