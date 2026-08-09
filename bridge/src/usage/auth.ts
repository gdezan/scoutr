import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

/**
 * Read-only access to pi's auth store (~/.pi/agent/auth.json).
 * The bridge never writes to this file.
 */

export interface CodexAuth {
  type?: string;
  access: string;
  refresh?: string;
  expires?: number;
  accountId?: string;
}

export interface ApiKeyAuth {
  type?: string;
  key: string;
}

export type AuthStore = Record<string, unknown>;

export function defaultAuthPath(): string {
  const agentDir = process.env.PI_CODING_AGENT_DIR?.trim() || join(homedir(), ".pi", "agent");
  return join(agentDir, "auth.json");
}

export async function readAuthStore(path = defaultAuthPath()): Promise<AuthStore> {
  return JSON.parse(await readFile(path, "utf8")) as AuthStore;
}

export function getCodexAuth(store: AuthStore): CodexAuth | undefined {
  const entry = store["openai-codex"];
  if (!entry || typeof entry !== "object" || Array.isArray(entry)) return undefined;
  const auth = entry as Record<string, unknown>;
  if (typeof auth.access !== "string") return undefined;
  return {
    type: typeof auth.type === "string" ? auth.type : undefined,
    access: auth.access,
    refresh: typeof auth.refresh === "string" ? auth.refresh : undefined,
    expires: typeof auth.expires === "number" ? auth.expires : undefined,
    accountId: typeof auth.accountId === "string" ? auth.accountId : undefined,
  };
}

export function getApiKeyAuth(store: AuthStore, key: string): ApiKeyAuth | undefined {
  const entry = store[key];
  if (!entry || typeof entry !== "object" || Array.isArray(entry)) return undefined;
  const auth = entry as Record<string, unknown>;
  if (typeof auth.key !== "string") return undefined;
  return { type: typeof auth.type === "string" ? auth.type : undefined, key: auth.key };
}
