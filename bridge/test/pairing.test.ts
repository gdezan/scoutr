import { test } from "node:test";
import assert from "node:assert/strict";
import { buildPairingPayload, parsePairingPayload } from "../src/pairing.js";

test("buildPairingPayload emits compact v1 JSON with host + token", () => {
  const payload = buildPairingPayload({
    host: "https://artemis.tail7dc568.ts.net",
    token: "cockpit_secret",
  });
  assert.equal(payload, '{"v":1,"host":"https://artemis.tail7dc568.ts.net","token":"cockpit_secret"}');
});

test("buildPairingPayload includes ntfy when both url and topic are set", () => {
  const payload = buildPairingPayload({
    host: "https://artemis.tail7dc568.ts.net",
    token: "cockpit_secret",
    ntfyUrl: "https://artemis.tail7dc568.ts.net/ntfy",
    ntfyTopic: "cockpit_topic",
  });
  assert.deepEqual(parsePairingPayload(payload), {
    v: 1,
    host: "https://artemis.tail7dc568.ts.net",
    token: "cockpit_secret",
    ntfy: { url: "https://artemis.tail7dc568.ts.net/ntfy", topic: "cockpit_topic" },
  });
});

test("parsePairingPayload round-trips the builder output", () => {
  const built = buildPairingPayload({
    host: "http://127.0.0.1:8737",
    token: "cockpit_abc",
  });
  assert.deepEqual(parsePairingPayload(built), {
    v: 1,
    host: "http://127.0.0.1:8737",
    token: "cockpit_abc",
  });
});

test("parsePairingPayload rejects garbage, wrong version, and missing fields", () => {
  assert.equal(parsePairingPayload("not json"), null);
  assert.equal(parsePairingPayload('{"v":2,"host":"h","token":"t"}'), null);
  assert.equal(parsePairingPayload('{"v":1,"host":"","token":"t"}'), null);
  assert.equal(parsePairingPayload('{"v":1,"host":"h"}'), null);
  assert.equal(parsePairingPayload('{"v":1,"token":"t"}'), null);
  // ntfy with only one field is dropped, not fatal
  assert.deepEqual(parsePairingPayload('{"v":1,"host":"h","token":"t","ntfy":{"url":"u"}}'), {
    v: 1,
    host: "h",
    token: "t",
  });
});
