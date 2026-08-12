import { randomBytes } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

/**
 * Cockpit bridge configuration. Stored in ~/.config/cockpit/config.json.
 * The token authenticates every HTTP/WS request from the app.
 */

export interface BridgeConfig {
  /** Bearer token shared with the app. */
  token: string;
  /** Loopback port the bridge listens on (tailscale serve fronts it with TLS). */
  port: number;
  /** Base URL of the self-hosted ntfy server (layer 5); empty = disabled. */
  ntfyUrl?: string;
  /** Random topic under which the bridge publishes blocked-agent events. */
  ntfyTopic?: string;
  /** Public base URL the app reaches the bridge at (e.g. https://host.ts.net); used for QR pairing. */
  publicHost?: string;
}

export function defaultConfigPath(): string {
  return join(process.env.XDG_CONFIG_HOME?.trim() || join(homedir(), ".config"), "cockpit", "config.json");
}

export function generateToken(): string {
  return `cockpit_${randomBytes(18).toString("base64url")}`;
}

export async function loadOrCreateConfig(path = defaultConfigPath()): Promise<BridgeConfig> {
  let config: BridgeConfig | null = null;
  let readOk = false;
  try {
    const raw = await readFile(path, "utf8");
    const parsed = JSON.parse(raw) as Partial<BridgeConfig>;
    if (typeof parsed.token !== "string" || parsed.token.length < 16 || typeof parsed.port !== "number") {
      throw new Error("invalid cockpit config (token or port missing)");
    }
    readOk = true;
    config = {
      token: parsed.token,
      port: parsed.port,
      ntfyUrl: typeof parsed.ntfyUrl === "string" ? parsed.ntfyUrl : undefined,
      ntfyTopic: typeof parsed.ntfyTopic === "string" ? parsed.ntfyTopic : `cockpit_${randomBytes(12).toString("base64url")}`,
      publicHost: typeof parsed.publicHost === "string" ? parsed.publicHost : undefined,
    };
  } catch {
    // Unreadable, missing, or invalid file: mint a fresh config below.
    config = null;
  }
  if (!config) {
    config = {
      token: generateToken(),
      port: 8737,
      ntfyTopic: `cockpit_${randomBytes(12).toString("base64url")}`,
    };
    await mkdir(join(path, ".."), { recursive: true });
  }
  try {
    // Persist the topic (and any other missing fields) so subsequent runs are stable.
    await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
  } catch (error) {
    if (readOk) {
      // A parsed config that cannot be re-persisted is still valid; never
      // silently mint a new token over it (that would 401 every paired phone).
      console.error(`cockpit config could not be persisted: ${error instanceof Error ? error.message : String(error)}`);
    } else {
      // A fresh config that cannot be persisted is fatal: nothing to pair against.
      throw error;
    }
  }
  return config;
}
