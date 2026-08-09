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
}

export function defaultConfigPath(): string {
  return join(process.env.XDG_CONFIG_HOME?.trim() || join(homedir(), ".config"), "cockpit", "config.json");
}

export function generateToken(): string {
  return `cockpit_${randomBytes(18).toString("base64url")}`;
}

export async function loadOrCreateConfig(path = defaultConfigPath()): Promise<BridgeConfig> {
  try {
    const raw = await readFile(path, "utf8");
    const parsed = JSON.parse(raw) as Partial<BridgeConfig>;
    if (typeof parsed.token !== "string" || parsed.token.length < 16 || typeof parsed.port !== "number") {
      throw new Error("invalid cockpit config (token or port missing)");
    }
    const config: BridgeConfig = {
      token: parsed.token,
      port: parsed.port,
      ntfyUrl: typeof parsed.ntfyUrl === "string" ? parsed.ntfyUrl : undefined,
      ntfyTopic: typeof parsed.ntfyTopic === "string" ? parsed.ntfyTopic : `cockpit_${randomBytes(12).toString("base64url")}`,
    };
    // Persist the topic (and any other missing fields) so subsequent runs are stable.
    await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
    return config;
  } catch {
    const config: BridgeConfig = {
      token: generateToken(),
      port: 8737,
      ntfyTopic: `cockpit_${randomBytes(12).toString("base64url")}`,
    };
    await mkdir(join(path, ".."), { recursive: true });
    await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
    return config;
  }
}
