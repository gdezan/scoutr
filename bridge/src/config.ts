import { randomBytes } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

/**
 * Scoutr bridge configuration. Stored in ~/.config/scoutr/config.json.
 * The token authenticates every HTTP/WS request from the app.
 */

/** How the bridge's loopback listener is published to the phone. */
export type ExposureKind = "tailscale" | "cloudflare" | "custom";

export const EXPOSURE_KINDS: readonly ExposureKind[] = ["tailscale", "cloudflare", "custom"];

export interface ExposureConfig {
  /** Who is responsible for making the public URL reach this host. */
  kind: ExposureKind;
  /** Full public base URL. Optional only for tailscale auto-discovery. */
  publicUrl?: string;
}

export interface BridgeConfig {
  /** Directory containing config.json; uploads and other sibling state live here. */
  configDir: string;
  /** Bearer token shared with the app. */
  token: string;
  /** Loopback port the bridge listens on (an exposure provider fronts it with TLS). */
  port: number;
  /** Base URL of the self-hosted ntfy server (layer 5); empty = disabled. */
  ntfyUrl?: string;
  /** Random topic under which the bridge publishes blocked-agent events. */
  ntfyTopic?: string;
  /** Deployment exposure used to advertise a reachable URL at pairing time. */
  exposure: ExposureConfig;
}

/** A config file that parses but cannot be honoured; never recoverable by minting a new one. */
export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ConfigError";
  }
}

export function defaultConfigPath(): string {
  return join(process.env.XDG_CONFIG_HOME?.trim() || join(homedir(), ".config"), "scoutr", "config.json");
}

export function generateToken(): string {
  return `scoutr_${randomBytes(18).toString("base64url")}`;
}

/**
 * Normalizes the persisted exposure into the canonical shape.
 *
 * Configs written before exposure existed carry a bare `publicHost`; they are
 * Tailscale deployments, so that value becomes the Tailscale URL override.
 * An unknown kind is a configuration error: silently serving it as Tailscale
 * would shell out to a provider the operator did not choose.
 */
export function normalizeExposure(parsed: Record<string, unknown>, path: string): ExposureConfig {
  const raw = parsed.exposure;
  if (raw === undefined || raw === null) {
    const legacy = typeof parsed.publicHost === "string" && parsed.publicHost ? parsed.publicHost : undefined;
    return { kind: "tailscale", ...(legacy ? { publicUrl: legacy } : {}) };
  }
  if (typeof raw !== "object" || Array.isArray(raw)) {
    throw new ConfigError(`invalid "exposure" in ${path}: expected an object like {"kind":"tailscale"}`);
  }
  const exposure = raw as Record<string, unknown>;
  const kind = exposure.kind;
  if (typeof kind !== "string" || !EXPOSURE_KINDS.includes(kind as ExposureKind)) {
    throw new ConfigError(
      `unknown exposure kind ${JSON.stringify(kind)} in ${path}: expected one of ${EXPOSURE_KINDS.join(", ")}`,
    );
  }
  const publicUrl = typeof exposure.publicUrl === "string" && exposure.publicUrl ? exposure.publicUrl : undefined;
  return { kind: kind as ExposureKind, ...(publicUrl ? { publicUrl } : {}) };
}

export async function loadOrCreateConfig(path = defaultConfigPath()): Promise<BridgeConfig> {
  let config: BridgeConfig | null = null;
  let readOk = false;
  try {
    const raw = await readFile(path, "utf8");
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    if (typeof parsed.token !== "string" || parsed.token.length < 16 || typeof parsed.port !== "number") {
      throw new Error("invalid scoutr config (token or port missing)");
    }
    const exposure = normalizeExposure(parsed, path);
    readOk = true;
    config = {
      configDir: join(path, ".."),
      token: parsed.token,
      port: parsed.port,
      ntfyUrl: typeof parsed.ntfyUrl === "string" ? parsed.ntfyUrl : undefined,
      ntfyTopic: typeof parsed.ntfyTopic === "string" ? parsed.ntfyTopic : `scoutr_${randomBytes(12).toString("base64url")}`,
      exposure,
    };
  } catch (error) {
    // A misconfigured exposure is the operator's to fix; minting a fresh
    // config over it would rotate the token behind their back.
    if (error instanceof ConfigError) throw error;
    // Unreadable, missing, or invalid file: mint a fresh config below.
    config = null;
  }
  if (!config) {
    config = {
      configDir: join(path, ".."),
      token: generateToken(),
      port: 8737,
      ntfyTopic: `scoutr_${randomBytes(12).toString("base64url")}`,
      exposure: { kind: "tailscale" },
    };
    await mkdir(join(path, ".."), { recursive: true });
  }
  try {
    // Persist the canonical shape (and any other missing fields) so
    // subsequent runs are stable and legacy publicHost migrates once.
    await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
  } catch (error) {
    if (readOk) {
      // A parsed config that cannot be re-persisted is still valid; never
      // silently mint a new token over it (that would 401 every paired phone).
      console.error(`scoutr config could not be persisted: ${error instanceof Error ? error.message : String(error)}`);
    } else {
      // A fresh config that cannot be persisted is fatal: nothing to pair against.
      throw error;
    }
  }
  return config;
}
