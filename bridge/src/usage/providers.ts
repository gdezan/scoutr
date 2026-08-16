/**
 * Provider usage adapters, ported from the user's pi extension
 * ~/.pi/agent/extensions/provider-usage.ts (TUI rendering removed).
 *
 * Covered providers (as configured on this machine):
 *   - openai-codex: rate-limit windows (5h / 7d) from the ChatGPT backend.
 *   - claude: rate-limit windows from Claude Code's OAuth usage endpoint.
 *   - deepseek: wallet balance from the DeepSeek API.
 *   - xai: weekly credit usage via Grok CLI OAuth (access/refresh in auth.json).
 */

import { fetchAntigravityUsage } from "./antigravity.js";
import { fetchClaudeUsage } from "./claude.js";
import {
  getCodexAuth,
  getApiKeyAuth,
  getOAuthAuth,
  persistOAuthAuth,
  readAuthStore,
  type AuthStore,
  type CodexAuth,
  type OAuthAuth,
} from "./auth.js";
import { isExpiring, requestTokenRefresh } from "./oauth.js";
import { windowSecondsFor } from "./windows.js";
import { homedir } from "node:os";
import { join } from "node:path";

export interface UsageWindow {
  label: string;
  usedPercent: number;
  amount?: number;
  limitAmount?: number;
  currency?: string;
  windowSeconds?: number;
  resetAt?: number;
}

export interface UsageSnapshot {
  provider: string;
  label: string;
  windows: UsageWindow[];
  updatedAt: number;
  error?: string;
}

const FETCH_TIMEOUT_MS = 10_000;
const CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
const CODEX_TOKEN_URL = "https://auth.openai.com/oauth/token";
const CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
const DEEPSEEK_BALANCE_URL = "https://api.deepseek.com/user/balance";
const OPENCODE_GO_USAGE_URL = "https://opencode.ai/zen/go/v1/usage";
const XAI_BILLING_URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits";
const XAI_USER_URL = "https://cli-chat-proxy.grok.com/v1/user";
const XAI_TOKEN_URL = "https://auth.x.ai/oauth2/token";
const XAI_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";

function finite(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function clampPercent(value: number): number {
  return Math.max(0, Math.min(100, value));
}

function normalizeResetAt(value: unknown): number | undefined {
  const number = finite(value);
  if (number === undefined) return undefined;
  return number > 10_000_000_000 ? Math.round(number / 1000) : Math.round(number);
}

function resetAtFromIso(value: unknown): number | undefined {
  if (typeof value !== "string" || value.trim() === "") return undefined;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? Math.floor(ms / 1000) : undefined;
}

function snapshot(provider: string, label: string, windows: Array<UsageWindow | undefined>): UsageSnapshot {
  const valid = windows.filter((w): w is UsageWindow => w !== undefined);
  return { provider, label, windows: valid, updatedAt: Date.now() };
}

async function withTimeout<T>(work: (signal: AbortSignal) => Promise<T>, ms = FETCH_TIMEOUT_MS): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), ms);
  try {
    return await work(controller.signal);
  } finally {
    clearTimeout(timeout);
  }
}

// ── Codex ─────────────────────────────────────────────────────────────

function parseCodexWindow(value: unknown, fallbackLabel: string): UsageWindow | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const record = value as Record<string, unknown>;
  const usedPercent = finite(record.used_percent);
  if (usedPercent === undefined) return undefined;
  const windowSeconds = finite(record.limit_window_seconds);
  let label = fallbackLabel;
  if (windowSeconds === 5 * 60 * 60) label = "5h";
  else if (windowSeconds === 7 * 24 * 60 * 60) label = "7d";
  return {
    label,
    usedPercent: clampPercent(usedPercent),
    windowSeconds,
    resetAt: normalizeResetAt(record.reset_at),
  };
}

/**
 * Refresh Codex's access token and write the result back to pi's auth.json.
 *
 * OpenAI rotates the refresh token on every exchange, so the rotated value must
 * be persisted — keeping it in memory only would leave the on-disk token one
 * rotation stale and break pi's Codex login after the upstream grace window.
 */
async function refreshCodexAccess(authPath: string, auth: CodexAuth): Promise<CodexAuth> {
  if (!auth.refresh) {
    throw new Error("Codex OAuth token expired and no refresh token is stored; run `pi /login` and select Codex");
  }

  const refreshed = await withTimeout((signal) =>
    requestTokenRefresh({
      urls: [CODEX_TOKEN_URL],
      clientId: CODEX_CLIENT_ID,
      refreshToken: auth.refresh!,
      encoding: "json",
      label: "Codex",
      reauthHint: "run `pi /login` and select Codex",
      signal,
    }),
  );

  await persistOAuthAuth(authPath, "openai-codex", refreshed);
  return { ...auth, access: refreshed.access, refresh: refreshed.refresh, expires: refreshed.expires };
}

async function fetchCodexUsage({ store, authPath }: UsageContext): Promise<UsageSnapshot> {
  let auth = getCodexAuth(store);
  if (!auth) throw new Error("openai-codex credentials are not configured in pi's auth.json");
  if (isExpiring(auth.expires)) auth = await refreshCodexAccess(authPath, auth);

  const requestUsage = (signal: AbortSignal, access: string) =>
    fetch(CODEX_USAGE_URL, {
      signal,
      headers: {
        accept: "application/json",
        authorization: `Bearer ${access}`,
        ...(auth!.accountId ? { "chatgpt-account-id": auth!.accountId } : {}),
      },
    });

  const body = await withTimeout(async (signal) => {
    let response = await requestUsage(signal, auth!.access);
    // A 401 despite a live-looking expiry means the token was revoked or the
    // clock drifted; one refresh distinguishes that from a real auth failure.
    if (response.status === 401) {
      void response.body?.cancel().catch(() => undefined);
      auth = await refreshCodexAccess(authPath, auth!);
      response = await requestUsage(signal, auth.access);
    }
    if (!response.ok) throw new Error(`Codex usage request returned ${response.status}`);
    return (await response.json()) as Record<string, unknown>;
  });

  const rateLimit = body.rate_limit;
  if (!rateLimit || typeof rateLimit !== "object" || Array.isArray(rateLimit)) {
    throw new Error("Codex usage response contained no recognized windows");
  }
  const bucket = rateLimit as Record<string, unknown>;
  const result = snapshot("openai-codex", "Codex", [
    parseCodexWindow(bucket.primary_window, "primary"),
    parseCodexWindow(bucket.secondary_window, "secondary"),
  ]);
  if (result.windows.length === 0) throw new Error("Codex usage response contained no recognized windows");
  return result;
}

// ── DeepSeek ──────────────────────────────────────────────────────────

function parseDeepseekBalance(value: unknown): UsageWindow[] {
  if (!value || typeof value !== "object" || Array.isArray(value)) return [];
  const root = value as Record<string, unknown>;
  const infos = root.balance_infos;
  if (!Array.isArray(infos)) return [];
  const windows: UsageWindow[] = [];
  for (const info of infos) {
    if (!info || typeof info !== "object" || Array.isArray(info)) continue;
    const record = info as Record<string, unknown>;
    const currency = typeof record.currency === "string" ? record.currency.toUpperCase() : undefined;
    const totalBalance = parseFloat(typeof record.total_balance === "string" ? record.total_balance : "");
    if (!Number.isFinite(totalBalance)) continue;
    windows.push({ label: currency ?? "???", usedPercent: 0, amount: totalBalance, currency });
  }
  return windows;
}

async function fetchDeepseekUsage({ store }: UsageContext): Promise<UsageSnapshot> {
  const auth = getApiKeyAuth(store, "deepseek");
  if (!auth) throw new Error("deepseek credentials are not configured in pi's auth.json");

  const body = await withTimeout(async (signal) => {
    const response = await fetch(DEEPSEEK_BALANCE_URL, {
      signal,
      headers: { accept: "application/json", authorization: `Bearer ${auth.key}` },
    });
    if (!response.ok) throw new Error(`DeepSeek balance request returned ${response.status}`);
    return (await response.json()) as Record<string, unknown>;
  });

  const windows = parseDeepseekBalance(body);
  if (windows.length === 0) throw new Error("DeepSeek balance response contained no data");
  return { ...snapshot("deepseek", "DeepSeek", windows), updatedAt: Date.now() };
}

// ── OpenCode Go ───────────────────────────────────────────────────────

/**
 * The Go plan's three spend caps, in the order the console shows them.
 * Zen (pay-as-you-go credits) has no equivalent endpoint — only Go reports usage.
 */
const OPENCODE_GO_WINDOWS: Array<{ key: string; label: string }> = [
  { key: "rolling", label: "5h" },
  { key: "weekly", label: "wk" },
  // Monthly rides the billing anchor, so its span comes from the reset date.
  { key: "monthly", label: "mo" },
];

export function parseOpencodeGoUsage(value: unknown): UsageSnapshot {
  const root = value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : undefined;
  const usage = root?.usage;
  if (!usage || typeof usage !== "object" || Array.isArray(usage)) {
    throw new Error("OpenCode Go usage response was not an object");
  }
  const buckets = usage as Record<string, unknown>;

  const windows: UsageWindow[] = [];
  for (const { key, label } of OPENCODE_GO_WINDOWS) {
    const bucket = buckets[key];
    if (!bucket || typeof bucket !== "object" || Array.isArray(bucket)) continue;
    const record = bucket as Record<string, unknown>;
    const percent = finite(record.percent);
    if (percent === undefined) continue;
    const resetAt = resetAtFromIso(record.resetsAt);
    windows.push({
      label,
      usedPercent: clampPercent(percent),
      windowSeconds: windowSecondsFor(label, resetAt),
      resetAt,
    });
  }

  if (windows.length === 0) throw new Error("OpenCode Go usage response contained no recognized windows");
  return snapshot("opencode-go", "OpenCode Go", windows);
}

async function fetchOpencodeGoUsage({ store }: UsageContext): Promise<UsageSnapshot> {
  const auth = getApiKeyAuth(store, "opencode-go");
  if (!auth) throw new Error("opencode-go credentials are not configured in pi's auth.json");

  const body = await withTimeout(async (signal) => {
    const response = await fetch(OPENCODE_GO_USAGE_URL, {
      signal,
      headers: {
        accept: "application/json",
        authorization: `Bearer ${auth.key}`,
        // opencode.ai sits behind a WAF that 403s unrecognized clients before
        // auth is ever considered — without a client-shaped user-agent even the
        // documented /models endpoint is refused.
        "user-agent": "opencode/1.0",
      },
    });
    if (!response.ok) throw new Error(`OpenCode Go usage request returned ${response.status}`);
    return (await response.json()) as Record<string, unknown>;
  });

  return parseOpencodeGoUsage(body);
}

// ── xAI ───────────────────────────────────────────────────────────────

function centsVal(value: unknown): number | undefined {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  return finite((value as Record<string, unknown>).val);
}

export function parseXaiUsage(value: unknown): UsageSnapshot {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("xAI usage response was not an object");
  }
  const root = value as Record<string, unknown>;
  const config =
    root.config && typeof root.config === "object" && !Array.isArray(root.config)
      ? (root.config as Record<string, unknown>)
      : root;

  const period =
    config.currentPeriod && typeof config.currentPeriod === "object" && !Array.isArray(config.currentPeriod)
      ? (config.currentPeriod as Record<string, unknown>)
      : undefined;
  const periodType = typeof period?.type === "string" ? period.type : "";

  let usedPercent = finite(config.creditUsagePercent);
  if (usedPercent === undefined) {
    const monthlyLimit = centsVal(config.monthlyLimit);
    const used = centsVal(config.used);
    if (monthlyLimit !== undefined && monthlyLimit > 0 && used !== undefined) {
      usedPercent = clampPercent((used / monthlyLimit) * 100);
    }
  }
  if (usedPercent === undefined) {
    const products = config.productUsage;
    if (Array.isArray(products)) {
      for (const product of products) {
        if (!product || typeof product !== "object" || Array.isArray(product)) continue;
        const pct = finite((product as Record<string, unknown>).usagePercent);
        if (pct !== undefined) {
          usedPercent = pct;
          break;
        }
      }
    }
  }
  // Endpoint omits creditUsagePercent at a fresh period start; treat as 0% used
  // when a typed period is present (matches grok-cli / official credits config).
  if (usedPercent === undefined && periodType.length > 0) usedPercent = 0;
  if (usedPercent === undefined) throw new Error("xAI usage response contained no recognized windows");

  const label = periodType.includes("WEEKLY")
    ? "wk"
    : periodType.includes("MONTHLY")
      ? "mo"
      : periodType.includes("DAILY")
        ? "day"
        : "plan";

  const resetAt =
    resetAtFromIso(period?.end) ??
    resetAtFromIso(config.billingPeriodEnd) ??
    resetAtFromIso(config.resetAt) ??
    resetAtFromIso(config.reset_at);
  const startAt = resetAtFromIso(period?.start) ?? resetAtFromIso(config.billingPeriodStart);
  const windowSeconds =
    startAt !== undefined && resetAt !== undefined && resetAt > startAt ? resetAt - startAt : undefined;

  return snapshot("xai", "xAI", [{ label, usedPercent: clampPercent(usedPercent), windowSeconds, resetAt }]);
}

/**
 * Refresh an expired xAI OAuth access token and persist the result.
 *
 * This used to refresh in memory only. That discarded the rotated refresh
 * token, so every bridge restart re-refreshed with a token that was already one
 * rotation behind — a slow path to a dead xAI login. The refreshed value is now
 * written back to pi's auth.json.
 */
async function refreshXaiAccess(authPath: string, auth: OAuthAuth): Promise<OAuthAuth> {
  const expires = finite(auth.expires);
  if (!isExpiring(expires)) return auth;
  if (!auth.refresh) {
    if (expires !== undefined && expires <= Date.now()) {
      throw new Error("xAI OAuth token expired; run `pi /login` and select xAI");
    }
    return auth;
  }

  const refreshed = await withTimeout((signal) =>
    requestTokenRefresh({
      urls: [XAI_TOKEN_URL],
      clientId: XAI_CLIENT_ID,
      refreshToken: auth.refresh!,
      encoding: "form",
      label: "xAI",
      reauthHint: "run `pi /login` and select xAI",
      signal,
    }),
  );

  await persistOAuthAuth(authPath, "xai", refreshed);
  return { ...auth, type: "oauth", access: refreshed.access, refresh: refreshed.refresh, expires: refreshed.expires };
}

async function fetchXaiUsage({ store, authPath }: UsageContext): Promise<UsageSnapshot> {
  const stored = getOAuthAuth(store, "xai");
  if (!stored) throw new Error("xAI OAuth is not configured in pi's auth.json (run /login and select xAI)");

  const auth = await refreshXaiAccess(authPath, stored);

  const headers: Record<string, string> = {
    accept: "application/json",
    authorization: `Bearer ${auth.access}`,
    "X-XAI-Token-Auth": "xai-grok-cli",
  };
  const body = await withTimeout(async (signal) => {
    try {
      const identityResponse = await fetch(XAI_USER_URL, { signal, headers, redirect: "error" });
      if (identityResponse.ok) {
        const identity = (await identityResponse.json()) as Record<string, unknown>;
        const userId = typeof identity.userId === "string" ? identity.userId : undefined;
        if (userId) headers["x-userid"] = userId;
      } else {
        void identityResponse.body?.cancel().catch(() => undefined);
      }
    } catch {
      // Billing often works with bearer alone.
    }
    const response = await fetch(XAI_BILLING_URL, { signal, headers, redirect: "error" });
    if (!response.ok) throw new Error(`xAI usage request returned ${response.status}`);
    return (await response.json()) as Record<string, unknown>;
  });
  return parseXaiUsage(body);
}

// ── Registry ──────────────────────────────────────────────────────────

/** What a provider needs to authenticate: the parsed store, plus where to write refreshed tokens back. */
export interface UsageContext {
  store: AuthStore;
  authPath: string;
}

export interface UsageProvider {
  id: string;
  label: string;
  fetch: (context: UsageContext) => Promise<UsageSnapshot>;
}

export const USAGE_PROVIDERS: UsageProvider[] = [
  { id: "codex", label: "Codex", fetch: fetchCodexUsage },
  { id: "claude", label: "Claude", fetch: () => fetchClaudeUsage() },
  { id: "opencode-go", label: "OpenCode Go", fetch: fetchOpencodeGoUsage },
  { id: "antigravity", label: "Antigravity", fetch: fetchAntigravityUsage },
  { id: "deepseek", label: "DeepSeek", fetch: fetchDeepseekUsage },
  { id: "xai", label: "xAI", fetch: fetchXaiUsage },
];

export interface UsageServiceOptions {
  authPath?: string;
  /** Cache TTL in ms; default 60s (provider windows change slowly). */
  cacheTtlMs?: number;
}

export class UsageService {
  private readonly cache = new Map<string, { snapshot: UsageSnapshot; at: number }>();
  private readonly authPath: string;
  private readonly ttlMs: number;

  constructor(options: UsageServiceOptions = {}) {
    const agentDir = process.env.PI_CODING_AGENT_DIR?.trim() || join(homedir(), ".pi", "agent");
    this.authPath = options.authPath ?? join(agentDir, "auth.json");
    this.ttlMs = options.cacheTtlMs ?? 60_000;
  }

  async all(): Promise<UsageSnapshot[]> {
    // Claude owns a separate OAuth store, so a missing pi auth file must not hide it.
    const store = await readAuthStore(this.authPath).catch(() => ({}));
    const context: UsageContext = { store, authPath: this.authPath };
    // Fetch in parallel: sequentially, four timing-out providers stack their
    // 10s timeouts into a 40s wait for a screen that polls every 10s.
    return Promise.all(USAGE_PROVIDERS.map((provider) => this.get(provider, context)));
  }

  async get(provider: UsageProvider, context: UsageContext): Promise<UsageSnapshot> {
    const cached = this.cache.get(provider.id);
    if (cached && Date.now() - cached.at < this.ttlMs) return cached.snapshot;
    try {
      const snapshotValue = await provider.fetch(context);
      this.cache.set(provider.id, { snapshot: snapshotValue, at: Date.now() });
      return snapshotValue;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return {
        provider: provider.id,
        label: provider.label,
        windows: cached?.snapshot.windows ?? [],
        updatedAt: cached?.snapshot.updatedAt ?? 0,
        error: message,
      };
    }
  }
}
