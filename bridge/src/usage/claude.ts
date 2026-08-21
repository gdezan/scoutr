import { readFile } from "node:fs/promises";
import { join } from "node:path";
import * as v from "valibot";
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

const oauthSchema = v.looseObject({
  accessToken: v.optional(v.string()),
  refreshToken: v.optional(v.string()),
  expiresAt: v.optional(v.number()),
});

const windowRecordSchema = v.looseObject({
  utilization: v.optional(v.number()),
  used_percentage: v.optional(v.number()),
  percent: v.optional(v.number()),
  resets_at: v.optional(v.union([v.string(), v.number()])),
});

const limitScopeSchema = v.looseObject({
  model: v.optional(
    v.looseObject({
      display_name: v.optional(v.string()),
      displayName: v.optional(v.string()),
    }),
  ),
});

const limitSchema = v.looseObject({
  kind: v.optional(v.string()),
  scope: v.optional(limitScopeSchema),
  percent: v.optional(v.number()),
  resets_at: v.optional(v.union([v.string(), v.number()])),
});

const usageSchema = v.looseObject({
  five_hour: v.optional(windowRecordSchema),
  seven_day: v.optional(windowRecordSchema),
  seven_day_opus: v.optional(windowRecordSchema),
  seven_day_sonnet: v.optional(windowRecordSchema),
  limits: v.optional(v.array(limitSchema)),
});

const stateFileSchema = v.looseObject({
  lastOnboardingVersion: v.optional(v.string()),
  cachedUsageUtilization: v.optional(
    v.looseObject({
      fetchedAtMs: v.optional(v.number()),
      utilization: v.optional(v.unknown()),
    }),
  ),
});

const errorBodySchema = v.looseObject({
  error: v.optional(v.looseObject({ message: v.optional(v.string()) })),
});

type OauthRecord = v.InferOutput<typeof oauthSchema>;
type WindowRecord = v.InferOutput<typeof windowRecordSchema>;
type LimitRecord = v.InferOutput<typeof limitSchema>;
type UsageRecord = v.InferOutput<typeof usageSchema>;

function finite(value: number | undefined): number | undefined {
  return value !== undefined && Number.isFinite(value) ? value : undefined;
}

function clampPercent(value: number): number {
  return Math.max(0, Math.min(100, value));
}

function claudeResetAt(value: string | number | undefined): number | undefined {
  if (value === undefined) return undefined;
  if (Number.isFinite(value)) {
    const num = Number(value);
    return Math.round(num > 10_000_000_000 ? num / 1000 : num);
  }
  const trimmed = String(value).trim();
  if (/^\d+(?:\.\d+)?$/.test(trimmed)) return claudeResetAt(Number(trimmed));
  const parsed = Date.parse(trimmed);
  return Number.isFinite(parsed) ? Math.floor(parsed / 1000) : undefined;
}

function claudeWindowSeconds(label: string): number | undefined {
  if (label === "5h") return 5 * 60 * 60;
  if (label === "7d" || label.startsWith("7d ")) return 7 * 24 * 60 * 60;
  return undefined;
}

type PercentKey = "utilization" | "percent";

function claudeWindow(
  window: WindowRecord | undefined,
  label: string,
  percentKey: PercentKey = "utilization",
  windowSeconds = claudeWindowSeconds(label),
): UsageWindow | undefined {
  if (!window) return undefined;
  const usedPercent = finite(window[percentKey]) ?? finite(window.used_percentage);
  if (usedPercent === undefined) return undefined;
  return {
    label,
    usedPercent: clampPercent(usedPercent),
    windowSeconds,
    resetAt: claudeResetAt(window.resets_at),
  };
}

function scopedClaudeLabel(limit: LimitRecord): string {
  const model = limit.scope?.model;
  const displayName = model?.display_name ?? model?.displayName;
  return displayName && displayName.trim() !== "" ? `7d ${displayName.trim()}` : "7d scoped";
}

function windowsFromClaudeLimits(limits: LimitRecord[] | undefined): UsageWindow[] {
  if (!limits) return [];
  const windows: UsageWindow[] = [];
  for (const limit of limits) {
    if (limit.kind === undefined) continue;
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
export function parseClaudeUsage(root: UsageRecord): UsageSnapshot {
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
    const parsed = v.safeParse(v.looseObject({ claudeAiOauth: v.optional(oauthSchema) }), JSON.parse(await readFile(path, "utf8")));
    if (!parsed.success) return undefined;
    const oauth = parsed.output.claudeAiOauth;
    const accessToken = oauth?.accessToken ?? "";
    if (accessToken === "") return undefined;
    return {
      accessToken,
      refreshToken: oauth?.refreshToken,
      expiresAt: finite(oauth?.expiresAt),
    };
  } catch (error) {
    // SAFETY: readFile only throws ENOENT for a missing credential file, which means the
    // user has not signed in yet; any other error is a real read failure.
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
  const refreshToken = credentials.refreshToken;

  const refreshed = await requestTokenRefresh({
    urls: CLAUDE_TOKEN_URLS,
    clientId: CLAUDE_CLIENT_ID,
    refreshToken,
    encoding: "json",
    label: "Claude",
    reauthHint: "run `claude` and sign in again",
    signal: AbortSignal.timeout(CLAUDE_USAGE_TIMEOUT_MS),
  });

  await updateJsonFile(path, (root) => {
    const stored = v.safeParse(oauthSchema, root.claudeAiOauth);
    const claudeOauth: OauthRecord = stored.success
      ? { ...stored.output, accessToken: refreshed.access }
      : { accessToken: refreshed.access };
    if (refreshed.refresh) claudeOauth.refreshToken = refreshed.refresh;
    claudeOauth.expiresAt = refreshed.expires;
    root.claudeAiOauth = claudeOauth;
    return root;
  });

  return { accessToken: refreshed.access, refreshToken: refreshed.refresh, expiresAt: refreshed.expires };
}

/** Claude Code records the version it last onboarded with; it is the closest thing to "installed version". */
async function claudeUserAgent(statePath: string): Promise<string> {
  try {
    const parsed = v.safeParse(stateFileSchema, JSON.parse(await readFile(statePath, "utf8")));
    if (!parsed.success) return `claude-code/${CLAUDE_FALLBACK_VERSION}`;
    const version = parsed.output.lastOnboardingVersion;
    if (version !== undefined && /^\d+\.\d+/.test(version)) return `claude-code/${version}`;
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
    const parsed = v.safeParse(stateFileSchema, JSON.parse(await readFile(path, "utf8")));
    if (!parsed.success) return undefined;
    const cached = parsed.output.cachedUsageUtilization;
    const updatedAt = finite(cached?.fetchedAtMs);
    if (updatedAt === undefined) return undefined;
    const utilParsed = v.safeParse(usageSchema, cached?.utilization);
    if (!utilParsed.success) return undefined;
    return { ...parseClaudeUsage(utilParsed.output), updatedAt };
  } catch {
    return undefined;
  }
}

async function claudeUsageError(response: Response): Promise<Error> {
  let detail: string | undefined;
  try {
    const bodyParsed = v.safeParse(errorBodySchema, await response.json());
    const message = bodyParsed.success ? bodyParsed.output.error?.message : undefined;
    if (message !== undefined) detail = message;
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
    const parsed = v.safeParse(usageSchema, await response.json());
    if (!parsed.success) throw new Error("Claude usage response contained no recognized windows");
    return parseClaudeUsage(parsed.output);
  } catch (error) {
    if (!cached) throw error;
    return {
      ...cached,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}
