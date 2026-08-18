import type { ResolvedExposure } from "./exposure.js";

/**
 * QR pairing payload — the single object a phone scans to connect.
 * Kept compact (no whitespace) so the QR stays small and scannable.
 *
 * v1 is the Tailscale contract already in the field and never changes shape.
 * v2 adds an explicit exposure kind for the providers that did not exist when
 * v1 shipped; it carries no edge-auth credentials (Cloudflare Access was
 * explicitly rejected), so the Scoutr bearer token stays the only secret.
 */
export interface PairingPayloadV1 {
  /** Payload schema version; the app rejects unknown versions. */
  v: 1;
  /** Bridge base URL, e.g. https://artemis.tail7dc568.ts.net */
  host: string;
  /** Pairing token from the bridge config. */
  token: string;
  /** ntfy discovery, present when push is configured. */
  ntfy?: { url: string; topic: string };
}

/** Exposure kinds that pair over v2; `tailscale` stays on v1 by contract. */
export type PairingExposureKind = "cloudflare" | "custom";

export interface PairingPayloadV2 {
  v: 2;
  host: string;
  token: string;
  /** Which provider fronts `host`. The app stores it; the protocol ignores it. */
  exposure: { kind: PairingExposureKind };
  ntfy?: { url: string; topic: string };
}

export type PairingPayload = PairingPayloadV1 | PairingPayloadV2;

export interface PairingConfig {
  /**
   * Resolved exposure, which supplies both the host and the payload version.
   * Taking the whole object (rather than a host plus a version flag) is what
   * makes an invalid provider/version pair unrepresentable at the call site.
   */
  exposure: ResolvedExposure;
  token: string;
  ntfyUrl?: string;
  ntfyTopic?: string;
}

/**
 * The payload always carries a full URL. The tailscale DNS name (and manual
 * config) can be scheme-less, so prepend https:// when nothing else is there.
 */
export function withScheme(host: string): string {
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(host) ? host : `https://${host}`;
}

function ntfyOf(config: PairingConfig): { ntfy: { url: string; topic: string } } | undefined {
  // All-or-nothing: half a discovery block would make the app poll nowhere.
  if (!config.ntfyUrl || !config.ntfyTopic) return undefined;
  return { ntfy: { url: config.ntfyUrl, topic: config.ntfyTopic } };
}

export function buildPairingPayload(config: PairingConfig): string {
  const host = withScheme(config.exposure.publicUrl);
  const ntfy = ntfyOf(config);
  const payload: PairingPayload =
    config.exposure.kind === "tailscale"
      ? { v: 1, host, token: config.token, ...ntfy }
      : { v: 2, host, token: config.token, exposure: { kind: config.exposure.kind }, ...ntfy };
  return JSON.stringify(payload);
}

function parseNtfy(value: unknown): { ntfy: { url: string; topic: string } } | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const n = value as Record<string, unknown>;
  if (typeof n.url !== "string" || typeof n.topic !== "string" || !n.url || !n.topic) return undefined;
  return { ntfy: { url: n.url, topic: n.topic } };
}

function parseExposureKind(value: unknown): PairingExposureKind | null {
  if (typeof value !== "object" || value === null) return null;
  const kind = (value as Record<string, unknown>).kind;
  return kind === "cloudflare" || kind === "custom" ? kind : null;
}

/**
 * Decodes a scanned payload. Returns null for anything that is not a v1 or v2
 * payload with a host and a token — and, for v2, a known exposure kind.
 */
export function parsePairingPayload(raw: string): PairingPayload | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null) return null;
    const p = parsed as Record<string, unknown>;
    if (p.v !== 1 && p.v !== 2) return null;
    if (typeof p.host !== "string" || p.host.length === 0) return null;
    if (typeof p.token !== "string" || p.token.length === 0) return null;
    const ntfy = parseNtfy(p.ntfy);
    if (p.v === 1) return { v: 1, host: p.host, token: p.token, ...ntfy };
    const kind = parseExposureKind(p.exposure);
    if (!kind) return null;
    return { v: 2, host: p.host, token: p.token, exposure: { kind }, ...ntfy };
  } catch {
    return null;
  }
}
