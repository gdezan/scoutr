import { test } from "node:test";
import assert from "node:assert/strict";
import { getCodexAuth, getApiKeyAuth, getOAuthAuth } from "../src/usage/auth.js";
import { parseXaiUsage, UsageService, type UsageProvider } from "../src/usage/providers.js";

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
