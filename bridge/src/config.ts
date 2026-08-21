import * as v from "valibot";
import { randomBytes } from "node:crypto";
import { access, mkdir, readFile, writeFile } from "node:fs/promises";
import { constants } from "node:fs";
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
  /** Path to the FCM service-account JSON. Absent = push disabled. */
  fcmServiceAccountPath?: string;
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

const bridgeConfigSchema = v.looseObject({
  token: v.string(),
  port: v.number(),
  fcmServiceAccountPath: v.optional(v.string()),
  exposure: v.optional(v.unknown()),
  publicHost: v.optional(v.string()),
});
const exposureSchema = v.looseObject({
  kind: v.string(),
  publicUrl: v.optional(v.string()),
});
type BridgeConfigParsed = v.InferOutput<typeof bridgeConfigSchema>;

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
 * An unknown kind is a configuration error: silently serving it as something
 * else would shell out to a provider the operator did not choose.
 */
export function normalizeExposure(parsed: BridgeConfigParsed, path: string): ExposureConfig {
  const raw = parsed.exposure;
  if (raw === undefined || raw === null) {
    const result: ExposureConfig = { kind: "tailscale" };
    if (parsed.publicHost) result.publicUrl = parsed.publicHost;
    return result;
  }
  const exposureParsed = v.safeParse(exposureSchema, raw);
  if (!exposureParsed.success) {
    throw new ConfigError(`invalid "exposure" in ${path}: expected an object like {"kind":"tailscale"}`);
  }
  const exposure = exposureParsed.output;
  let matched: ExposureKind | undefined;
  for (const kind of EXPOSURE_KINDS) {
    if (kind === exposure.kind) {
      matched = kind;
      break;
    }
  }
  if (!matched) {
    throw new ConfigError(
      `unknown exposure kind ${JSON.stringify(exposure.kind)} in ${path}: expected one of ${EXPOSURE_KINDS.join(", ")}`,
    );
  }
  const result: ExposureConfig = { kind: matched };
  if (exposure.publicUrl) result.publicUrl = exposure.publicUrl;
  return result;
}

/** Filename of the conventional FCM service-account key next to config.json. */
export const FCM_SERVICE_ACCOUNT_FILENAME = "fcm-service-account.json";

async function resolveFcmServiceAccountPath(
  explicit: string | undefined,
  configDir: string,
): Promise<string | undefined> {
  if (explicit) return explicit;
  const conventional = join(configDir, FCM_SERVICE_ACCOUNT_FILENAME);
  try {
    await access(conventional, constants.R_OK);
    return conventional;
  } catch {
    return undefined;
  }
}

export async function loadOrCreateConfig(path = defaultConfigPath()): Promise<BridgeConfig> {
  let config: BridgeConfig | null = null;
  let readOk = false;
  try {
    const parsed = v.safeParse(bridgeConfigSchema, JSON.parse(await readFile(path, "utf8")));
    if (!parsed.success || parsed.output.token.length < 16) {
      throw new Error("invalid scoutr config (token or port missing)");
    }
    const exposure = normalizeExposure(parsed.output, path);
    readOk = true;
    const configDir = join(path, "..");
    config = {
      configDir,
      token: parsed.output.token,
      port: parsed.output.port,
      fcmServiceAccountPath: await resolveFcmServiceAccountPath(parsed.output.fcmServiceAccountPath, configDir),
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
      exposure: { kind: "tailscale" },
    };
    await mkdir(join(path, ".."), { recursive: true });
  }
  if (!config.fcmServiceAccountPath) {
    config.fcmServiceAccountPath = await resolveFcmServiceAccountPath(undefined, config.configDir);
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
