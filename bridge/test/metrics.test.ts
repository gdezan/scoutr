import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { BridgeMetrics, normalizeMetricPath } from "../src/metrics.js";

describe("BridgeMetrics", () => {
  test("records route status, response bytes, and duration without ids", () => {
    const metrics = new BridgeMetrics();
    const request = metrics.beginRequest("GET", "/api/sessions/secret-pane/control?token=secret");

    request.complete(503, 17);
    request.complete(200, 999);

    const snapshot = metrics.snapshot();
    const route = snapshot.routes["GET /api/sessions/:paneId"];
    assert.ok(route);
    assert.equal(snapshot.activeRequests, 0);
    assert.equal(snapshot.requests, 1);
    assert.equal(snapshot.responses, 1);
    assert.equal(snapshot.errors, 1);
    assert.equal(snapshot.responseBytes, 17);
    assert.equal(route.statuses["503"], 1);
    assert.equal(route.statuses["200"], undefined);
    assert.equal(normalizeMetricPath("/api/sessions/secret-pane/control?token=secret"), "/api/sessions/:paneId");
    assert.equal(JSON.stringify(snapshot).includes("secret"), false);
  });

  test("settles transport failures without reporting a response", () => {
    const metrics = new BridgeMetrics();
    const request = metrics.beginRequest("GET", "/api/health");

    request.fail();
    request.fail();

    const snapshot = metrics.snapshot();
    assert.equal(snapshot.activeRequests, 0);
    assert.equal(snapshot.requests, 1);
    assert.equal(snapshot.responses, 0);
    assert.equal(snapshot.errors, 1);
    assert.equal(snapshot.routes["GET /api/health"]?.responses, 0);
    assert.equal(snapshot.routes["GET /api/health"]?.errors, 1);
  });

  test("balances socket lifecycle callbacks and never decrements twice", () => {
    const metrics = new BridgeMetrics();
    const close = metrics.openSocket("feed");

    assert.equal(metrics.snapshot().sockets.feed.active, 1);
    close();
    close();

    assert.deepEqual(metrics.snapshot().sockets.feed, { opened: 1, closed: 1, active: 0 });
  });
});
