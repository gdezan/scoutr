import { test } from "node:test";
import assert from "node:assert/strict";
import { getCodexAuth, getApiKeyAuth } from "../src/usage/auth.js";
import { parseXaiUsage } from "../src/usage/providers.js";

test("auth helpers extract codex and api-key credentials", () => {
  const store = {
    "openai-codex": { type: "oauth", access: "tok", refresh: "ref", expires: 123, accountId: "acc" },
    deepseek: { type: "api-key", key: "sk-123" },
  };
  const codex = getCodexAuth(store);
  assert.equal(codex?.access, "tok");
  assert.equal(codex?.accountId, "acc");
  const deepseek = getApiKeyAuth(store, "deepseek");
  assert.equal(deepseek?.key, "sk-123");
  assert.equal(getApiKeyAuth(store, "missing"), undefined);
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

test("parseXaiUsage falls back to products", () => {
  const snapshot = parseXaiUsage({
    config: { productUsage: [{ usagePercent: 7 }] },
  });
  assert.equal(snapshot.windows[0]?.usedPercent, 7);
});

test("parseXaiUsage rejects unrecognized shapes", () => {
  assert.throws(() => parseXaiUsage({ nope: true }), /no recognized windows/);
});
