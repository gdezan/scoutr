/**
 * Provider usage adapters, ported from the user's pi extension
 * ~/.pi/agent/extensions/provider-usage.ts (TUI rendering removed).
 *
 * Covered providers (as configured on this machine):
 *   - openai-codex: rate-limit windows (5h / 7d) from the ChatGPT backend.
 *   - claude: rate-limit windows from Claude Code's OAuth usage endpoint.
 *   - deepseek: wallet balance from the DeepSeek API.
 *   - xai: weekly credit usage via Grok CLI OAuth (access/refresh in auth.json).
 *
 * Each provider's JSON response is decoded once at the fetch boundary with a
 * valibot schema, so the extraction logic below branches on typed domain values
 * instead of narrowing `unknown` with `typeof` or casting through
 * `Record<string, unknown>`.
 */

import * as v from "valibot";
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

// ── Response schemas ───────────────────────────────────────────────────

const finiteNumber = v.optional(v.pipe(v.number(), v.finite()));

const codexWindowSchema = v.looseObject({
  used_percent: finiteNumber,
  limit_window_seconds: finiteNumber,
  reset_at: finiteNumber,
});
const codexUsageSchema = v.looseObject({
  rate_limit: v.optional(
    v.looseObject({
      primary_window: v.optional(codexWindowSchema),
      secondary_window: v.optional(codexWindowSchema),
    }),
  ),
});

const deepseekBalanceSchema = v.looseObject({
  balance_infos: v.optional(
    v.array(
      v.looseObject({
        currency: v.optional(v.string()),
        total_balance: v.optional(v.union([v.string(), v.number()])),
      }),
    ),
  ),
});

const opencodeGoWindowSchema = v.looseObject({
  percent: finiteNumber,
  resetsAt: v.optional(v.string()),
});
const opencodeGoUsageSchema = v.looseObject({
  usage: v.optional(
    v.looseObject({
      rolling: v.optional(opencodeGoWindowSchema),
      weekly: v.optional(opencodeGoWindowSchema),
      monthly: v.optional(opencodeGoWindowSchema),
    }),
  ),
});

const xaiPeriodSchema = v.looseObject({
  type: v.optional(v.string()),
  start: v.optional(v.string()),
  end: v.optional(v.string()),
});
const xaiAmountSchema = v.looseObject({ val: finiteNumber });
const xaiFieldsSchema = v.looseObject({
  creditUsagePercent: finiteNumber,
  monthlyLimit: v.optional(xaiAmountSchema),
  used: v.optional(xaiAmountSchema),
  productUsage: v.optional(v.array(v.looseObject({ usagePercent: finiteNumber }))),
  currentPeriod: v.optional(xaiPeriodSchema),
  billingPeriodEnd: v.optional(v.string()),
  billingPeriodStart: v.optional(v.string()),
  resetAt: v.optional(v.string()),
  reset_at: v.optional(v.string()),
});
const xaiIdentitySchema = v.looseObject({ userId: v.optional(v.string()) });
// xAI nests the usage fields under `config` *or* places them at the top level.
const xaiUsageSchema = v.looseObject({
  config: v.optional(xaiFieldsSchema),
  creditUsagePercent: finiteNumber,
  monthlyLimit: v.optional(xaiAmountSchema),
  used: v.optional(xaiAmountSchema),
  productUsage: v.optional(v.array(v.looseObject({ usagePercent: finiteNumber }))),
  currentPeriod: v.optional(xaiPeriodSchema),
  billingPeriodEnd: v.optional(v.string()),
  billingPeriodStart: v.optional(v.string()),
  resetAt: v.optional(v.string()),
  reset_at: v.optional(v.string()),
});

type CodexWindow = v.InferOutput<typeof codexWindowSchema>;
type DeepseekBalance = v.InferOutput<typeof deepseekBalanceSchema>;
type OpencodeGoUsage = v.InferOutput<typeof opencodeGoUsageSchema>;
type XaiUsage = v.InferOutput<typeof xaiUsageSchema>;

// ── Shared types ───────────────────────────────────────────────────────

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

function clampPercent(value: number): number {
  return Math.max(0, Math.min(100, value));
}

/** Seconds-since-epoch from a provider reset timestamp that may be in ms or s. */
function normalizeResetAt(value: number | undefined): number | undefined {
  if (value === undefined) return undefined;
  return value > 10_000_000_000 ? Math.round(value / 1000) : Math.round(value);
}

/** Seconds-since-epoch from an ISO-8601 timestamp. */
function resetAtFromIso(value: string | undefined): number | undefined {
  if (value === undefined || value.trim() === "") return undefined;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? Math.floor(ms / 1000) : undefined;
}

function snapshot(
  provider: string,
  label: string,
  windows: Array<UsageWindow | undefined>,
): UsageSnapshot {
  const valid = windows.filter((w): w is UsageWindow => w !== undefined);
  return { provider, label, windows: valid, updatedAt: Date.now() };
}

async function withTimeout<T>(
  work: (signal: AbortSignal) => Promise<T>,
  ms = FETCH_TIMEOUT_MS,
): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), ms);
  try {
    return await work(controller.signal);
  } finally {
    clearTimeout(timeout);
  }
}

// ── Codex ─────────────────────────────────────────────────────────────

function parseCodexWindow(
  value: CodexWindow | undefined,
  fallbackLabel: string,
): UsageWindow | undefined {
  const usedPercent = value?.used_percent;
  if (usedPercent === undefined) return undefined;
  const windowSeconds = value?.limit_window_seconds;
  let label = fallbackLabel;
  if (windowSeconds === 5 * 60 * 60) label = "5h";
  else if (windowSeconds === 7 * 24 * 60 * 60) label = "7d";
  return {
    label,
    usedPercent: clampPercent(usedPercent),
    windowSeconds,
    resetAt: normalizeResetAt(value?.reset_at),
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
  const refreshToken = auth.refresh;

  const refreshed = await withTimeout((signal) =>
    requestTokenRefresh({
      urls: [CODEX_TOKEN_URL],
      clientId: CODEX_CLIENT_ID,
      refreshToken,
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

  let access = auth.access;
  let accountId = auth.accountId;
  const baseHeaders = {
    accept: "application/json",
    authorization: `Bearer ${access}`,
  };
  const requestUsage = (signal: AbortSignal) => {
    const headers = accountId
      ? { ...baseHeaders, "chatgpt-account-id": accountId }
      : baseHeaders;
    return fetch(CODEX_USAGE_URL, { signal, headers });
  };

  let response = await withTimeout(requestUsage);
  // A 401 despite a live-looking expiry means the token was revoked or the
  // clock drifted; one refresh distinguishes that from a real auth failure.
  if (response.status === 401) {
    void response.body?.cancel().catch(() => undefined);
    const refreshed = await refreshCodexAccess(authPath, auth);
    access = refreshed.access;
    accountId = refreshed.accountId;
    response = await withTimeout(requestUsage);
  }
  if (!response.ok) throw new Error(`Codex usage request returned ${response.status}`);
  const body: unknown = await response.json();

  const parsed = v.safeParse(codexUsageSchema, body);
  if (!parsed.success) throw new Error("Codex usage response contained no recognized windows");
  const rateLimit = parsed.output.rate_limit;
  if (!rateLimit) throw new Error("Codex usage response contained no recognized windows");
  const result = snapshot("openai-codex", "Codex", [
    parseCodexWindow(rateLimit.primary_window, "primary"),
    parseCodexWindow(rateLimit.secondary_window, "secondary"),
  ]);
  if (result.windows.length === 0) throw new Error("Codex usage response contained no recognized windows");
  return result;
}

// ── DeepSeek ──────────────────────────────────────────────────────────

function parseDeepseekBalance(value: DeepseekBalance): UsageWindow[] {
  const infos = value.balance_infos;
  if (!Array.isArray(infos)) return [];
  const windows: UsageWindow[] = [];
  for (const info of infos) {
    const currency = info.currency?.toUpperCase();
    const raw = info.total_balance;
    const totalBalance = raw === "" ? Number.NaN : Number(raw);
    if (!Number.isFinite(totalBalance)) continue;
    windows.push({ label: currency ?? "???", usedPercent: 0, amount: totalBalance, currency });
  }
  return windows;
}

async function fetchDeepseekUsage({ store }: UsageContext): Promise<UsageSnapshot> {
  const auth = getApiKeyAuth(store, "deepseek");
  if (!auth) throw new Error("deepseek credentials are not configured in pi's auth.json");

  const body: unknown = await withTimeout(async (signal) => {
    const response = await fetch(DEEPSEEK_BALANCE_URL, {
      signal,
      headers: { accept: "application/json", authorization: `Bearer ${auth.key}` },
    });
    if (!response.ok) throw new Error(`DeepSeek balance request returned ${response.status}`);
    return (await response.json());
  });

  const parsed = v.safeParse(deepseekBalanceSchema, body);
  if (!parsed.success) throw new Error("DeepSeek balance response contained no data");
  const windows = parseDeepseekBalance(parsed.output);
  if (windows.length === 0) throw new Error("DeepSeek balance response contained no data");
  return { ...snapshot("deepseek", "DeepSeek", windows), updatedAt: Date.now() };
}

// ── OpenCode Go ───────────────────────────────────────────────────────

/**
 * The Go plan's three spend caps, in the order the console shows them.
 * Zen (pay-as-you-go credits) has no equivalent endpoint — only Go reports usage.
 */
const OPENCODE_GO_WINDOWS = [
  { key: "rolling", label: "5h" },
  { key: "weekly", label: "wk" },
  // Monthly rides the billing anchor, so its span comes from the reset date.
  { key: "monthly", label: "mo" },
] as const;

export function parseOpencodeGoUsage(value: OpencodeGoUsage): UsageSnapshot {
  const usage = value.usage;
  if (!usage) throw new Error("OpenCode Go usage response was not an object");
  const buckets = usage;

  const windows: UsageWindow[] = [];
  for (const { key, label } of OPENCODE_GO_WINDOWS) {
    const bucket = buckets[key];
    if (!bucket) continue;
    const percent = bucket.percent;
    if (percent === undefined) continue;
    const resetAt = resetAtFromIso(bucket.resetsAt);
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

  const body: unknown = await withTimeout(async (signal) => {
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
    return (await response.json());
  });

  const parsed = v.safeParse(opencodeGoUsageSchema, body);
  if (!parsed.success) throw new Error("OpenCode Go usage response was not an object");
  return parseOpencodeGoUsage(parsed.output);
}

// ── xAI ───────────────────────────────────────────────────────────────

/** Cents-style `{ val: number }` amount, or a bare number, to a number. */
function centsVal(value: { val?: number } | undefined): number | undefined {
  return value?.val;
}

export function parseXaiUsage(value: XaiUsage): UsageSnapshot {
  const config = value.config ?? value;
  const period = config.currentPeriod;
  const periodType = period?.type ?? "";

  let usedPercent = config.creditUsagePercent;
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
        const pct = product.usagePercent;
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
  const expires = auth.expires;
  if (!isExpiring(expires)) return auth;
  if (!auth.refresh) {
    if (expires !== undefined && expires <= Date.now()) {
      throw new Error("xAI OAuth token expired; run `pi /login` and select xAI");
    }
    return auth;
  }
  const refreshToken = auth.refresh;

  const refreshed = await withTimeout((signal) =>
    requestTokenRefresh({
      urls: [XAI_TOKEN_URL],
      clientId: XAI_CLIENT_ID,
      refreshToken,
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

  const baseHeaders = {
    accept: "application/json",
    authorization: `Bearer ${auth.access}`,
    "X-XAI-Token-Auth": "xai-grok-cli",
  };
  const body: unknown = await withTimeout(async (signal) => {
    let userId: string | undefined;
    try {
      const identityResponse = await fetch(XAI_USER_URL, { signal, headers: baseHeaders, redirect: "error" });
      if (identityResponse.ok) {
        const identity = await identityResponse.json();
        const identityParsed = v.safeParse(xaiIdentitySchema, identity);
        userId = identityParsed.success ? identityParsed.output.userId : undefined;
      } else {
        void identityResponse.body?.cancel().catch(() => undefined);
      }
    } catch {
      // Billing often works with bearer alone.
    }
    const headers = userId ? { ...baseHeaders, "x-userid": userId } : baseHeaders;
    const response = await fetch(XAI_BILLING_URL, { signal, headers, redirect: "error" });
    if (!response.ok) throw new Error(`xAI usage request returned ${response.status}`);
    return (await response.json());
  });
  const parsed = v.safeParse(xaiUsageSchema, body);
  if (!parsed.success) throw new Error("xAI usage response was not an object");
  return parseXaiUsage(parsed.output);
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
