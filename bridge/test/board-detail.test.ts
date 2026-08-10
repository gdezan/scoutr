import { strict as assert } from "node:assert";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it, beforeEach, afterEach } from "node:test";
import { BoardDetailCache, deriveBoardDetail, cleanActivity } from "../src/board-detail.js";

function sessionLine(type: string, fields: Record<string, unknown>, ts: string): string {
  return JSON.stringify({ type, timestamp: ts, ...fields });
}

describe("deriveBoardDetail", () => {
  it("extracts the latest model_change", () => {
    const text = [
      sessionLine("model_change", { provider: "openai-codex", modelId: "gpt-5.4" }, "2026-08-10T00:00:00Z"),
      sessionLine("model_change", { provider: "anthropic", modelId: "claude-sonnet-4-6" }, "2026-08-10T01:00:00Z"),
    ].join("\n");
    const detail = deriveBoardDetail(text, 1);
    assert.equal(detail.model, "anthropic/claude-sonnet-4-6");
  });

  it("picks the latest meaningful user/assistant text, skipping one-char echoes", () => {
    const text = [
      sessionLine("message", { message: { role: "user", content: "Fix the billing bug" } }, "2026-08-10T00:00:00Z"),
      sessionLine("message", { message: { role: "assistant", content: "I found the rounding error." } }, "2026-08-10T00:00:10Z"),
      sessionLine("message", { message: { role: "user", content: "ok" } }, "2026-08-10T00:00:20Z"),
    ].join("\n");
    const detail = deriveBoardDetail(text, 1);
    assert.equal(detail.latestActivity, "I found the rounding error.");
    assert.equal(detail.latestActivityAtMs, Date.parse("2026-08-10T00:00:10Z"));
  });

  it("skips meaningless echoes and falls back to mtime", () => {
    const text = [
      sessionLine("message", { message: { role: "user", content: "x" } }, "2026-08-10T00:00:00Z"),
      sessionLine("message", { message: { role: "user", content: "  " } }, "2026-08-10T00:00:05Z"),
    ].join("\n");
    const mtime = Date.parse("2026-08-10T02:00:00Z");
    const detail = deriveBoardDetail(text, mtime);
    assert.equal(detail.latestActivity, "");
    assert.equal(detail.latestActivityAtMs, mtime);
  });

  it("records tool use as activity", () => {
    const text = [
      sessionLine("tool_use", { name: "bash" }, "2026-08-10T00:00:00Z"),
      sessionLine("tool_result", { name: "read" }, "2026-08-10T00:00:05Z"),
    ].join("\n");
    const detail = deriveBoardDetail(text, 1);
    assert.match(detail.latestActivity, /tool: read/);
  });

  it("is robust to malformed lines", () => {
    const detail = deriveBoardDetail("not json\n{}\n", 1);
    assert.equal(detail.model, null);
    assert.equal(detail.latestActivity, "");
  });
});

describe("BoardDetailCache", () => {
  let dir: string;

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), "cockpit-board-detail-"));
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  it("memoizes by mtime and returns stable detail", async () => {
    const path = join(dir, "s.jsonl");
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "Hello there" } }, "2026-08-10T00:00:00Z"));
    const cache = new BoardDetailCache();
    const first = await cache.detailFor(path);
    const second = await cache.detailFor(path);
    assert.equal(first?.latestActivity, "Hello there");
    assert.equal(first?.latestActivity, second?.latestActivity);
    assert.equal(cache.size, 1);
  });

  it("invalidates after the file changes", async () => {
    const path = join(dir, "s.jsonl");
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "First" } }, "2026-08-10T00:00:00Z"));
    const cache = new BoardDetailCache();
    assert.equal((await cache.detailFor(path))?.latestActivity, "First");
    await new Promise((resolve) => setTimeout(resolve, 5));
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "Second" } }, "2026-08-10T00:00:10Z"));
    assert.equal((await cache.detailFor(path))?.latestActivity, "Second");
  });

  it("returns null for missing files and prunes", async () => {
    const cache = new BoardDetailCache();
    assert.equal(await cache.detailFor(join(dir, "missing.jsonl")), null);
    const path = join(dir, "s.jsonl");
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "Hello" } }, "2026-08-10T00:00:00Z"));
    await cache.detailFor(path);
    assert.equal(cache.size, 1);
    cache.prune(new Set([join(dir, "other.jsonl")]));
    assert.equal(cache.size, 0);
  });

  it("reads only the bounded tail of a large file", async () => {
    const path = join(dir, "big.jsonl");
    // 40 small lines of noise (about 100 KiB), then the meaningful marker at the end.
    const noise = Array.from({ length: 40 }, (_, i) =>
      sessionLine("message", { message: { role: "user", content: `n${String(i).padStart(3, "0")}` + "x".repeat(2400) } }, "2026-08-10T00:00:00Z"),
    ).join("\n");
    const marker = sessionLine("message", { message: { role: "user", content: "Tail marker found" } }, "2026-08-10T01:00:00Z");
    await writeFile(path, `${noise}\n${marker}\n`);
    const cache = new BoardDetailCache();
    const detail = await cache.detailFor(path);
    assert.equal(detail?.latestActivity, "Tail marker found");
    // The activity is capped even for very long single lines.
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "y".repeat(200) } }, "2026-08-10T02:00:00Z"));
    const capped = await cache.detailFor(path);
    assert.equal(capped?.latestActivity.length, 160);
  });
});

describe("cleanActivity", () => {
  it("collapses whitespace and caps length with an ellipsis", () => {
    assert.equal(cleanActivity("  a   b  ", 10), "a b");
    assert.equal(cleanActivity("a".repeat(200), 10), `${"a".repeat(9)}…`);
  });
});
