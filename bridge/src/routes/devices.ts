import { MAX_TOKEN_LENGTH } from "../push/devices.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

/**
 * Where the phone tells the bridge how to reach it.
 *
 * The app POSTs here on launch and again from `onNewToken`, so registration is
 * idempotent by design. Tokens are opaque to us: validate that one is present
 * and not absurd, then store it verbatim — parsing it would only invent a
 * format Google is free to change.
 */

export const devicesRoutes: Route[] = [
  { method: "POST", path: "/api/devices", handle: registerDevice },
];

async function registerDevice(ctx: RouteContext): Promise<RouteResult> {
  const { devices } = ctx.deps;
  const token = ctx.body.fcmToken;
  if (typeof token !== "string" || !token.trim()) {
    return { status: 400, body: { ok: false, error: "fcmToken is required" } };
  }
  if (token.length > MAX_TOKEN_LENGTH) {
    return { status: 400, body: { ok: false, error: `fcmToken exceeds ${MAX_TOKEN_LENGTH} characters` } };
  }
  if (!devices) {
    return { status: 503, body: { ok: false, error: "push is not configured on this bridge" } };
  }
  await devices.register(token);
  return { status: 200, body: { ok: true } };
}
