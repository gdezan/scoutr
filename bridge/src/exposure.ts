/**
 * Exposure resolution — the one place that answers "what public base URL
 * should pairing advertise?".
 *
 * The bridge protocol never branches on the provider: only pairing/setup
 * needs to know who fronts the loopback listener. Provider discovery lives
 * here (not in the CLI) so it can be stubbed in tests, and so that a
 * Cloudflare or custom deployment never executes the Tailscale binary.
 */
import { execFile } from "node:child_process";
import type { BridgeConfig, ExposureKind } from "./config.js";
import { withScheme } from "./pairing.js";

export interface ResolvedExposure {
  kind: ExposureKind;
  publicUrl: string;
  /**
   * True when Tailscale discovery found nothing and `publicUrl` is the local
   * loopback fallback — the QR is not remotely usable.
   */
  loopbackFallback?: boolean;
}

export interface ExposureDeps {
  /** Returns the public base URL for the tailnet, or null when undiscoverable. */
  discoverTailscaleUrl?: () => Promise<string | null>;
}

/** A resolvable-configuration failure: pairing cannot advertise a reachable host. */
export class ExposureError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ExposureError";
  }
}

/** Reads the tailnet MagicDNS name from the local Tailscale daemon. */
export function discoverTailscaleUrl(): Promise<string | null> {
  return new Promise((resolve) => {
    execFile("tailscale", ["status", "--json"], { timeout: 5000 }, (err, stdout) => {
      if (err) return resolve(null);
      try {
        const dns = (JSON.parse(stdout) as { Self?: { DNSName?: string } }).Self?.DNSName;
        resolve(dns ? dns.replace(/\.$/, "") || null : null);
      } catch {
        resolve(null);
      }
    });
  });
}

function configuredUrl(config: BridgeConfig): string | undefined {
  // Config wins over the environment, as it did before exposure existed.
  return config.exposure.publicUrl?.trim() || process.env.SCOUTR_PUBLIC_HOST?.trim() || undefined;
}

function fixHint(kind: ExposureKind, detail: string): string {
  return (
    `${detail}\n` +
    `set exposure.publicUrl in ~/.config/scoutr/config.json, e.g. ` +
    `{"exposure":{"kind":"${kind}","publicUrl":"https://scoutr.example.com"}} (or export SCOUTR_PUBLIC_HOST).`
  );
}

/**
 * Resolves the public base URL for pairing. Only `tailscale` may run discovery;
 * `cloudflare` and `custom` require an explicit URL and never shell out.
 */
export async function resolveExposure(config: BridgeConfig, deps: ExposureDeps = {}): Promise<ResolvedExposure> {
  const { kind } = config.exposure;
  const configured = configuredUrl(config);

  if (kind === "tailscale") {
    const discover = deps.discoverTailscaleUrl ?? discoverTailscaleUrl;
    const host = configured ?? (await discover()) ?? "";
    if (!host) {
      return { kind, publicUrl: `http://127.0.0.1:${config.port}`, loopbackFallback: true };
    }
    return { kind, publicUrl: withScheme(host) };
  }

  if (!configured) {
    throw new ExposureError(fixHint(kind, `exposure "${kind}" has no public URL, so pairing cannot advertise a reachable bridge.`));
  }
  // A scheme-less host is an https host; an explicit scheme is never rewritten,
  // because that would hand the phone a different security contract silently.
  const url = withScheme(configured);
  if (kind === "cloudflare" && !url.startsWith("https://")) {
    throw new ExposureError(
      fixHint(
        kind,
        `exposure "cloudflare" requires an https:// public URL (got ${url}); TLS terminates at Cloudflare.`,
      ),
    );
  }
  if (kind === "custom" && !/^https?:\/\//.test(url)) {
    throw new ExposureError(fixHint(kind, `exposure "custom" requires an http:// or https:// public URL (got ${url}).`));
  }
  return { kind, publicUrl: url };
}
