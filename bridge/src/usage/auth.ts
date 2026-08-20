import { readFile, rename, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

/**
 * Access to pi's auth store (~/.pi/agent/auth.json) and Claude Code's
 * credential file (~/.claude/.credentials.json).
 *
 * The bridge reads these to fetch provider usage, and writes back **only** the
 * OAuth fields it refreshes. Write-back is required, not optional: Anthropic and
 * OpenAI both rotate the refresh token on every refresh, so a refresh whose
 * result is discarded leaves the on-disk refresh token one rotation behind and
 * eventually breaks the owning CLI's own login.
 *
 * Every write goes through `updateJsonFile`, which re-reads immediately before
 * writing and merges into whatever is on disk, so fields the bridge does not
 * understand (idToken, scopes, subscriptionType, …) always survive.
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

/** Generic OAuth entry (xAI, and any future bearer-access providers). */
export interface OAuthAuth {
  type?: string;
  access: string;
  refresh?: string;
  expires?: number;
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

export function getOAuthAuth(store: AuthStore, providerKey: string): OAuthAuth | undefined {
  const entry = store[providerKey];
  if (!entry || typeof entry !== "object" || Array.isArray(entry)) return undefined;
  const auth = entry as Record<string, unknown>;
  if (typeof auth.access !== "string") return undefined;
  return {
    type: typeof auth.type === "string" ? auth.type : undefined,
    access: auth.access,
    refresh: typeof auth.refresh === "string" ? auth.refresh : undefined,
    expires: typeof auth.expires === "number" ? auth.expires : undefined,
  };
}

// ── Write-back ────────────────────────────────────────────────────────

/** Serializes this process's writes per path so two providers can't clobber each other. */
const writeQueues = new Map<string, Promise<unknown>>();

function recordOf(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

/**
 * Read-modify-write a JSON credential file atomically.
 *
 * `mutate` receives the file as it exists *now* — never a copy read earlier —
 * so a token the owning CLI rotated in the meantime is merged into, not
 * overwritten. The temp file is written into the same directory (rename is only
 * atomic within a filesystem) with the original's mode, so 0600 credential
 * files stay 0600.
 */
export async function updateJsonFile(
  path: string,
  mutate: (root: Record<string, unknown>) => Record<string, unknown> | undefined,
): Promise<void> {
  const previous = writeQueues.get(path) ?? Promise.resolve();
  const next = previous
    .catch(() => undefined)
    .then(async () => {
      let root: Record<string, unknown> = {};
      let mode = 0o600;
      try {
        root = recordOf(JSON.parse(await readFile(path, "utf8"))) ?? {};
        mode = (await stat(path)).mode & 0o777;
      } catch (error) {
        // A missing file is a fresh store; anything else (corrupt JSON,
        // permissions) must not be papered over by writing a truncated store.
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      }

      const updated = mutate(root);
      if (!updated) return;

      const temp = join(dirname(path), `.${Date.now()}-${process.pid}.tmp`);
      await writeFile(temp, `${JSON.stringify(updated, null, 2)}\n`, { mode });
      await rename(temp, path);
    });
  writeQueues.set(path, next);
  return next;
}

/** Merge refreshed OAuth fields into one provider entry of pi's auth.json. */
export async function persistOAuthAuth(
  path: string,
  providerKey: string,
  auth: Pick<OAuthAuth, "access" | "refresh" | "expires">,
): Promise<void> {
  await updateJsonFile(path, (root) => {
    const entry = recordOf(root[providerKey]) ?? { type: "oauth" };
    const result: OAuthAuth = {
      ...entry,
      access: auth.access,
    };
    if (auth.refresh) result.refresh = auth.refresh;
    if (auth.expires !== undefined) result.expires = auth.expires;
    root[providerKey] = result;
    return root;
  });
}

export function getApiKeyAuth(store: AuthStore, key: string): ApiKeyAuth | undefined {
  const entry = store[key];
  if (!entry || typeof entry !== "object" || Array.isArray(entry)) return undefined;
  const auth = entry as Record<string, unknown>;
  if (typeof auth.key !== "string") return undefined;
  return { type: typeof auth.type === "string" ? auth.type : undefined, key: auth.key };
}
