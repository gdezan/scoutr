/**
 * Shared OAuth refresh-token exchange for the usage providers.
 *
 * Each provider differs only in endpoint, client id, and body encoding, so the
 * request/response handling lives here and the providers supply the rest.
 */
import * as v from "valibot";

export interface RefreshedToken {
  access: string;
  /** Providers rotate this; callers MUST persist it. Falls back to the sent token if omitted. */
  refresh?: string;
  /** Absolute epoch ms, already skew-adjusted. */
  expires: number;
}

const tokenResponseSchema = v.looseObject({
  access_token: v.optional(v.string()),
  refresh_token: v.optional(v.string()),
  expires_in: v.optional(v.union([v.number(), v.string()])),
});

export interface RefreshRequest {
  /** Tried in order; the first that does not 404 wins. Lets an endpoint migration roll out gracefully. */
  urls: string[];
  clientId: string;
  refreshToken: string;
  encoding: "json" | "form";
  label: string;
  /** Shown when the provider rejects the refresh token outright — the only fix is a human re-login. */
  reauthHint: string;
  signal?: AbortSignal;
}

/** Refresh slightly early so a token cannot expire between the check and the request. */
export const TOKEN_SKEW_MS = 5 * 60 * 1000;

/**
 * True when `expires` (epoch ms) is close enough to now to refresh pre-emptively.
 *
 * A missing or unparseable expiry is treated as *not* expiring: the token may
 * well be good, and spending a refresh (which rotates the stored token) on a
 * guess is worse than letting the 401 retry path handle it.
 */
export function isExpiring(expires: number | undefined): boolean {
  if (expires === undefined || !Number.isFinite(expires)) return false;
  return expires - TOKEN_SKEW_MS <= Date.now();
}

export async function requestTokenRefresh(request: RefreshRequest): Promise<RefreshedToken> {
  const payload = {
    grant_type: "refresh_token",
    client_id: request.clientId,
    refresh_token: request.refreshToken,
  };

  let lastError: Error | undefined;
  for (const url of request.urls) {
    const response = await fetch(url, {
      method: "POST",
      signal: request.signal,
      headers: {
        accept: "application/json",
        "content-type": request.encoding === "json" ? "application/json" : "application/x-www-form-urlencoded",
      },
      body: request.encoding === "json" ? JSON.stringify(payload) : new URLSearchParams(payload),
    });

    if (response.status === 404) {
      // Endpoint moved (Anthropic did exactly this); try the next candidate.
      void response.body?.cancel().catch(() => undefined);
      lastError = new Error(`${request.label} token refresh returned 404 from ${url}`);
      continue;
    }
    if (!response.ok) {
      void response.body?.cancel().catch(() => undefined);
      // 400/invalid_grant means the stored refresh token is spent or revoked.
      // No amount of retrying recovers it, so say what actually fixes it.
      if (response.status === 400 || response.status === 401) {
        throw new Error(`${request.label} refresh token is no longer valid; ${request.reauthHint}`);
      }
      throw new Error(`${request.label} token refresh returned ${response.status}`);
    }

    const parsed = v.safeParse(tokenResponseSchema, await response.json());
    if (!parsed.success) {
      throw new Error(`${request.label} token refresh returned no access token`);
    }
    const token = parsed.output;
    const access = token.access_token;
    if (!access) throw new Error(`${request.label} token refresh returned no access token`);
    const expiresIn = Number.isFinite(Number(token.expires_in)) ? Number(token.expires_in) : 3600;
    return {
      access,
      refresh: token.refresh_token ?? request.refreshToken,
      expires: Date.now() + expiresIn * 1000,
    };
  }

  throw lastError ?? new Error(`${request.label} token refresh had no endpoint to try`);
}
