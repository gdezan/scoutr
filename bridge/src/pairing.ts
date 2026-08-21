import type { ResolvedExposure } from "./exposure.js";
import * as v from "valibot";

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
}

/** Exposure kinds that pair over v2; `tailscale` stays on v1 by contract. */
export type PairingExposureKind = "cloudflare" | "custom";

export interface PairingPayloadV2 {
  v: 2;
  host: string;
  token: string;
  /** Which provider fronts `host`. The app stores it; the protocol ignores it. */
  exposure: { kind: PairingExposureKind };
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
}

/**
 * The payload always carries a full URL. The tailscale DNS name (and manual
 * config) can be scheme-less, so prepend https:// when nothing else is there.
 */
export function withScheme(host: string): string {
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(host) ? host : `https://${host}`;
}

export function buildPairingPayload(config: PairingConfig): string {
  const host = withScheme(config.exposure.publicUrl);
  const payload: PairingPayload =
    config.exposure.kind === "tailscale"
      ? { v: 1, host, token: config.token }
      : { v: 2, host, token: config.token, exposure: { kind: config.exposure.kind } };
  return JSON.stringify(payload);
}

const pairingPayloadSchema = v.variant("v", [
  v.looseObject({
    v: v.literal(1),
    host: v.pipe(v.string(), v.minLength(1)),
    token: v.pipe(v.string(), v.minLength(1)),
  }),
  v.looseObject({
    v: v.literal(2),
    host: v.pipe(v.string(), v.minLength(1)),
    token: v.pipe(v.string(), v.minLength(1)),
    exposure: v.object({ kind: v.picklist(["cloudflare", "custom"]) }),
  }),
]);

/**
 * Decodes a scanned payload. Returns null for anything that is not a v1 or v2
 * payload with a host and a token — and, for v2, a known exposure kind.
 */
export function parsePairingPayload(raw: string): PairingPayload | null {
  try {
    const parsed = v.safeParse(pairingPayloadSchema, JSON.parse(raw));
    if (!parsed.success) return null;
    const payload = parsed.output;
    return payload.v === 1
      ? { v: 1, host: payload.host, token: payload.token }
      : { v: 2, host: payload.host, token: payload.token, exposure: { kind: payload.exposure.kind } };
  } catch {
    return null;
  }
}
