/**
 * Provider usage adapters, ported from the user's pi extension
 * ~/.pi/agent/extensions/provider-usage.ts (TUI rendering removed).
 *
 * Covered providers (as configured on this machine):
 *   - openai-codex: rate-limit windows (5h / 7d) from the ChatGPT backend.
 *   - deepseek: wallet balance from the DeepSeek API.
 *   - xai: weekly credit usage via Grok CLI OAuth (access/refresh in auth.json).
 */

import { getCodexAuth, getApiKeyAuth, getOAuthAuth, readAuthStore, type AuthStore, type OAuthAuth } from "./auth.js";
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
const TOKEN_SKEW_MS = 5 * 60 * 1000;
const CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
const DEEPSEEK_BALANCE_URL = "https://api.deepseek.com/user/balance";
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

async function fetchCodexUsage(authStore: AuthStore): Promise<UsageSnapshot> {
  const auth = getCodexAuth(authStore);
  if (!auth) throw new Error("openai-codex credentials are not configured in pi's auth.json");

  const body = await withTimeout(async (signal) => {
    const response = await fetch(CODEX_USAGE_URL, {
      signal,
      headers: {
        accept: "application/json",
        authorization: `Bearer ${auth.access}`,
        ...(auth.accountId ? { "chatgpt-account-id": auth.accountId } : {}),
      },
    });
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

async function fetchDeepseekUsage(authStore: AuthStore): Promise<UsageSnapshot> {
  const auth = getApiKeyAuth(authStore, "deepseek");
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
 * Refresh an expired xAI OAuth access token in memory only.
 * The bridge never writes auth.json (see auth.ts); pi's own login/refresh owns persistence.
 */
async function refreshXaiAccess(auth: OAuthAuth): Promise<OAuthAuth> {
  const expires = finite(auth.expires);
  if (expires !== undefined && expires - TOKEN_SKEW_MS > Date.now()) return auth;
  if (!auth.refresh) {
    if (expires !== undefined && expires <= Date.now()) {
      throw new Error("xAI OAuth token expired; run `pi /login` and select xAI");
    }
    return auth;
  }

  const body = await withTimeout(async (signal) => {
    const response = await fetch(XAI_TOKEN_URL, {
      method: "POST",
      signal,
      headers: {
        accept: "application/json",
        "content-type": "application/x-www-form-urlencoded",
      },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: XAI_CLIENT_ID,
        refresh_token: auth.refresh!,
      }),
    });
    if (!response.ok) throw new Error(`xAI token refresh returned ${response.status}`);
    return (await response.json()) as Record<string, unknown>;
  });

  const access = typeof body.access_token === "string" ? body.access_token : undefined;
  if (!access) throw new Error("xAI token refresh returned no access token");
  const refresh = typeof body.refresh_token === "string" ? body.refresh_token : auth.refresh;
  const expiresIn = finite(body.expires_in) ?? 3600;
  return {
    ...auth,
    type: "oauth",
    access,
    refresh,
    expires: Date.now() + expiresIn * 1000 - TOKEN_SKEW_MS,
  };
}

async function fetchXaiUsage(authStore: AuthStore): Promise<UsageSnapshot> {
  const stored = getOAuthAuth(authStore, "xai");
  if (!stored) throw new Error("xAI OAuth is not configured in pi's auth.json (run /login and select xAI)");

  const auth = await refreshXaiAccess(stored);

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

export interface UsageProvider {
  id: string;
  label: string;
  fetch: (store: AuthStore) => Promise<UsageSnapshot>;
}

export const USAGE_PROVIDERS: UsageProvider[] = [
  { id: "codex", label: "Codex", fetch: fetchCodexUsage },
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
    const store = await readAuthStore(this.authPath);
    const results: UsageSnapshot[] = [];
    for (const provider of USAGE_PROVIDERS) {
      results.push(await this.get(provider, store));
    }
    return results;
  }

  async get(provider: UsageProvider, store: AuthStore): Promise<UsageSnapshot> {
    const cached = this.cache.get(provider.id);
    if (cached && Date.now() - cached.at < this.ttlMs) return cached.snapshot;
    try {
      const snapshotValue = await provider.fetch(store);
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
