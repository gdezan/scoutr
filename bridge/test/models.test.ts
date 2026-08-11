import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readModelsCatalog, modelsStorePath } from "../src/agents/pi/models.js";

describe("modelsStorePath", () => {
  it("joins the pi agent dir with models-store.json", () => {
    assert.equal(modelsStorePath("/tmp/pi"), "/tmp/pi/models-store.json");
  });
});

describe("readModelsCatalog", () => {
  let dir: string;

  before(() => {
    dir = mkdtempSync(join(tmpdir(), "cockpit-models-"));
    writeFileSync(
      join(dir, "models-store.json"),
      JSON.stringify({
        "openai-codex": {
          models: [
            {
              id: "gpt-5.3-codex-spark",
              name: "GPT-5.3 Codex Spark",
              provider: "openai-codex",
              reasoning: true,
              contextWindow: 128000,
              thinkingLevelMap: { xhigh: "xhigh", minimal: "low" },
            },
            { id: "gpt-5.4", name: "GPT-5.4", provider: "openai-codex", reasoning: true },
          ],
        },
        deepseek: {
          models: [
            {
              id: "deepseek-v4-flash",
              name: "DeepSeek V4 Flash",
              reasoning: true,
              contextWindow: 1000000,
              thinkingLevelMap: { minimal: "mini", medium: "medium", high: "high" },
            },
          ],
        },
      }),
    );
  });

  after(() => rmSync(dir, { recursive: true, force: true }));

  it("flattens providers into a provider-grouped catalog", () => {
    const catalog = readModelsCatalog(dir);
    assert.equal(catalog.providers.length, 2);
    assert.deepEqual(
      catalog.providers.map((p) => p.name),
      ["openai-codex", "deepseek"],
    );
    assert.equal(catalog.providers[0]!.models.length, 2);
    assert.equal(catalog.providers[1]!.models.length, 1);
  });

  it("matches pi's supported thinking-level order and exclusions", () => {
    const [codex, deepseek] = readModelsCatalog(dir).providers;
    assert.deepEqual(codex!.models[0]!.thinkingLevels, ["off", "minimal", "low", "medium", "high", "xhigh"]);
    assert.deepEqual(codex!.models[1]!.thinkingLevels, ["off", "minimal", "low", "medium", "high"]);
    assert.deepEqual(deepseek!.models[0]!.thinkingLevels, ["off", "minimal", "low", "medium", "high"]);
  });

  it("defaults missing fields gracefully", () => {
    const [codex, deepseek] = readModelsCatalog(dir).providers;
    assert.equal(codex!.models[1]!.contextWindow, null);
    assert.deepEqual(codex!.models[1]!.thinkingLevels, ["off", "minimal", "low", "medium", "high"]);
    assert.equal(deepseek!.models[0]!.contextWindow, 1000000);
  });

  it("throws on a missing file so the HTTP layer can report it", () => {
    const empty = mkdtempSync(join(tmpdir(), "cockpit-models-empty-"));
    try {
      assert.throws(() => readModelsCatalog(empty));
    } finally {
      rmSync(empty, { recursive: true, force: true });
    }
  });
});
