import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { updateJsonFile } from "./auth.js";
import { isExpiring } from "./oauth.js";
import type { UsageSnapshot, UsageWindow } from "./providers.js";
import { windowSecondsFor } from "./windows.js";

/**
 * Antigravity (Google's agentic CLI, `agy`) quota.
 *
 * Antigravity ships as a Gemini CLI sibling, so its OAuth token lives under
 * ~/.gemini/antigravity-cli/ rather than a directory of its own. Tokens are
 * ordinary Google OAuth, refreshed at oauth2.googleapis.com with credentials
 * supplied through SCOUTR_ANTIGRAVITY_CLIENTS.
 */

const ANTIGRAVITY_TIMEOUT_MS = 10_000;
const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

/**
 * `agy` uses two OAuth clients — one per auth_method (consumer vs managed).
 * Keep these credentials outside the repository and pass them as a JSON array:
 * `[{id:"...", secret:"..."}]`.
 */
function antigravityClients(): Array<{ id: string; secret: string }> {
  const raw = process.env.SCOUTR_ANTIGRAVITY_CLIENTS?.trim();
  if (!raw) throw new Error("SCOUTR_ANTIGRAVITY_CLIENTS is required to refresh Antigravity credentials");
  try {
    const clients: unknown = JSON.parse(raw);
    if (
      !Array.isArray(clients) ||
      clients.length === 0 ||
      clients.some((client) => {
        if (client === null || typeof client !== "object") return true;
        const value = client as Record<string, unknown>;
        return typeof value.id !== "string" || typeof value.secret !== "string";
      })
    ) {
      throw new Error("invalid client list");
    }
    return clients.map((client) => {
      const value = client as { id: string; secret: string };
      return { id: value.id, secret: value.secret };
    });
  } catch {
    throw new Error("SCOUTR_ANTIGRAVITY_CLIENTS must be a JSON array of {id, secret} objects");
  }
}
let workingClientIndex = 0;

/** This install talks to the `daily-` channel; the production host is the fallback. */
const QUOTA_HOSTS = ["https://daily-cloudcode-pa.googleapis.com", "https://cloudcode-pa.googleapis.com"];
const QUOTA_PATH = "/v1internal:retrieveUserQuotaSummary";

type JsonRecord = Record<string, unknown>;

function recordOf(value: unknown): JsonRecord | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? (value as JsonRecord) : undefined;
}

function finite(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function clampPercent(value: number): number {
  return Math.max(0, Math.min(100, value));
}

function resetAtFrom(value: unknown): number | undefined {
  if (typeof value === "number" && Number.isFinite(value)) {
    return Math.round(value > 10_000_000_000 ? value / 1000 : value);
  }
  if (typeof value !== "string" || value.trim() === "") return undefined;
  const parsed = Date.parse(value.trim());
  return Number.isFinite(parsed) ? Math.floor(parsed / 1000) : undefined;
}

export function antigravityTokenPath(): string {
  return join(homedir(), ".gemini", "antigravity-cli", "antigravity-oauth-token");
}

interface AntigravityToken {
  access: string;
  refresh?: string;
  /** Epoch ms, parsed from the Go RFC3339 `expiry` field. */
  expires?: number;
}

async function readAntigravityToken(path: string): Promise<AntigravityToken | undefined> {
  try {
    const root = recordOf(JSON.parse(await readFile(path, "utf8")));
    const token = recordOf(root?.token);
    const access = typeof token?.access_token === "string" ? token.access_token : "";
    if (access === "") return undefined;
    const expiry = typeof token?.expiry === "string" ? Date.parse(token.expiry) : Number.NaN;
    return {
      access,
      refresh: typeof token?.refresh_token === "string" ? token.refresh_token : undefined,
      expires: Number.isFinite(expiry) ? expiry : undefined,
    };
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    throw error;
  }
}

/**
 * Refresh via Google and merge the result back into the token file.
 *
 * Unlike Anthropic and OpenAI, Google does not rotate installed-app refresh
 * tokens, so the write-back here only advances the access token and expiry —
 * but it still matters, otherwise every poll would burn a fresh exchange.
 */
async function refreshAntigravityToken(path: string, token: AntigravityToken): Promise<AntigravityToken> {
  if (!token.refresh) {
    throw new Error("Antigravity OAuth token expired and no refresh token is stored; run `agy` to sign in");
  }
  const clients = antigravityClients();

  const attempt = async (client: { id: string; secret: string }) =>
    fetch(GOOGLE_TOKEN_URL, {
      method: "POST",
      signal: AbortSignal.timeout(ANTIGRAVITY_TIMEOUT_MS),
      headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        refresh_token: token.refresh!,
        client_id: client.id,
        client_secret: client.secret,
      }),
    });

  // Start from the client that worked last time, then fall through to the other.
  const order = [workingClientIndex, ...clients.keys()].filter(
    (index, position, all) => all.indexOf(index) === position,
  );

  let lastDetail = "";
  for (const index of order) {
    const response = await attempt(clients[index]!);
    if (response.ok) {
      const body = (await response.json()) as JsonRecord;
      const access = typeof body.access_token === "string" ? body.access_token : undefined;
      if (!access) throw new Error("Antigravity token refresh returned no access token");
      const expiresIn = finite(body.expires_in) ?? 3600;
      const expires = Date.now() + expiresIn * 1000;
      const refresh = typeof body.refresh_token === "string" ? body.refresh_token : token.refresh;
      workingClientIndex = index;

      await updateJsonFile(path, (root) => {
        const stored = recordOf(root.token) ?? {};
        root.token = {
          ...stored,
          access_token: access,
          refresh_token: refresh,
          expiry: new Date(expires).toISOString(),
        };
        return root;
      });

      return { access, refresh, expires };
    }
    const detail = await response.text().catch(() => "");
    lastDetail = `${response.status}${detail ? `: ${detail.slice(0, 200)}` : ""}`;
    void response.body?.cancel().catch(() => undefined);
  }

  throw new Error(`Antigravity token refresh failed (${lastDetail}); run \`agy\` to sign in`);
}

/**
 * Flatten the quota summary into windows.
 *
 * Shape: `groups[]` are model families ("Gemini Models", "Claude and GPT
 * models") and each carries `buckets[]` per window. `remainingFraction` counts
 * down from 1.0, so it inverts into the used-percent the rest of the app shows.
 */
export function parseAntigravityUsage(value: unknown): UsageSnapshot {
  const root = recordOf(value);
  const groups = root?.groups ?? recordOf(root?.response)?.groups;
  if (!Array.isArray(groups)) throw new Error("Antigravity quota response contained no groups");

  const windows: UsageWindow[] = [];
  for (const rawGroup of groups) {
    const group = recordOf(rawGroup);
    if (!group) continue;
    const groupName = typeof group.displayName === "string" ? group.displayName : "";
    const buckets = group.buckets;
    if (!Array.isArray(buckets)) continue;

    for (const rawBucket of buckets) {
      const bucket = recordOf(rawBucket);
      if (!bucket) continue;
      const remaining = finite(bucket.remainingFraction);
      if (remaining === undefined) continue;
      const resetAt = resetAtFrom(bucket.resetTime);
      const name = windowName(bucket);
      windows.push({
        label: groupName === "" ? name : `${shortGroup(groupName)} ${name}`,
        usedPercent: clampPercent((1 - remaining) * 100),
        // Drives the meter's elapsed-time guide; the group prefix is not a window.
        windowSeconds: windowSecondsFor(name, resetAt),
        resetAt,
      });
    }
  }

  if (windows.length === 0) throw new Error("Antigravity quota response contained no recognized windows");
  return { provider: "antigravity", label: "Antigravity", windows, updatedAt: Date.now() };
}

/** "Gemini Models" -> "Gemini", "Claude and GPT models" -> "Claude+GPT". */
function shortGroup(name: string): string {
  return name.replace(/\s*models?\s*$/i, "").replace(/\s+and\s+/gi, "+").trim() || name;
}

const WINDOW_NAMES: Record<string, string> = {
  daily: "day",
  weekly: "wk",
  monthly: "mo",
};

/**
 * The window part of a bar label, e.g. "wk" — the group prefix is added by the
 * caller, which also needs this part alone to resolve the window's span.
 *
 * The API's own `displayName` is prose ("Weekly Limit Remaining") and far too
 * long to sit beside Codex's "5h"/"7d" on a phone, so the machine-readable
 * `window` is preferred and the prose is only a fallback.
 */
function windowName(bucket: JsonRecord): string {
  const window = typeof bucket.window === "string" ? WINDOW_NAMES[bucket.window.toLowerCase()] : undefined;
  if (window !== undefined) return window;
  if (typeof bucket.displayName === "string" && bucket.displayName.trim() !== "") return bucket.displayName.trim();
  return typeof bucket.bucketId === "string" ? bucket.bucketId : "quota";
}

async function requestQuota(host: string, access: string): Promise<Response> {
  return fetch(`${host}${QUOTA_PATH}`, {
    method: "POST",
    signal: AbortSignal.timeout(ANTIGRAVITY_TIMEOUT_MS),
    headers: {
      authorization: `Bearer ${access}`,
      "content-type": "application/json",
      accept: "application/json",
      "user-agent": "antigravity",
    },
    body: JSON.stringify({}),
  });
}

/** Fetch Antigravity subscription quota, refreshing the Google token when it has expired. */
export async function fetchAntigravityUsage(): Promise<UsageSnapshot> {
  const path = antigravityTokenPath();
  let token = await readAntigravityToken(path);
  if (!token) throw new Error("Antigravity credentials are not configured; run `agy` to sign in");
  if (isExpiring(token.expires)) token = await refreshAntigravityToken(path, token);

  let lastStatus = 0;
  for (const host of QUOTA_HOSTS) {
    let response = await requestQuota(host, token.access);
    if (response.status === 401) {
      void response.body?.cancel().catch(() => undefined);
      token = await refreshAntigravityToken(path, token);
      response = await requestQuota(host, token.access);
    }
    if (response.ok) return parseAntigravityUsage(await response.json());
    lastStatus = response.status;
    void response.body?.cancel().catch(() => undefined);
    // 404 means this channel does not serve the RPC; anything else is a real failure.
    if (response.status !== 404) break;
  }
  throw new Error(`Antigravity quota request returned ${lastStatus}`);
}
