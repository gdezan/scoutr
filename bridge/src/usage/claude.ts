import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { claudeConfigDir } from "../agents/claude/index.js";
import { updateJsonFile } from "./auth.js";
import { isExpiring, requestTokenRefresh } from "./oauth.js";
import type { UsageSnapshot, UsageWindow } from "./providers.js";

const CLAUDE_USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
const CLAUDE_USAGE_BETA = "oauth-2025-04-20";
const CLAUDE_USAGE_TIMEOUT_MS = 10_000;
const CLAUDE_CACHE_FRESH_MS = 60_000;

/**
 * Anthropic buckets requests by user-agent: an unrecognized one lands in an
 * aggressively rate-limited pool that answers 429 almost immediately. Claude
 * Code records its own version in ~/.claude.json, so track that rather than
 * pinning a version string that silently ages out.
 */
const CLAUDE_FALLBACK_VERSION = "2.1.0";

/** Anthropic moved token exchange to platform.claude.com; console 404s but still works for some accounts. */
const CLAUDE_TOKEN_URLS = [
  "https://platform.claude.com/v1/oauth/token",
  "https://console.anthropic.com/v1/oauth/token",
];
const CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";

type JsonRecord = Record<string, unknown>;

function recordOf(value: unknown): JsonRecord | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as JsonRecord)
    : undefined;
}

function finite(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function clampPercent(value: number): number {
  return Math.max(0, Math.min(100, value));
}

function claudeResetAt(value: unknown): number | undefined {
  if (typeof value === "number" && Number.isFinite(value)) {
    return Math.round(value > 10_000_000_000 ? value / 1000 : value);
  }
  if (typeof value !== "string" || value.trim() === "") return undefined;
  const trimmed = value.trim();
  if (/^\d+(?:\.\d+)?$/.test(trimmed)) return claudeResetAt(Number(trimmed));
  const parsed = Date.parse(trimmed);
  return Number.isFinite(parsed) ? Math.floor(parsed / 1000) : undefined;
}
function claudeWindowSeconds(label: string): number | undefined {
  if (label === "5h") return 5 * 60 * 60;
  if (label === "7d" || label.startsWith("7d ")) return 7 * 24 * 60 * 60;
  return undefined;
}

function claudeWindow(
  value: unknown,
  label: string,
  percentKey = "utilization",
  windowSeconds = claudeWindowSeconds(label),
): UsageWindow | undefined {
  const record = recordOf(value);
  if (!record) return undefined;
  const usedPercent = finite(record[percentKey]) ?? finite(record.used_percentage);
  if (usedPercent === undefined) return undefined;
  return {
    label,
    usedPercent: clampPercent(usedPercent),
    windowSeconds,
    resetAt: claudeResetAt(record.resets_at),
  };
}

function scopedClaudeLabel(limit: JsonRecord): string {
  const model = recordOf(recordOf(limit.scope)?.model);
  const displayName = model?.display_name ?? model?.displayName;
  return typeof displayName === "string" && displayName.trim() !== ""
    ? `7d ${displayName.trim()}`
    : "7d scoped";
}

function windowsFromClaudeLimits(value: unknown): UsageWindow[] {
  if (!Array.isArray(value)) return [];
  const windows: UsageWindow[] = [];
  for (const item of value) {
    const limit = recordOf(item);
    if (!limit || typeof limit.kind !== "string") continue;
    const label = limit.kind === "session"
      ? "5h"
      : limit.kind === "weekly_all"
        ? "7d"
        : limit.kind === "weekly_scoped"
          ? scopedClaudeLabel(limit)
          : undefined;
    if (!label) continue;
    const window = claudeWindow(limit, label, "percent");
    if (window) windows.push(window);
  }
  return windows;
}

/** Convert Claude Code's OAuth usage response into the shared quota-window model. */
export function parseClaudeUsage(value: unknown): UsageSnapshot {
  const root = recordOf(value);
  if (!root) throw new Error("Claude usage response was not an object");

  const legacyWindows = [
    claudeWindow(root.five_hour, "5h"),
    claudeWindow(root.seven_day, "7d"),
    claudeWindow(root.seven_day_opus, "7d Opus"),
    claudeWindow(root.seven_day_sonnet, "7d Sonnet"),
  ].filter((window): window is UsageWindow => window !== undefined);
  const windowsByLabel = new Map(legacyWindows.map((window) => [window.label, window]));
  for (const window of windowsFromClaudeLimits(root.limits)) windowsByLabel.set(window.label, window);
  const preferredOrder = ["5h", "7d", "7d Opus", "7d Sonnet"];
  const windows = [
    ...preferredOrder.map((label) => windowsByLabel.get(label)).filter((window): window is UsageWindow => window !== undefined),
    ...[...windowsByLabel.values()].filter((window) => !preferredOrder.includes(window.label)),
  ];

  if (windows.length === 0) throw new Error("Claude usage response contained no recognized windows");
  return { provider: "claude", label: "Claude", windows, updatedAt: Date.now() };
}

function defaultClaudeCredentialsPath(): string {
  return join(claudeConfigDir(), ".credentials.json");
}

function defaultClaudeStatePath(): string {
  return `${claudeConfigDir()}.json`;
}

interface ClaudeCredentials {
  accessToken: string;
  refreshToken?: string;
  expiresAt?: number;
}

async function readClaudeCredentials(path: string): Promise<ClaudeCredentials | undefined> {
  try {
    const root = recordOf(JSON.parse(await readFile(path, "utf8")));
    const oauth = recordOf(root?.claudeAiOauth);
    const accessToken = typeof oauth?.accessToken === "string" ? oauth.accessToken : "";
    if (accessToken === "") return undefined;
    return {
      accessToken,
      refreshToken: typeof oauth?.refreshToken === "string" ? oauth.refreshToken : undefined,
      expiresAt: finite(oauth?.expiresAt),
    };
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    throw error;
  }
}

/**
 * Exchange the refresh token and merge the result back into the credential file.
 *
 * Anthropic rotates the refresh token, so persisting is what keeps `claude`'s
 * own login alive — dropping the rotated token would leave the file one
 * rotation behind and eventually force a re-login.
 */
async function refreshClaudeCredentials(
  path: string,
  credentials: ClaudeCredentials,
): Promise<ClaudeCredentials> {
  if (!credentials.refreshToken) {
    throw new Error("Claude Code OAuth token expired and no refresh token is stored; run `claude` to sign in");
  }

  const refreshed = await requestTokenRefresh({
    urls: CLAUDE_TOKEN_URLS,
    clientId: CLAUDE_CLIENT_ID,
    refreshToken: credentials.refreshToken,
    encoding: "json",
    label: "Claude",
    reauthHint: "run `claude` and sign in again",
    signal: AbortSignal.timeout(CLAUDE_USAGE_TIMEOUT_MS),
  });

  await updateJsonFile(path, (root) => {
    const oauth = recordOf(root.claudeAiOauth) ?? {};
    root.claudeAiOauth = {
      ...oauth,
      accessToken: refreshed.access,
      ...(refreshed.refresh ? { refreshToken: refreshed.refresh } : {}),
      expiresAt: refreshed.expires,
    };
    return root;
  });

  return { accessToken: refreshed.access, refreshToken: refreshed.refresh, expiresAt: refreshed.expires };
}

/** Claude Code records the version it last onboarded with; it is the closest thing to "installed version". */
async function claudeUserAgent(statePath: string): Promise<string> {
  try {
    const root = recordOf(JSON.parse(await readFile(statePath, "utf8")));
    const version = root?.lastOnboardingVersion;
    if (typeof version === "string" && /^\d+\.\d+/.test(version)) return `claude-code/${version}`;
  } catch {
    // Fall through to the pinned version; a plausible UA still beats none.
  }
  return `claude-code/${CLAUDE_FALLBACK_VERSION}`;
}

async function requestClaudeUsage(accessToken: string, userAgent: string): Promise<Response> {
  return fetch(CLAUDE_USAGE_URL, {
    signal: AbortSignal.timeout(CLAUDE_USAGE_TIMEOUT_MS),
    headers: {
      authorization: `Bearer ${accessToken}`,
      "anthropic-beta": CLAUDE_USAGE_BETA,
      "content-type": "application/json",
      "user-agent": userAgent,
    },
  });
}

async function readCachedClaudeUsage(path: string): Promise<UsageSnapshot | undefined> {
  try {
    const root = recordOf(JSON.parse(await readFile(path, "utf8")));
    const cached = recordOf(root?.cachedUsageUtilization);
    const updatedAt = finite(cached?.fetchedAtMs);
    if (updatedAt === undefined) return undefined;
    return { ...parseClaudeUsage(cached?.utilization), updatedAt };
  } catch {
    return undefined;
  }
}

async function claudeUsageError(response: Response): Promise<Error> {
  let detail: string | undefined;
  try {
    const body = recordOf(await response.json());
    const error = recordOf(body?.error);
    if (typeof error?.message === "string") detail = error.message;
  } catch {
    // The status still gives the user a searchable failure when the body is not JSON.
  }
  return new Error(`Claude usage request returned ${response.status}${detail ? `: ${detail}` : ""}`);
}

/**
 * Fetch Claude Code subscription usage, preserving Claude's last cached snapshot if the endpoint fails.
 *
 * Access tokens live ~60 minutes and `claude` only refreshes them while it is
 * running, so the token on disk is routinely expired by the time the app asks.
 * The recovery ladder, cheapest first:
 *
 *   1. refresh proactively when `expiresAt` says the token is spent;
 *   2. on a 401 anyway, re-read the file — `claude` may have just rotated it;
 *   3. still 401 with an unchanged token: refresh and retry once.
 */
export async function fetchClaudeUsage(): Promise<UsageSnapshot> {
  const statePath = defaultClaudeStatePath();
  const credentialsPath = defaultClaudeCredentialsPath();
  const cached = await readCachedClaudeUsage(statePath);
  if (cached && Date.now() - cached.updatedAt < CLAUDE_CACHE_FRESH_MS) return cached;

  try {
    let credentials = await readClaudeCredentials(credentialsPath);
    if (!credentials) throw new Error("Claude Code OAuth credentials are not configured; run `claude` to sign in");

    if (isExpiring(credentials.expiresAt)) {
      credentials = await refreshClaudeCredentials(credentialsPath, credentials);
    }

    const userAgent = await claudeUserAgent(statePath);
    let response = await requestClaudeUsage(credentials.accessToken, userAgent);

    if (response.status === 401) {
      void response.body?.cancel().catch(() => undefined);
      const onDisk = await readClaudeCredentials(credentialsPath);
      const fresher = onDisk && onDisk.accessToken !== credentials.accessToken ? onDisk : undefined;
      credentials = fresher ?? (await refreshClaudeCredentials(credentialsPath, credentials));
      response = await requestClaudeUsage(credentials.accessToken, userAgent);
    }

    if (!response.ok) throw await claudeUsageError(response);
    return parseClaudeUsage(await response.json());
  } catch (error) {
    if (!cached) throw error;
    return {
      ...cached,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}
