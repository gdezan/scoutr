import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { BridgeConfig, ExposureConfig } from "../src/config.js";
import type { ResolvedExposure } from "../src/exposure.js";
import { buildScoutrContext, exposureHost, scoutrContextText } from "../src/agents/scoutr-context.js";
import { piLaunchCommand, piResumeCommand } from "../src/agents/pi/index.js";
import { claudeLaunchCommand, claudeResumeCommand } from "../src/agents/claude/index.js";
import { agyLaunchCommand } from "../src/agents/agy/index.js";

const TOKEN = "0123456789abcdef";

function configWith(exposure: ExposureConfig | undefined): BridgeConfig {
  // SAFETY: `exposure` may be partial or absent on purpose — buildScoutrContext
  // must degrade to the exposure-agnostic wording, never throw.
  return { configDir: "/tmp/scoutr-test", hostId: "host_test", token: TOKEN, port: 8737, exposure } as BridgeConfig;
}

function exposure(kind: ResolvedExposure["kind"], publicUrl: string, loopbackFallback = false): ResolvedExposure {
  return { kind, publicUrl, loopbackFallback };
}

/** Every variant must stay one shell-quotable line and carry no credential. */
function assertCommonInvariants(text: string): void {
  assert.ok(!text.includes("\n"), "context must be a single line");
  // SAFETY: /\p{Cc}/u matches every Unicode control character.
  assert.ok(!/\p{Cc}/u.test(text), "context must not contain control characters");
  assert.ok(!text.includes(TOKEN), "context must never carry the bridge token");
}

describe("scoutrContextText", () => {
  it("names the tailnet host and the tailscale serve mechanism", () => {
    const text = scoutrContextText(exposure("tailscale", "https://artemis.tail7dc568.ts.net"));
    assertCommonInvariants(text);
    assert.match(text, /supervised through Scoutr/);
    assert.match(text, /structured question tool/);
    assert.match(text, /artemis\.tail7dc568\.ts\.net/);
    assert.match(text, /tailscale serve --bg <port>/);
  });

  it("names a Cloudflare Tunnel without suggesting tailscale serve", () => {
    const text = scoutrContextText(exposure("cloudflare", "https://scoutr.example.com"));
    assertCommonInvariants(text);
    assert.match(text, /Cloudflare Tunnel fronting scoutr\.example\.com/);
    assert.ok(!text.includes("tailscale serve"));
  });

  it("keeps a custom exposure generic", () => {
    const text = scoutrContextText(exposure("custom", "http://scoutr.lan:8737"));
    assertCommonInvariants(text);
    assert.match(text, /configured exposure fronting scoutr\.lan:8737/);
  });

  it("keeps the remote warning but promises no mechanism when nothing resolved", () => {
    const text = scoutrContextText(null);
    assertCommonInvariants(text);
    assert.match(text, /remote-access path configured on this host/);
    assert.ok(!text.includes("tailscale serve"));
  });

  it("never advertises the loopback fallback as a reachable host", () => {
    const text = scoutrContextText(exposure("tailscale", "http://127.0.0.1:8737", true));
    assertCommonInvariants(text);
    assert.ok(!text.includes("127.0.0.1"));
    assert.match(text, /remote-access path configured on this host/);
  });

  it("never forbids without stating the positive target", () => {
    // The remote guidance leads with the publish instruction; the provisioning
    // guardrail rides after it (writing-for-agents: prompt the positive).
    const text = scoutrContextText(exposure("tailscale", "https://artemis.tail7dc568.ts.net"));
    assert.match(text, /Publish every link you share/);
    assert.match(text, /Do not provision new public infrastructure/);
  });
});

describe("exposureHost", () => {
  it("strips the scheme and flags the loopback fallback as unusable", () => {
    assert.equal(exposureHost(exposure("tailscale", "https://artemis.tail7dc568.ts.net/")), "artemis.tail7dc568.ts.net");
    assert.equal(exposureHost(exposure("tailscale", "http://127.0.0.1:8737", true)), null);
  });

  it("never leaks userinfo, path, or query into the injected text", () => {
    assert.equal(exposureHost(exposure("custom", "https://user:pass@host.example/?token=secret")), "host.example");
  });

  it("never emits a host carrying whitespace or control characters", () => {
    // WHATWG URL parsing strips/percent-encodes most of these; the guard in
    // exposureHost covers whatever survives parsing.
    const host = exposureHost(exposure("custom", "https://hos\nt.example"));
    assert.ok(!host || !/[\s\p{Cc}]/u.test(host));
  });

  it("degrades a malformed URL to no host", () => {
    assert.equal(exposureHost(exposure("custom", "not a url")), null);
  });
});

describe("buildScoutrContext", () => {
  it("resolves the tailnet host through injected discovery", async () => {
    const text = await buildScoutrContext(configWith({ kind: "tailscale" }), {
      discoverTailscaleUrl: async () => "artemis.tail7dc568.ts.net",
    });
    assertCommonInvariants(text);
    assert.match(text, /artemis\.tail7dc568\.ts\.net/);
  });

  it("degrades to the exposure-agnostic wording when resolution throws", async () => {
    // cloudflare without a configured URL makes resolveExposure throw.
    const text = await buildScoutrContext(configWith({ kind: "cloudflare" }), {
      discoverTailscaleUrl: async () => null,
    });
    assertCommonInvariants(text);
    assert.match(text, /remote-access path configured on this host/);
  });

  it("degrades gracefully when the exposure config is absent", async () => {
    const text = await buildScoutrContext(configWith(undefined));
    assertCommonInvariants(text);
    assert.match(text, /remote-access path configured on this host/);
  });

  it("memoizes the production path so a launch never shells out twice", async () => {
    const config = configWith({ kind: "tailscale", publicUrl: "https://configured.ts.net" });
    const first = await buildScoutrContext(config);
    const second = await buildScoutrContext(config);
    assert.equal(first, second);
    assertCommonInvariants(first);
  });

  it("rekeys the memo when SCOUTR_PUBLIC_HOST changes", async () => {
    const config = configWith({ kind: "custom" });
    try {
      process.env.SCOUTR_PUBLIC_HOST = "https://first.example.com";
      const first = await buildScoutrContext(config);
      process.env.SCOUTR_PUBLIC_HOST = "https://second.example.com";
      const second = await buildScoutrContext(config);
      assert.match(first, /first\.example\.com/);
      assert.match(second, /second\.example\.com/);
    } finally {
      delete process.env.SCOUTR_PUBLIC_HOST;
    }
  });
});


describe("backend context injection", () => {
  const CONTEXT = scoutrContextText(exposure("tailscale", "https://artemis.tail7dc568.ts.net"));

  it("pi appends the context on launch and resume", () => {
    assert.equal(
      piLaunchCommand({ model: "m", scoutrContext: CONTEXT }),
      `pi --model 'm' --append-system-prompt '${CONTEXT}'`,
    );
    assert.equal(
      piResumeCommand("/s/x.jsonl", "resume", CONTEXT),
      `pi --session '/s/x.jsonl' --append-system-prompt '${CONTEXT}'`,
    );
    assert.equal(piResumeCommand("/s/x.jsonl", "fork", CONTEXT), `pi --fork '/s/x.jsonl' --append-system-prompt '${CONTEXT}'`);
  });

  it("pi omits the flag when no context is given", () => {
    assert.equal(piLaunchCommand({ model: "m" }), "pi --model 'm'");
    assert.equal(piResumeCommand("/s/x.jsonl", "resume"), "pi --session '/s/x.jsonl'");
  });

  it("claude appends the context on launch and resume", () => {
    // Claude's system-prompt flags are per-invocation, so resume must re-carry them.
    assert.equal(claudeLaunchCommand({ scoutrContext: CONTEXT }), `claude --append-system-prompt '${CONTEXT}'`);
    assert.equal(
      claudeResumeCommand("/p/uuid.jsonl", "resume", CONTEXT),
      `claude --resume 'uuid' --append-system-prompt '${CONTEXT}'`,
    );
  });

  it("claude omits the flag when no context is given", () => {
    assert.equal(claudeLaunchCommand({}), "claude");
    assert.equal(claudeResumeCommand("/p/uuid.jsonl", "resume"), "claude --resume 'uuid'");
  });

  it("agy has no system-prompt append and ignores the context", () => {
    assert.equal(agyLaunchCommand({ scoutrContext: CONTEXT }), "agy");
    assert.equal(agyLaunchCommand({ model: "m", scoutrContext: CONTEXT }), "agy --model 'm'");
  });
});
