import { test } from "node:test";
import assert from "node:assert/strict";
import { buildPairingPayload, parsePairingPayload } from "../src/pairing.js";

const TAILSCALE = { kind: "tailscale", publicUrl: "https://artemis.tail7dc568.ts.net" } as const;
const CLOUDFLARE = { kind: "cloudflare", publicUrl: "https://scoutr.example.com" } as const;
const CUSTOM = { kind: "custom", publicUrl: "http://192.168.1.10:8737" } as const;

test("buildPairingPayload emits compact v1 JSON with host + token for tailscale", () => {
  const payload = buildPairingPayload({ exposure: TAILSCALE, token: "scoutr_secret" });
  assert.equal(payload, '{"v":1,"host":"https://artemis.tail7dc568.ts.net","token":"scoutr_secret"}');
});

test("the payload carries no push discovery: the app registers itself after pairing", () => {
  const payload = buildPairingPayload({ exposure: TAILSCALE, token: "scoutr_secret" });
  assert.deepEqual(Object.keys(JSON.parse(payload)).sort(), ["host", "token", "v"]);
});

test("parsePairingPayload round-trips the v1 builder output", () => {
  const built = buildPairingPayload({
    exposure: { kind: "tailscale", publicUrl: "http://127.0.0.1:8737", loopbackFallback: true },
    token: "scoutr_abc",
  });
  assert.deepEqual(parsePairingPayload(built), {
    v: 1,
    host: "http://127.0.0.1:8737",
    token: "scoutr_abc",
  });
});

test("buildPairingPayload emits v2 with the exposure kind for cloudflare", () => {
  const payload = buildPairingPayload({ exposure: CLOUDFLARE, token: "scoutr_secret" });
  assert.equal(
    payload,
    '{"v":2,"host":"https://scoutr.example.com","token":"scoutr_secret","exposure":{"kind":"cloudflare"}}',
  );
  assert.deepEqual(parsePairingPayload(payload), {
    v: 2,
    host: "https://scoutr.example.com",
    token: "scoutr_secret",
    exposure: { kind: "cloudflare" },
  });
});

test("buildPairingPayload emits v2 for custom exposure", () => {
  const payload = buildPairingPayload({ exposure: CUSTOM, token: "scoutr_abc" });
  assert.deepEqual(parsePairingPayload(payload), {
    v: 2,
    host: "http://192.168.1.10:8737",
    token: "scoutr_abc",
    exposure: { kind: "custom" },
  });
});

test("buildPairingPayload never emits v1 for cloudflare or custom exposure", () => {
  for (const exposure of [CLOUDFLARE, CUSTOM]) {
    const parsed = JSON.parse(buildPairingPayload({ exposure, token: "t" }));
    assert.equal(parsed.v, 2);
    assert.equal(parsed.exposure.kind, exposure.kind);
  }
});

test("v2 payloads carry no edge-auth fields", () => {
  const parsed = JSON.parse(buildPairingPayload({ exposure: CLOUDFLARE, token: "t" }));
  assert.deepEqual(Object.keys(parsed), ["v", "host", "token", "exposure"]);
  assert.deepEqual(Object.keys(parsed.exposure), ["kind"]);
});

test("parsePairingPayload rejects garbage, wrong version, and missing fields", () => {
  assert.equal(parsePairingPayload("not json"), null);
  assert.equal(parsePairingPayload('{"v":3,"host":"h","token":"t"}'), null);
  assert.equal(parsePairingPayload('{"v":1,"host":"","token":"t"}'), null);
  assert.equal(parsePairingPayload('{"v":1,"host":"h"}'), null);
  assert.equal(parsePairingPayload('{"v":1,"token":"t"}'), null);
  // Unknown fields — e.g. a QR printed by an older bridge — are ignored, not fatal.
  assert.deepEqual(parsePairingPayload('{"v":1,"host":"h","token":"t","legacyPush":{"url":"u"}}'), {
    v: 1,
    host: "h",
    token: "t",
  });
});

test("parsePairingPayload rejects v2 with a missing or unknown exposure kind", () => {
  assert.equal(parsePairingPayload('{"v":2,"host":"h","token":"t"}'), null);
  assert.equal(parsePairingPayload('{"v":2,"host":"h","token":"t","exposure":{}}'), null);
  assert.equal(parsePairingPayload('{"v":2,"host":"h","token":"t","exposure":{"kind":"tailscale"}}'), null);
  assert.equal(parsePairingPayload('{"v":2,"host":"h","token":"t","exposure":{"kind":"ngrok"}}'), null);
  assert.equal(parsePairingPayload('{"v":2,"host":"h","token":"t","exposure":"cloudflare"}'), null);
});
