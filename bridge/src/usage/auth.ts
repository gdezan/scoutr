import { readFile, rename, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import * as v from "valibot";

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

/** Arbitrary JSON object, as read from or written to a credential file. */
const jsonObjectSchema = v.record(v.string(), v.unknown());
type JsonObject = v.InferOutput<typeof jsonObjectSchema>;

export type AuthStore = JsonObject;

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

const codexAuthSchema = v.looseObject({
  type: v.optional(v.string()),
  access: v.string(),
  refresh: v.optional(v.string()),
  expires: v.optional(v.number()),
  accountId: v.optional(v.string()),
});

const oauthAuthSchema = v.looseObject({
  type: v.optional(v.string()),
  access: v.string(),
  refresh: v.optional(v.string()),
  expires: v.optional(v.number()),
});

const apiKeyAuthSchema = v.looseObject({
  type: v.optional(v.string()),
  key: v.string(),
});

export function defaultAuthPath(): string {
  const agentDir = process.env.PI_CODING_AGENT_DIR?.trim() || join(homedir(), ".pi", "agent");
  return join(agentDir, "auth.json");
}

export async function readAuthStore(path = defaultAuthPath()): Promise<AuthStore> {
  const parsed = v.safeParse(jsonObjectSchema, JSON.parse(await readFile(path, "utf8")));
  return parsed.success ? parsed.output : {};
}

export function getCodexAuth(store: AuthStore): CodexAuth | undefined {
  const parsed = v.safeParse(codexAuthSchema, store["openai-codex"]);
  if (!parsed.success) return undefined;
  const out = parsed.output;
  return { type: out.type, access: out.access, refresh: out.refresh, expires: out.expires, accountId: out.accountId };
}

export function getOAuthAuth(store: AuthStore, providerKey: string): OAuthAuth | undefined {
  const parsed = v.safeParse(oauthAuthSchema, store[providerKey]);
  if (!parsed.success) return undefined;
  const out = parsed.output;
  return { type: out.type, access: out.access, refresh: out.refresh, expires: out.expires };
}

// ── Write-back ────────────────────────────────────────────────────────

/** Serializes this process's writes per path so two providers can't clobber each other. */
const writeQueues = new Map<string, Promise<unknown>>();

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
  mutate: (root: JsonObject) => JsonObject | undefined,
): Promise<void> {
  const previous = writeQueues.get(path) ?? Promise.resolve();
  const next = previous
    .catch(() => undefined)
    .then(async () => {
      let root: JsonObject = {};
      let mode = 0o600;
      try {
        const parsed = v.safeParse(jsonObjectSchema, JSON.parse(await readFile(path, "utf8")));
        if (parsed.success) root = parsed.output;
        mode = (await stat(path)).mode & 0o777;
      } catch (error) {
        // A missing file is a fresh store; anything else (corrupt JSON,
        // permissions) must not be papered over by writing a truncated store.
        // SAFETY: this catch is only reached by a thrown NodeJS error, which always
        // carries a string `code`; reading it to split ENOENT from real failures is sound.
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
    const parsed = v.safeParse(oauthAuthSchema, root[providerKey]);
    const entry: JsonObject = parsed.success ? parsed.output : { type: "oauth" };
    entry.access = auth.access;
    if (auth.refresh) entry.refresh = auth.refresh;
    if (auth.expires !== undefined) entry.expires = auth.expires;
    root[providerKey] = entry;
    return root;
  });
}

export function getApiKeyAuth(store: AuthStore, key: string): ApiKeyAuth | undefined {
  const parsed = v.safeParse(apiKeyAuthSchema, store[key]);
  if (!parsed.success) return undefined;
  const out = parsed.output;
  return { type: out.type, key: out.key };
}
