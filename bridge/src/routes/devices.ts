import { isProfileGeneration } from "../push/fcm.js";
import { MAX_TOKEN_LENGTH } from "../push/devices.js";
import type { Route, RouteContext, RouteResult } from "./types.js";
import * as v from "valibot";

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
  { method: "POST", path: "/api/devices/unregister", handle: unregisterDevice },
];

async function registerDevice(ctx: RouteContext): Promise<RouteResult> {
  const token = validateToken(ctx);
  if ("error" in token) return token.error;
  const generation = validateGeneration(ctx);
  if ("error" in generation) return generation.error;
  const { devices } = ctx.deps;
  if (!devices) return pushUnavailable();
  await devices.register(token.value, generation.value);
  return { status: 200, body: { ok: true } };
}

async function unregisterDevice(ctx: RouteContext): Promise<RouteResult> {
  const token = validateToken(ctx);
  if ("error" in token) return token.error;
  const { devices } = ctx.deps;
  if (!devices) return pushUnavailable();
  // DeviceRegistry.unregister is intentionally idempotent: forgetting a token
  // that was already removed is still a successful cleanup.
  await devices.unregister(token.value);
  return { status: 200, body: { ok: true } };
}

type Validated<T> = { value: T } | { error: RouteResult };

function validateToken(ctx: RouteContext): Validated<string> {
  const parsed = v.safeParse(v.string(), ctx.body.fcmToken);
  if (!parsed.success || !parsed.output.trim()) {
    return { error: { status: 400, body: { ok: false, error: "fcmToken is required" } } };
  }
  const token = parsed.output;
  if (token.length > MAX_TOKEN_LENGTH) {
    return {
      error: { status: 400, body: { ok: false, error: `fcmToken exceeds ${MAX_TOKEN_LENGTH} characters` } },
    };
  }
  return { value: token };
}

function validateGeneration(ctx: RouteContext): Validated<string | undefined> {
  const generation = ctx.body.profileGeneration;
  if (generation === undefined) return { value: undefined };
  const parsed = v.safeParse(v.pipe(v.string(), v.regex(/^[1-9][0-9]*$/)), generation);
  if (!parsed.success || !isProfileGeneration(parsed.output)) {
    return {
      error: {
        status: 400,
        body: { ok: false, error: "profileGeneration must be a positive decimal string" },
      },
    };
  }
  return { value: parsed.output };
}

function pushUnavailable(): RouteResult {
  return { status: 503, body: { ok: false, error: "push is not configured on this bridge" } };
}
