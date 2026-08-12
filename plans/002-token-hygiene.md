# Plan 002: Stop leaking and silently rotating the pairing token

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 1ece5c9..HEAD -- bridge/src/config.ts bridge/src/cli.ts bridge/src/routes/dispatcher.ts bridge/src/server.ts android/app/src/main/AndroidManifest.xml`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `1ece5c9`, 2026-08-12

## Why this matters

One bearer token is the entire auth layer of the bridge API (behind tailnet
TLS). Today that token: (a) is printed to stderr on every daemon start, and
the documented deployment is a systemd user unit — so the credential persists
in journald and every `journalctl` paste; (b) is silently **regenerated**
whenever the config file write fails (full disk, EACCES), instantly 401-ing
every paired phone with no diagnostic; (c) is accepted as a `?token=` query
parameter on **every** HTTP route although only the WebSocket upgrade needs
that form — putting the credential into any URL-logging layer; and (d) is
included in Android cloud backups (`allowBackup="true"`, plaintext
SharedPreferences). Four small fixes, one credential.

**Never write the actual token value into any commit, test, log, or this
plan's outputs.** `docs/AUDIT.md` claims the token is "never written to
logs"; this plan makes that claim true.

## Current state

- `bridge/src/config.ts` — `loadOrCreateConfig`: one `try` spans read, parse,
  **and** the persist-write; the `catch` unconditionally mints a new token:

```ts
export async function loadOrCreateConfig(path = defaultConfigPath()): Promise<BridgeConfig> {
  try {
    const raw = await readFile(path, "utf8");
    const parsed = JSON.parse(raw) as Partial<BridgeConfig>;
    if (typeof parsed.token !== "string" || parsed.token.length < 16 || typeof parsed.port !== "number") {
      throw new Error("invalid cockpit config (token or port missing)");
    }
    const config: BridgeConfig = { /* token, port, ntfyUrl, ntfyTopic (minted if missing), publicHost */ };
    // Persist the topic (and any other missing fields) so subsequent runs are stable.
    await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
    return config;
  } catch {
    const config: BridgeConfig = { token: generateToken(), port: 8737, ntfyTopic: `cockpit_...` };
    await mkdir(join(path, ".."), { recursive: true });
    await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
    return config;
  }
}
```

- `bridge/src/cli.ts:126` — the `serve` startup banner prints the token:

```ts
console.error(`cockpit bridge listening on ${server.url}`);
console.error(`token: ${config.token}`);
```

  The `pair` command (same file, `case "pair"`) already prints pairing
  material on demand — that is the sanctioned way to get the token.

- `bridge/src/routes/dispatcher.ts:81-89` — query-token fallback on every route:

```ts
export function isAuthorized(request: DispatchRequest, token: string): boolean {
  const header = request.authorization;
  if (typeof header === "string" && header.startsWith("Bearer ")) {
    return timingSafeEqual(header.slice(7), token);
  }
  const queryToken = request.search.get("token");
  if (queryToken) return timingSafeEqual(queryToken, token);
  return false;
}
```

- `bridge/src/server.ts:109-128` — the WS upgrade calls the same
  `isAuthorized`; the Android client's WS connect is the **only** caller that
  uses the query form (`android/.../net/BridgeClient.kt:48-49` documents why:
  "the WS upgrade uses a query-param token"). All HTTP calls use the
  `Authorization: Bearer` header (`BridgeClient.kt:113,172`).

- `android/app/src/main/AndroidManifest.xml:12` — `android:allowBackup="true"`
  with no `dataExtractionRules`/`fullBackupContent`; the token lives in
  plaintext SharedPreferences file `cockpit_connection`
  (`android/.../data/ConnectionStore.kt`, keys `host`/`token`/`ntfyUrl`/`ntfyTopic`).

- Existing config tests: `bridge/test/config.test.ts`. Existing dispatcher
  tests: `bridge/test/routes.test.ts` and offline HTTP tests in
  `bridge/test/server.test.ts`.

## Commands you will need

| Purpose   | Command | Expected on success |
|-----------|---------|---------------------|
| Bridge typecheck | `cd bridge && npm run typecheck` | exit 0 |
| Bridge tests | `cd bridge && npm test` | all pass |
| Android unit tests | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL |
| Android build | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` | BUILD SUCCESSFUL |

## Scope

**In scope**:
- `bridge/src/config.ts`
- `bridge/src/cli.ts` (the `serve` banner only)
- `bridge/src/routes/dispatcher.ts`
- `bridge/src/server.ts` (pass the allow-query flag from the upgrade path only)
- `bridge/test/config.test.ts`, `bridge/test/server.test.ts` (extend)
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/res/xml/` (new backup-rules files)

**Out of scope**:
- Moving the Android token to EncryptedSharedPreferences/Keystore — bigger
  change, defer; excluding it from backup removes the off-device copy.
- `scripts/pair.sh` and the `pair` CLI command — they print pairing material
  **on demand**; that is intended behavior.
- Any change to the token format or `timingSafeEqual`.

## Git workflow

- Work directly on `main`. Conventional commits, e.g.
  `fix(bridge): keep the pairing token out of logs and URLs`.

## Steps

### Step 1: Narrow the config catch

Restructure `loadOrCreateConfig` so only "file missing or unparseable/invalid"
mints a token:

```ts
export async function loadOrCreateConfig(path = defaultConfigPath()): Promise<BridgeConfig> {
  let config: BridgeConfig | null = null;
  try {
    const raw = await readFile(path, "utf8");
    const parsed = JSON.parse(raw) as Partial<BridgeConfig>;
    if (typeof parsed.token !== "string" || parsed.token.length < 16 || typeof parsed.port !== "number") {
      throw new Error("invalid cockpit config (token or port missing)");
    }
    config = { /* same field mapping as today */ };
  } catch {
    config = null;
  }
  if (!config) {
    config = { token: generateToken(), port: 8737, ntfyTopic: `cockpit_${randomBytes(12).toString("base64url")}` };
    await mkdir(join(path, ".."), { recursive: true });
  }
  try {
    await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
  } catch (error) {
    // A parsed config that cannot be re-persisted is still valid; a fresh
    // config that cannot be persisted is fatal (nothing to pair against).
    if (/* config came from a successful read */) {
      console.error(`cockpit config could not be persisted: ${error instanceof Error ? error.message : String(error)}`);
    } else {
      throw error;
    }
  }
  return config;
}
```

Track "came from a successful read" with a boolean set in the read path.

**Verify**: `cd bridge && npm test` → `config.test.ts` passes.

### Step 2: Remove the token from the serve banner

In `bridge/src/cli.ts`, replace `console.error(\`token: ${config.token}\`)`
with a pointer that leaks nothing:

```ts
console.error(`token: run 'cockpit-bridge pair' or read ${defaultConfigPath()}`);
```

(`defaultConfigPath` is exported from `./config.js`; import it in the serve
case alongside `loadOrCreateConfig`.)

**Verify**: `grep -n 'config.token' bridge/src/cli.ts` → no match inside a
`console.*` call.

### Step 3: Restrict query-token auth to the WS upgrade

Change `isAuthorized`'s signature to
`isAuthorized(request, token, opts: { allowQueryToken?: boolean } = {})` and
gate the `queryToken` branch on `opts.allowQueryToken === true`. Update the
upgrade handler in `bridge/src/server.ts:111-119` to pass
`{ allowQueryToken: true }`; `dispatchRoute` passes nothing.

**Verify**: `cd bridge && npm test` → all pass.

### Step 4: Pin both behaviors with offline HTTP tests

In `bridge/test/server.test.ts` (offline suite, fake herdr — follow the
existing request-helper pattern in that file):
- `GET /api/health?token=<the test config's token>` with no Authorization
  header → **401**.
- The existing header-auth tests still pass unchanged.
- If the offline harness exercises the WS upgrade (check for a `/ws` test),
  assert query-token upgrade still succeeds; if it does not, note that in the
  test file comment rather than building a WS harness from scratch.

**Verify**: `cd bridge && npm test` → all pass including the new 401 case.

### Step 5: Exclude the connection prefs from Android backup

Create `android/app/src/main/res/xml/backup_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="sharedpref" path="cockpit_connection.xml" />
</full-backup-content>
```

Create `android/app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="cockpit_connection.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="cockpit_connection.xml" />
    </device-transfer>
</data-extraction-rules>
```

In `AndroidManifest.xml`, on the `<application>` element add:

```xml
android:fullBackupContent="@xml/backup_rules"
android:dataExtractionRules="@xml/data_extraction_rules"
```

(keep `allowBackup="true"` — other prefs like reduce-motion are harmless and
excluding only the credential is the surgical fix).

**Verify**: `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` → BUILD SUCCESSFUL.

## Test plan

- Bridge: extend `bridge/test/config.test.ts` — a config file that exists and
  parses but whose **path is then made unwritable** keeps its token
  (simulate: point the config at a read-only directory via `chmod 0500` on a
  mkdtemp dir, or mock; skip on platforms where chmod is a no-op as root).
- Bridge: `server.test.ts` 401-on-query-token case (step 4).
- Android: no new unit tests (manifest/XML change) — the build + existing
  suites are the gate.

## Done criteria

- [ ] `cd bridge && npm run typecheck && npm test` exits 0
- [ ] `grep -rn "token: \${config.token}" bridge/src/` returns nothing
- [ ] `grep -n "allowQueryToken" bridge/src/routes/dispatcher.ts bridge/src/server.ts` shows the flag defined and passed only from the upgrade path
- [ ] `grep -n "dataExtractionRules\|fullBackupContent" android/app/src/main/AndroidManifest.xml` shows both attributes
- [ ] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks assembleDebug` succeeds
- [ ] No secret values appear in any changed file or commit message
- [ ] `plans/README.md` status row updated

## STOP conditions

- The Android client turns out to use `?token=` for any **HTTP** call
  (search `BridgeClient.kt` for query-token usage beyond the WS connect
  before step 3; if found, report — do not break the app).
- `config.test.ts` reveals a caller that depends on regenerate-on-write-failure.
- Anything requires printing, committing, or asserting on a real token value.

## Maintenance notes

- **After this lands, the operator must rotate the existing token** (delete
  the `token` field in `~/.config/cockpit/config.json`, restart the bridge,
  re-pair the phone) — prior journal entries contain the old value. Say this
  in the PR/commit body.
- Future: move Android token storage to EncryptedSharedPreferences (deferred
  here); if that happens, the backup exclusions must still stay (Keystore
  keys don't back up, so a restored ciphertext is unreadable anyway).
- Plan 008/DX follow-ups may add request logging — that logger must redact
  `?token=` query strings, which step 3 makes almost moot (only `/ws` accepts
  it).
