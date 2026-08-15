import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import assert from "node:assert/strict";
import { getCodexAuth, getApiKeyAuth, getOAuthAuth } from "../src/usage/auth.js";
import { fetchClaudeUsage, parseClaudeUsage } from "../src/usage/claude.js";
import { parseXaiUsage, USAGE_PROVIDERS, UsageService, type UsageProvider } from "../src/usage/providers.js";

test("auth helpers extract codex, oauth, and api-key credentials", () => {
  const store = {
    "openai-codex": { type: "oauth", access: "tok", refresh: "ref", expires: 123, accountId: "acc" },
    deepseek: { type: "api-key", key: "sk-123" },
    xai: { type: "oauth", access: "xai-access", refresh: "xai-refresh", expires: 999 },
  };
  const codex = getCodexAuth(store);
  assert.equal(codex?.access, "tok");
  assert.equal(codex?.accountId, "acc");
  const deepseek = getApiKeyAuth(store, "deepseek");
  assert.equal(deepseek?.key, "sk-123");
  assert.equal(getApiKeyAuth(store, "missing"), undefined);
  const xai = getOAuthAuth(store, "xai");
  assert.equal(xai?.access, "xai-access");
  assert.equal(xai?.refresh, "xai-refresh");
  assert.equal(getOAuthAuth(store, "missing"), undefined);
  // xAI is OAuth — the api-key helper must not invent a key from the access token.
  assert.equal(getApiKeyAuth(store, "xai"), undefined);
});

test("parseClaudeUsage handles Claude Code's legacy quota windows", () => {
  const snapshot = parseClaudeUsage({
    five_hour: { utilization: 42.5, resets_at: "2026-08-13T12:00:00Z" },
    seven_day: { utilization: 61, resets_at: 1_786_684_800 },
    seven_day_opus: { utilization: 12, resets_at: "1786684800000" },
  });

  assert.equal(snapshot.provider, "claude");
  assert.equal(snapshot.label, "Claude");
  assert.deepEqual(
    snapshot.windows.map(({ label, usedPercent, windowSeconds, resetAt }) => ({ label, usedPercent, windowSeconds, resetAt })),
    [
      { label: "5h", usedPercent: 42.5, windowSeconds: 5 * 60 * 60, resetAt: 1_786_622_400 },
      { label: "7d", usedPercent: 61, windowSeconds: 7 * 24 * 60 * 60, resetAt: 1_786_684_800 },
      { label: "7d Opus", usedPercent: 12, windowSeconds: 7 * 24 * 60 * 60, resetAt: 1_786_684_800 },
    ],
  );
});

test("parseClaudeUsage prefers the generalized limits array", () => {
  const snapshot = parseClaudeUsage({
    five_hour: { utilization: 99 },
    seven_day_sonnet: { utilization: 15 },
    limits: [
      { kind: "session", percent: 20, resets_at: "2026-08-13T12:00:00Z", is_active: true },
      { kind: "weekly_all", percent: 30, resets_at: "2026-08-18T12:00:00Z", is_active: false },
      {
        kind: "weekly_scoped",
        percent: 40,
        resets_at: "2026-08-18T12:00:00Z",
        scope: { model: { display_name: "Opus" } },
      },
      { kind: "unknown_future_limit", percent: 50 },
    ],
  });

  assert.deepEqual(
    snapshot.windows.map(({ label, usedPercent }) => ({ label, usedPercent })),
    [
      { label: "5h", usedPercent: 20 },
      { label: "7d", usedPercent: 30 },
      { label: "7d Opus", usedPercent: 40 },
      { label: "7d Sonnet", usedPercent: 15 },
    ],
  );
});

test("Claude follows Codex in the usage provider registry", () => {
  assert.deepEqual(USAGE_PROVIDERS.map((provider) => provider.id), ["codex", "claude", "deepseek", "xai"]);
});

test("fetchClaudeUsage authenticates as Claude Code and keeps stale Claude cache on failure", { concurrency: false }, async () => {
  const configDir = await mkdtemp(join(tmpdir(), "scoutr-claude-usage-"));
  const statePath = `${configDir}.json`;
  const originalConfigDir = process.env.CLAUDECONFIGDIR;
  const originalFetch = globalThis.fetch;
  process.env.CLAUDECONFIGDIR = configDir;
  await writeFile(
    join(configDir, ".credentials.json"),
    JSON.stringify({ claudeAiOauth: { accessToken: "claude-token" } }),
  );

  try {
    globalThis.fetch = async (input, init) => {
      assert.equal(String(input), "https://api.anthropic.com/api/oauth/usage");
      const headers = new Headers(init?.headers);
      assert.equal(headers.get("authorization"), "Bearer claude-token");
      assert.equal(headers.get("anthropic-beta"), "oauth-2025-04-20");
      assert.equal(headers.get("content-type"), "application/json");
      assert.match(headers.get("user-agent") ?? "", /^claude-code\//);
      return new Response(JSON.stringify({ five_hour: { utilization: 25 } }), { status: 200 });
    };
    const live = await fetchClaudeUsage();
    assert.equal(live.windows[0]?.usedPercent, 25);
    assert.equal(live.error, undefined);

    const withoutPiAuth = await new UsageService({ authPath: join(configDir, "missing-auth.json") }).all();
    assert.equal(withoutPiAuth[1]?.provider, "claude");
    assert.equal(withoutPiAuth[1]?.windows[0]?.usedPercent, 25);

    const cachedAt = Date.now() - 120_000;
    await writeFile(
      statePath,
      JSON.stringify({
        cachedUsageUtilization: {
          fetchedAtMs: cachedAt,
          utilization: { five_hour: { utilization: 35 } },
        },
      }),
    );
    globalThis.fetch = async () => new Response(
      JSON.stringify({ error: { type: "rate_limit_error", message: "Please try again later" } }),
      { status: 429, headers: { "content-type": "application/json" } },
    );

    const stale = await fetchClaudeUsage();
    assert.equal(stale.windows[0]?.usedPercent, 35);
    assert.equal(stale.updatedAt, cachedAt);
    assert.equal(stale.error, "Claude usage request returned 429: Please try again later");
  } finally {
    globalThis.fetch = originalFetch;
    if (originalConfigDir === undefined) delete process.env.CLAUDECONFIGDIR;
    else process.env.CLAUDECONFIGDIR = originalConfigDir;
    await rm(configDir, { recursive: true, force: true });
    await rm(statePath, { force: true });
  }
});

test("parseXaiUsage handles the monthly credits shape", () => {
  const snapshot = parseXaiUsage({
    config: {
      creditUsagePercent: 42.5,
      monthlyLimit: { val: 10000 },
      currentPeriod: { type: "MONTHLY", start: "2026-08-01T00:00:00Z", end: "2026-08-31T23:59:59Z" },
    },
  });
  assert.equal(snapshot.provider, "xai");
  assert.equal(snapshot.windows[0]?.usedPercent, 42.5);
  assert.equal(snapshot.windows[0]?.label, "mo");
  assert.ok(snapshot.windows[0]?.resetAt);
});

test("parseXaiUsage handles live SuperGrok weekly credits shape", () => {
  const snapshot = parseXaiUsage({
    config: {
      currentPeriod: {
        type: "USAGE_PERIOD_TYPE_WEEKLY",
        start: "2026-08-11T02:26:29.391390+00:00",
        end: "2026-08-18T02:26:29.391390+00:00",
      },
      creditUsagePercent: 10,
      productUsage: [{ product: "GrokBuild", usagePercent: 10 }],
      isUnifiedBillingUser: true,
      prepaidBalance: { val: 0 },
      billingPeriodStart: "2026-08-11T02:26:29.391390+00:00",
      billingPeriodEnd: "2026-08-18T02:26:29.391390+00:00",
    },
  });
  assert.equal(snapshot.windows[0]?.label, "wk");
  assert.equal(snapshot.windows[0]?.usedPercent, 10);
  assert.equal(snapshot.windows[0]?.windowSeconds, 7 * 24 * 60 * 60);
  assert.ok(snapshot.windows[0]?.resetAt);
});

test("parseXaiUsage defaults omitted creditUsagePercent to 0 when a period is present", () => {
  const snapshot = parseXaiUsage({
    config: {
      currentPeriod: {
        type: "USAGE_PERIOD_TYPE_WEEKLY",
        start: "2026-08-11T00:00:00Z",
        end: "2026-08-18T00:00:00Z",
      },
    },
  });
  assert.equal(snapshot.windows[0]?.usedPercent, 0);
  assert.equal(snapshot.windows[0]?.label, "wk");
});

test("parseXaiUsage falls back to products", () => {
  const snapshot = parseXaiUsage({
    config: { productUsage: [{ usagePercent: 7 }] },
  });
  assert.equal(snapshot.windows[0]?.usedPercent, 7);
});

test("parseXaiUsage rejects unrecognized shapes", () => {
  assert.throws(() => parseXaiUsage({ nope: true }), /no recognized windows/);
});


test("failed refreshes keep successful data stale and remain retryable", async () => {
  let calls = 0;
  const provider: UsageProvider = {
    id: "test",
    label: "Test",
    fetch: async () => {
      calls += 1;
      if (calls === 1) {
        return { provider: "test", label: "Test", windows: [{ label: "day", usedPercent: 25 }], updatedAt: 123 };
      }
      throw new Error("provider offline");
    },
  };
  const service = new UsageService({ cacheTtlMs: 1 });

  await service.get(provider, {});
  await new Promise((resolve) => setTimeout(resolve, 5));
  const failed = await service.get(provider, {});
  const retried = await service.get(provider, {});

  assert.equal(failed.updatedAt, 123);
  assert.deepEqual(failed.windows, [{ label: "day", usedPercent: 25 }]);
  assert.equal(failed.error, "provider offline");
  assert.equal(retried.error, "provider offline");
  assert.equal(calls, 3);
});
