import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, statSync, chmodSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { defaultConfigPath, generateToken, loadOrCreateConfig } from "../src/config.js";

describe("defaultConfigPath", () => {
  test("honors XDG_CONFIG_HOME", () => {
    const previous = process.env.XDG_CONFIG_HOME;
    process.env.XDG_CONFIG_HOME = "/tmp/scoutr-xdg-test";
    try {
      assert.equal(defaultConfigPath(), join("/tmp/scoutr-xdg-test", "scoutr", "config.json"));
    } finally {
      if (previous === undefined) delete process.env.XDG_CONFIG_HOME;
      else process.env.XDG_CONFIG_HOME = previous;
    }
  });
});

describe("generateToken", () => {
  test("produces a long random token with the scoutr_ prefix", () => {
    const token = generateToken();
    assert.ok(token.startsWith("scoutr_"));
    assert.ok(token.length >= 16);
    assert.notEqual(token, generateToken());
  });
});

describe("loadOrCreateConfig", () => {
  let dir: string;

  before(() => {
    dir = mkdtempSync(join(tmpdir(), "scoutr-config-"));
  });

  after(() => {
    rmSync(dir, { recursive: true, force: true });
  });

  test("creates a config with a fresh token when none exists", async () => {
    const path = join(dir, "fresh", "config.json");
    const config = await loadOrCreateConfig(path);
    assert.ok(config.token.length >= 16);
    assert.equal(config.port, 8737);
    assert.ok(config.ntfyTopic?.startsWith("scoutr_"));
    assert.equal(config.configDir, join(dir, "fresh"));
    // Written with owner-only permissions.
    assert.equal(statSync(path).mode & 0o777, 0o600);
  });

  test("loads an existing valid config and persists missing fields", async () => {
    const path = join(dir, "valid", "config.json");
    const config = await loadOrCreateConfig(path);
    const again = await loadOrCreateConfig(path);
    assert.equal(again.token, config.token);
    assert.equal(again.port, 8737);
    assert.equal(again.ntfyTopic, config.ntfyTopic);
  });

  test("recreates the config when the stored token is shorter than 16 chars", async () => {
    const path = join(dir, "short-token", "config.json");
    mkdirSync(join(dir, "short-token"), { recursive: true });
    await writeFile(path, JSON.stringify({ token: "short", port: 1 }));
    const config = await loadOrCreateConfig(path);
    assert.ok(config.token.length >= 16);
    assert.notEqual(config.token, "short");
  });

  test("generates a topic when ntfy is enabled but the topic is missing", async () => {
    const path = join(dir, "with-url", "config.json");
    mkdirSync(join(dir, "with-url"), { recursive: true });
    await writeFile(
      path,
      JSON.stringify({ token: "0123456789abcdef", port: 8737, ntfyUrl: "https://ntfy.example" }),
    );
    const config = await loadOrCreateConfig(path);
    assert.equal(config.ntfyUrl, "https://ntfy.example");
    assert.ok(config.ntfyTopic?.startsWith("scoutr_"));
  });

  test("keeps the token when the parsed config cannot be re-persisted", { skip: process.getuid?.() === 0 }, async () => {
    // Only a missing or unparseable config may mint a token. A valid config
    // whose write fails (read-only directory) must be kept
    // and returned — silently regenerating it would 401 every paired phone.
    const roDir = join(dir, "readonly");
    mkdirSync(roDir, { recursive: true });
    const path = join(roDir, "config.json");
    const token = "0123456789abcdef";
    await writeFile(path, JSON.stringify({ token, port: 8737 }));
    // Directory permissions do not stop a write to an existing file — the
    // file itself must lose its write bit for writeFile to fail (EACCES).
    chmodSync(path, 0o400);
    try {
      const config = await loadOrCreateConfig(path);
      assert.equal(config.token, token, "token must survive a failed re-persist");
      assert.equal(config.port, 8737);
    } finally {
      chmodSync(path, 0o600);
    }
  });
});
