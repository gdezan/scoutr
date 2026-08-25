import { test, describe } from "node:test";
import assert from "node:assert/strict";
import type { BridgeConfig, ExposureConfig } from "../src/config.js";
import { ExposureError, resolveExposure } from "../src/exposure.js";

function configWith(exposure: ExposureConfig): BridgeConfig {
  return { configDir: "/tmp/scoutr-test", hostId: "host_test", token: "0123456789abcdef", port: 8737, exposure };
}

/** Records whether Tailscale discovery ran at all — the core provider invariant. */
function discovery(result: string | null) {
  const spy = {
    calls: 0,
    discoverTailscaleUrl: async (): Promise<string | null> => {
      spy.calls += 1;
      return result;
    },
  };
  return spy;
}

describe("resolveExposure", () => {
  test("discovers the tailnet name when tailscale has no configured URL", async () => {
    const deps = discovery("artemis.tail7dc568.ts.net");
    const resolved = await resolveExposure(configWith({ kind: "tailscale" }), deps);
    assert.deepEqual(resolved, { kind: "tailscale", publicUrl: "https://artemis.tail7dc568.ts.net" });
    assert.equal(deps.calls, 1);
  });

  test("prefers the configured URL over tailscale discovery", async () => {
    const deps = discovery("discovered.ts.net");
    const resolved = await resolveExposure(
      configWith({ kind: "tailscale", publicUrl: "https://configured.ts.net" }),
      deps,
    );
    assert.equal(resolved.publicUrl, "https://configured.ts.net");
    assert.equal(deps.calls, 0);
  });

  test("falls back to loopback and flags it when discovery finds nothing", async () => {
    const deps = discovery(null);
    const resolved = await resolveExposure(configWith({ kind: "tailscale" }), deps);
    assert.deepEqual(resolved, { kind: "tailscale", publicUrl: "http://127.0.0.1:8737", loopbackFallback: true });
  });

  test("honors SCOUTR_PUBLIC_HOST as the public URL override", async () => {
    const previous = process.env.SCOUTR_PUBLIC_HOST;
    process.env.SCOUTR_PUBLIC_HOST = "https://env.example.com";
    const deps = discovery("discovered.ts.net");
    try {
      const resolved = await resolveExposure(configWith({ kind: "tailscale" }), deps);
      assert.equal(resolved.publicUrl, "https://env.example.com");
      assert.equal(deps.calls, 0);
    } finally {
      if (previous === undefined) delete process.env.SCOUTR_PUBLIC_HOST;
      else process.env.SCOUTR_PUBLIC_HOST = previous;
    }
  });

  for (const kind of ["cloudflare", "custom"] as const) {
    test(`never invokes tailscale discovery for ${kind}`, async () => {
      const deps = discovery("artemis.tail7dc568.ts.net");
      const resolved = await resolveExposure(
        configWith({ kind, publicUrl: "https://scoutr.example.com" }),
        deps,
      );
      assert.deepEqual(resolved, { kind, publicUrl: "https://scoutr.example.com" });
      assert.equal(deps.calls, 0, "only tailscale exposure may run discovery");
    });

    test(`fails with actionable guidance when ${kind} has no public URL`, async () => {
      const deps = discovery("artemis.tail7dc568.ts.net");
      await assert.rejects(() => resolveExposure(configWith({ kind }), deps), (error) => {
        assert.ok(error instanceof ExposureError);
        assert.match(error.message, /exposure\.publicUrl/);
        return true;
      });
      assert.equal(deps.calls, 0);
    });
  }

  test("rejects an http cloudflare URL rather than rewriting its scheme", async () => {
    await assert.rejects(
      () => resolveExposure(configWith({ kind: "cloudflare", publicUrl: "http://scoutr.example.com" }), discovery(null)),
      (error) => {
        assert.ok(error instanceof ExposureError);
        assert.match(error.message, /https:\/\//);
        return true;
      },
    );
  });

  test("treats a scheme-less cloudflare host as https", async () => {
    const resolved = await resolveExposure(
      configWith({ kind: "cloudflare", publicUrl: "scoutr.example.com" }),
      discovery(null),
    );
    assert.equal(resolved.publicUrl, "https://scoutr.example.com");
  });

  test("keeps an explicit http URL for custom dev exposure", async () => {
    const resolved = await resolveExposure(
      configWith({ kind: "custom", publicUrl: "http://192.168.1.10:8737" }),
      discovery(null),
    );
    assert.deepEqual(resolved, { kind: "custom", publicUrl: "http://192.168.1.10:8737" });
  });

  test("rejects a non-http custom URL", async () => {
    await assert.rejects(
      () => resolveExposure(configWith({ kind: "custom", publicUrl: "ftp://scoutr.example.com" }), discovery(null)),
      ExposureError,
    );
  });
});
