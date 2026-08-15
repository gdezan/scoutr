import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import assert from "node:assert/strict";
import { getCodexAuth, getApiKeyAuth, getOAuthAuth, persistOAuthAuth } from "../src/usage/auth.js";
import { isExpiring } from "../src/usage/oauth.js";
import { fetchClaudeUsage, parseClaudeUsage } from "../src/usage/claude.js";
import { parseOpencodeGoUsage, parseXaiUsage, USAGE_PROVIDERS, UsageService, type UsageProvider } from "../src/usage/providers.js";

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
  assert.deepEqual(USAGE_PROVIDERS.map((provider) => provider.id), [
    "codex",
    "claude",
    "deepseek",
    "xai",
    "opencode-go",
  ]);
});

test("parseOpencodeGoUsage maps the Go plan's three spend caps", () => {
  const snapshot = parseOpencodeGoUsage({
    usage: {
      rolling: { status: "ok", percent: 25, resetsAt: "2026-08-15T20:24:13.661Z" },
      weekly: { status: "ok", percent: 91, resetsAt: "2026-08-17T00:00:00.661Z" },
      monthly: { status: "ok", percent: 48, resetsAt: "2026-09-09T16:10:03.661Z" },
    },
  });

  assert.equal(snapshot.provider, "opencode-go");
  assert.equal(snapshot.label, "OpenCode Go");
  assert.deepEqual(
    snapshot.windows.map(({ label, usedPercent, windowSeconds }) => ({ label, usedPercent, windowSeconds })),
    [
      { label: "5h", usedPercent: 25, windowSeconds: 5 * 60 * 60 },
      { label: "wk", usedPercent: 91, windowSeconds: 7 * 24 * 60 * 60 },
      { label: "mo", usedPercent: 48, windowSeconds: undefined },
    ],
  );
  assert.equal(snapshot.windows[0]?.resetAt, Math.floor(Date.parse("2026-08-15T20:24:13.661Z") / 1000));
});

test("parseOpencodeGoUsage skips buckets without a percent and rejects empty responses", () => {
  const partial = parseOpencodeGoUsage({
    usage: { rolling: { status: "ok", percent: 5 }, weekly: { status: "unknown" } },
  });
  assert.deepEqual(partial.windows.map((window) => window.label), ["5h"]);
  assert.throws(() => parseOpencodeGoUsage({ usage: {} }), /no recognized windows/);
  assert.throws(() => parseOpencodeGoUsage({ nope: 1 }), /was not an object/);
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

test("fetchClaudeUsage refreshes an expired token and persists the rotated one", { concurrency: false }, async () => {
  const configDir = await mkdtemp(join(tmpdir(), "scoutr-claude-refresh-"));
  const statePath = `${configDir}.json`;
  const credentialsPath = join(configDir, ".credentials.json");
  const originalConfigDir = process.env.CLAUDECONFIGDIR;
  const originalFetch = globalThis.fetch;
  process.env.CLAUDECONFIGDIR = configDir;
  await writeFile(
    credentialsPath,
    JSON.stringify({
      claudeAiOauth: {
        accessToken: "stale-token",
        refreshToken: "refresh-1",
        expiresAt: Date.now() - 1000,
        subscriptionType: "max",
      },
    }),
  );

  try {
    const seen: string[] = [];
    globalThis.fetch = async (input, init) => {
      const url = String(input);
      seen.push(url);
      if (url.endsWith("/v1/oauth/token")) {
        assert.equal(init?.method, "POST");
        const body = JSON.parse(String(init?.body)) as Record<string, string>;
        assert.equal(body.grant_type, "refresh_token");
        assert.equal(body.refresh_token, "refresh-1");
        assert.equal(body.client_id, "9d1c250a-e61b-44d9-88ed-5944d1962f5e");
        return new Response(
          JSON.stringify({ access_token: "fresh-token", refresh_token: "refresh-2", expires_in: 3600 }),
          { status: 200, headers: { "content-type": "application/json" } },
        );
      }
      assert.equal(new Headers(init?.headers).get("authorization"), "Bearer fresh-token");
      return new Response(JSON.stringify({ five_hour: { utilization: 12 } }), { status: 200 });
    };

    const snapshot = await fetchClaudeUsage();
    assert.equal(snapshot.windows[0]?.usedPercent, 12);
    assert.equal(snapshot.error, undefined);
    assert.ok(seen.some((url) => url.includes("/v1/oauth/token")), "expected a token refresh");

    // The rotated refresh token must land on disk, and unrelated fields must survive.
    const persisted = JSON.parse(await readFile(credentialsPath, "utf8")) as Record<string, any>;
    assert.equal(persisted.claudeAiOauth.accessToken, "fresh-token");
    assert.equal(persisted.claudeAiOauth.refreshToken, "refresh-2");
    assert.equal(persisted.claudeAiOauth.subscriptionType, "max");
    assert.ok(persisted.claudeAiOauth.expiresAt > Date.now());
  } finally {
    globalThis.fetch = originalFetch;
    if (originalConfigDir === undefined) delete process.env.CLAUDECONFIGDIR;
    else process.env.CLAUDECONFIGDIR = originalConfigDir;
    await rm(configDir, { recursive: true, force: true });
    await rm(statePath, { force: true });
  }
});

test("fetchClaudeUsage retries a 401 with the token Claude Code just wrote", { concurrency: false }, async () => {
  const configDir = await mkdtemp(join(tmpdir(), "scoutr-claude-401-"));
  const statePath = `${configDir}.json`;
  const credentialsPath = join(configDir, ".credentials.json");
  const originalConfigDir = process.env.CLAUDECONFIGDIR;
  const originalFetch = globalThis.fetch;
  process.env.CLAUDECONFIGDIR = configDir;
  // Expiry still in the future, so nothing refreshes pre-emptively.
  await writeFile(
    credentialsPath,
    JSON.stringify({ claudeAiOauth: { accessToken: "old-token", refreshToken: "r", expiresAt: Date.now() + 3_600_000 } }),
  );

  try {
    let attempts = 0;
    globalThis.fetch = async (input, init) => {
      assert.ok(!String(input).includes("/v1/oauth/token"), "must not spend a refresh when the file is fresher");
      attempts += 1;
      const token = new Headers(init?.headers).get("authorization");
      if (token === "Bearer old-token") {
        // Simulate `claude` rotating the file behind us between the two attempts.
        await writeFile(
          credentialsPath,
          JSON.stringify({ claudeAiOauth: { accessToken: "cli-token", refreshToken: "r", expiresAt: Date.now() + 3_600_000 } }),
        );
        return new Response(JSON.stringify({ error: { message: "expired" } }), { status: 401 });
      }
      assert.equal(token, "Bearer cli-token");
      return new Response(JSON.stringify({ five_hour: { utilization: 77 } }), { status: 200 });
    };

    const snapshot = await fetchClaudeUsage();
    assert.equal(snapshot.windows[0]?.usedPercent, 77);
    assert.equal(attempts, 2);
  } finally {
    globalThis.fetch = originalFetch;
    if (originalConfigDir === undefined) delete process.env.CLAUDECONFIGDIR;
    else process.env.CLAUDECONFIGDIR = originalConfigDir;
    await rm(configDir, { recursive: true, force: true });
    await rm(statePath, { force: true });
  }
});

test("persistOAuthAuth merges into pi's auth store without dropping other providers", async () => {
  const dir = await mkdtemp(join(tmpdir(), "scoutr-pi-auth-"));
  const authPath = join(dir, "auth.json");
  await writeFile(
    authPath,
    JSON.stringify({
      "openai-codex": { type: "oauth", access: "a1", refresh: "r1", expires: 1, accountId: "acct" },
      deepseek: { type: "api-key", key: "sk-keep" },
    }),
  );

  try {
    await persistOAuthAuth(authPath, "openai-codex", { access: "a2", refresh: "r2", expires: 2 });
    const store = JSON.parse(await readFile(authPath, "utf8")) as Record<string, any>;
    assert.equal(store["openai-codex"].access, "a2");
    assert.equal(store["openai-codex"].refresh, "r2");
    assert.equal(store["openai-codex"].expires, 2);
    // Fields the bridge does not manage must survive the write.
    assert.equal(store["openai-codex"].accountId, "acct");
    assert.equal(store["openai-codex"].type, "oauth");
    assert.equal(store.deepseek.key, "sk-keep");
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("isExpiring refreshes only on a known-spent expiry", () => {
  assert.equal(isExpiring(Date.now() - 1), true);
  assert.equal(isExpiring(Date.now() + 60_000), true, "inside the skew window");
  assert.equal(isExpiring(Date.now() + 3_600_000), false);
  // Unknown expiry is not a reason to burn a rotation; the 401 path covers it.
  assert.equal(isExpiring(undefined), false);
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
  const context = { store: {}, authPath: "/nonexistent/auth.json" };

  await service.get(provider, context);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const failed = await service.get(provider, context);
  const retried = await service.get(provider, context);

  assert.equal(failed.updatedAt, 123);
  assert.deepEqual(failed.windows, [{ label: "day", usedPercent: 25 }]);
  assert.equal(failed.error, "provider offline");
  assert.equal(retried.error, "provider offline");
  assert.equal(calls, 3);
});
