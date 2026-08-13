import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { claudeConfigDir } from "../agents/claude/index.js";
import type { UsageSnapshot, UsageWindow } from "./providers.js";

const CLAUDE_USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
const CLAUDE_USAGE_BETA = "oauth-2025-04-20";
const CLAUDE_USAGE_USER_AGENT = "claude-code/2.1.0";
const CLAUDE_USAGE_TIMEOUT_MS = 10_000;
const CLAUDE_CACHE_FRESH_MS = 60_000;

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

function claudeWindow(value: unknown, label: string, percentKey = "utilization"): UsageWindow | undefined {
  const record = recordOf(value);
  if (!record) return undefined;
  const usedPercent = finite(record[percentKey]) ?? finite(record.used_percentage);
  if (usedPercent === undefined) return undefined;
  return {
    label,
    usedPercent: clampPercent(usedPercent),
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

async function readClaudeAccessToken(path: string): Promise<string | undefined> {
  try {
    const root = recordOf(JSON.parse(await readFile(path, "utf8")));
    const oauth = recordOf(root?.claudeAiOauth);
    return typeof oauth?.accessToken === "string" && oauth.accessToken !== "" ? oauth.accessToken : undefined;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    throw error;
  }
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

/** Fetch Claude Code subscription usage, preserving Claude's last cached snapshot if the endpoint fails. */
export async function fetchClaudeUsage(): Promise<UsageSnapshot> {
  const cached = await readCachedClaudeUsage(defaultClaudeStatePath());
  if (cached && Date.now() - cached.updatedAt < CLAUDE_CACHE_FRESH_MS) return cached;

  try {
    const accessToken = await readClaudeAccessToken(defaultClaudeCredentialsPath());
    if (!accessToken) throw new Error("Claude Code OAuth credentials are not configured; run `claude` to sign in");

    const response = await fetch(CLAUDE_USAGE_URL, {
      signal: AbortSignal.timeout(CLAUDE_USAGE_TIMEOUT_MS),
      headers: {
        authorization: `Bearer ${accessToken}`,
        "anthropic-beta": CLAUDE_USAGE_BETA,
        "content-type": "application/json",
        "user-agent": CLAUDE_USAGE_USER_AGENT,
      },
    });
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
