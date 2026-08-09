/**
 * QR pairing payload — the single object a phone scans to connect.
 * Kept compact (no whitespace) so the QR stays small and scannable.
 */
export interface PairingPayload {
  /** Payload schema version; the app rejects unknown versions. */
  v: 1;
  /** Bridge base URL, e.g. https://artemis.tail7dc568.ts.net */
  host: string;
  /** Pairing token from the bridge config. */
  token: string;
  /** ntfy discovery, present when push is configured. */
  ntfy?: { url: string; topic: string };
}

export interface PairingConfig {
  host: string;
  token: string;
  ntfyUrl?: string;
  ntfyTopic?: string;
}

export function buildPairingPayload(config: PairingConfig): string {
  const payload: PairingPayload = { v: 1, host: config.host, token: config.token };
  if (config.ntfyUrl && config.ntfyTopic) {
    payload.ntfy = { url: config.ntfyUrl, topic: config.ntfyTopic };
  }
  return JSON.stringify(payload);
}

/**
 * Decodes a scanned payload. Returns null for anything that is not a
 * v1 payload with a host and a token.
 */
export function parsePairingPayload(raw: string): PairingPayload | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null) return null;
    const p = parsed as Record<string, unknown>;
    if (p.v !== 1) return null;
    if (typeof p.host !== "string" || p.host.length === 0) return null;
    if (typeof p.token !== "string" || p.token.length === 0) return null;
    let ntfy: PairingPayload["ntfy"];
    if (typeof p.ntfy === "object" && p.ntfy !== null) {
      const n = p.ntfy as Record<string, unknown>;
      if (typeof n.url === "string" && typeof n.topic === "string" && n.url && n.topic) {
        ntfy = { url: n.url, topic: n.topic };
      }
    }
    return { v: 1, host: p.host, token: p.token, ...(ntfy ? { ntfy } : {}) };
  } catch {
    return null;
  }
}
