import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, statSync, chmodSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readFile } from "node:fs/promises";
import { ConfigError, defaultConfigPath, generateToken, loadOrCreateConfig } from "../src/config.js";

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
    assert.equal(config.fcmServiceAccountPath, undefined);
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
  });

  test("recreates the config when the stored token is shorter than 16 chars", async () => {
    const path = join(dir, "short-token", "config.json");
    mkdirSync(join(dir, "short-token"), { recursive: true });
    await writeFile(path, JSON.stringify({ token: "short", port: 1 }));
    const config = await loadOrCreateConfig(path);
    assert.ok(config.token.length >= 16);
    assert.notEqual(config.token, "short");
  });

  test("keeps the configured FCM service-account path", async () => {
    const path = join(dir, "with-fcm", "config.json");
    mkdirSync(join(dir, "with-fcm"), { recursive: true });
    await writeFile(
      path,
      JSON.stringify({ token: "0123456789abcdef", port: 8737, fcmServiceAccountPath: "/keys/fcm.json" }),
    );
    const config = await loadOrCreateConfig(path);
    assert.equal(config.fcmServiceAccountPath, "/keys/fcm.json");
  });

  test("picks up a conventional FCM key next to config.json", async () => {
    const folder = join(dir, "conventional-fcm");
    mkdirSync(folder, { recursive: true });
    const path = join(folder, "config.json");
    const keyPath = join(folder, "fcm-service-account.json");
    await writeFile(path, JSON.stringify({ token: "0123456789abcdef", port: 8737 }));
    await writeFile(keyPath, "{}");
    const config = await loadOrCreateConfig(path);
    assert.equal(config.fcmServiceAccountPath, keyPath);
    const persisted = JSON.parse(await readFile(path, "utf8")) as Record<string, unknown>;
    assert.equal(persisted.fcmServiceAccountPath, keyPath);
  });

  test("defaults a config without exposure to tailscale", async () => {
    const path = join(dir, "no-exposure", "config.json");
    mkdirSync(join(dir, "no-exposure"), { recursive: true });
    await writeFile(path, JSON.stringify({ token: "0123456789abcdef", port: 8737 }));
    const config = await loadOrCreateConfig(path);
    assert.deepEqual(config.exposure, { kind: "tailscale" });
  });

  test("migrates a legacy publicHost into the tailscale public URL", async () => {
    const path = join(dir, "legacy-host", "config.json");
    mkdirSync(join(dir, "legacy-host"), { recursive: true });
    await writeFile(
      path,
      JSON.stringify({ token: "0123456789abcdef", port: 8737, publicHost: "artemis.tail7dc568.ts.net" }),
    );
    const config = await loadOrCreateConfig(path);
    assert.deepEqual(config.exposure, { kind: "tailscale", publicUrl: "artemis.tail7dc568.ts.net" });
    // The canonical shape is persisted, so the migration happens once.
    const persisted = JSON.parse(await readFile(path, "utf8")) as Record<string, unknown>;
    assert.deepEqual(persisted.exposure, { kind: "tailscale", publicUrl: "artemis.tail7dc568.ts.net" });
    assert.equal(persisted.publicHost, undefined);
  });

  test("preserves a canonical exposure config", async () => {
    const path = join(dir, "canonical", "config.json");
    mkdirSync(join(dir, "canonical"), { recursive: true });
    await writeFile(
      path,
      JSON.stringify({
        token: "0123456789abcdef",
        port: 8737,
        exposure: { kind: "cloudflare", publicUrl: "https://scoutr.example.com" },
      }),
    );
    const config = await loadOrCreateConfig(path);
    assert.deepEqual(config.exposure, { kind: "cloudflare", publicUrl: "https://scoutr.example.com" });
    assert.equal(config.token, "0123456789abcdef");
  });

  test("rejects an unknown exposure kind instead of falling back to tailscale", async () => {
    const path = join(dir, "bad-kind", "config.json");
    mkdirSync(join(dir, "bad-kind"), { recursive: true });
    const raw = JSON.stringify({ token: "0123456789abcdef", port: 8737, exposure: { kind: "ngrok" } });
    await writeFile(path, raw);
    await assert.rejects(() => loadOrCreateConfig(path), (error: unknown) => {
      assert.ok(error instanceof ConfigError);
      assert.match(error.message, /unknown exposure kind "ngrok"/);
      return true;
    });
    // The token is never rotated over a config the operator must fix.
    assert.equal(await readFile(path, "utf8"), raw);
  });

  test("keeps the token when the parsed config cannot be re-persisted", { skip: process.getuid?.() === 0 }, async () => {
    // Only a missing or unparseable config may mint a token. A valid config
    // whose write fails (read-only directory) must be kept
    // and returned — silently regenerating it would 401 every paired phone.
    const roDir = join(dir, "readonly");
    mkdirSync(roDir, { recursive: true });
    const path = join(roDir, "config.json");
    const token = "0123456789abcdef";
    await writeFile(path, JSON.stringify({ token, port: 8737, publicHost: "artemis.tail7dc568.ts.net" }));
    // Directory permissions do not stop a write to an existing file — the
    // file itself must lose its write bit for writeFile to fail (EACCES).
    chmodSync(path, 0o400);
    try {
      const config = await loadOrCreateConfig(path);
      assert.equal(config.token, token, "token must survive a failed re-persist");
      assert.equal(config.port, 8737);
      assert.deepEqual(config.exposure, { kind: "tailscale", publicUrl: "artemis.tail7dc568.ts.net" });
    } finally {
      chmodSync(path, 0o600);
    }
  });
});
