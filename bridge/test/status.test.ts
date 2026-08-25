import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { StatusTracker } from "../src/status.js";

describe("StatusTracker", () => {
  test("tracks when a pane entered its current status", () => {
    const tracker = new StatusTracker();
    assert.equal(tracker.since("p1"), undefined);
    tracker.note("p1", "working");
    const at = tracker.since("p1");
    assert.notEqual(at, undefined);
    assert.ok(at <= Date.now());
    // A newer observation replaces the timestamp (Date.now() granularity
    // means the two notes can land in the same millisecond).
    tracker.note("p1", "blocked");
    const later = tracker.since("p1");
    assert.notEqual(later, undefined);
    assert.ok(later >= at);
  });

  test("prune forgets panes that no longer exist", () => {
    const tracker = new StatusTracker();
    tracker.note("p1", "working");
    tracker.note("p2", "idle");
    tracker.prune(new Set(["p2"]));
    assert.equal(tracker.since("p1"), undefined);
    const since = tracker.since("p2");
    assert.notEqual(since, undefined);
  });
});
