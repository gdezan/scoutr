# Pluggable Exposure + Cloudflare Tunnel Blueprint

## Current situation

Scoutr's product transport is already mostly independent of Tailscale: `bridge/src/server.ts` binds the authenticated HTTP/WebSocket API to `127.0.0.1:<port>`, while `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt` builds ordinary HTTP/WSS requests from the saved base URL and sends the Scoutr token in the `Authorization` header. The load-bearing Tailscale coupling lives around deployment and pairing rather than inside the bridge protocol itself.

Verified Tailscale-specific anchors:

- `bridge/src/cli.ts` → `pair`: resolves `config.publicHost`, then `SCOUTR_PUBLIC_HOST`, then shells out to `tailscale status --json` for MagicDNS discovery; `serve` also prints a Tailscale-specific exposure hint.
- `bridge/src/config.ts` → `BridgeConfig`: documents `port` as fronted by `tailscale serve` and stores a single `publicHost` override rather than an exposure mode.
- `bridge/src/pairing.ts` → `PairingPayload`: v1 carries only `host`, `token`, and optional ntfy discovery.
- `README.md`, `PRODUCT.md`, and `docs/decisions.md`: describe the bridge as tailnet-only and the deployment recipe as `tailscale serve`.
- `scripts/deploy-bridge.sh`, `bridge/package.json` → `scripts.deploy`, and `bridge/scripts/check-deployed.mjs`: assume Linux systemd and, for the deployed-state gate, Linux `ss`.

The Android side is URL-driven rather than Tailscale-driven:

- `BridgeClient.baseUrl()` consumes `ConnectionStore.saved.host` verbatim.
- `TopologyFeedClient` derives WSS from the same saved host and sends the same bearer token.
- `ConnectScreen` accepts an arbitrary bridge address, but its placeholder still advertises `.ts.net`.
- `PairingPayloadParser` is the only QR-version gate; it currently accepts exactly v1.

Credential storage is a separate security seam exposed by this work. `ConnectionStore` stores the Scoutr bearer token directly in app-private `SharedPreferences`. `backup_rules.xml` and `data_extraction_rules.xml` correctly exclude that file from cloud backup/device transfer, but the token is still plaintext at rest inside the app sandbox.

The bridge token itself is strong (`generateToken()` uses 18 random bytes, 144 bits before encoding), HTTP routes authenticate before route/body processing in `routes/dispatcher.ts`, and current clients use the Authorization header rather than putting the token in URLs. The terminal and command surfaces therefore remain behind the same bearer capability when the bridge is exposed through another HTTPS reverse proxy.

`ntfy` needs separate treatment. Under Tailscale it is reachable only inside the tailnet even though the ntfy server itself has no user authentication; the random `scoutr_<12 random bytes>` topic is the capability secret. A public Cloudflare Tunnel removes the tailnet perimeter, so the Cloudflare recipe must publish ntfy on a separate hostname and explicitly document that the topic remains its only application-layer capability in this blueprint.

Operational precedent: `docs/dev-workflow.md` treats the compiled, supervised bridge process as the real deployment and requires a post-build freshness/health gate. Preserve that invariant on macOS rather than adding a second, weaker deployment path.

External facts verified against current primary documentation (August 2026):

- Cloudflare Tunnel can publish an HTTPS hostname to a local HTTP service such as `http://localhost:8737`; `cloudflared` maintains outbound connections, so the origin does not need an inbound public port.
- Cloudflare Tunnel ingress supports HTTP applications and long-lived WebSocket traffic; restarting/replacing `cloudflared` drops existing long-lived WebSocket connections and new connections recover through the replacement.
- One named tunnel may publish multiple hostnames, which lets Scoutr use one hostname for the bridge and a second for ntfy without introducing path-rewrite behavior.
- On macOS, Cloudflare documents `cloudflared service install` as a per-user LaunchAgent and `sudo cloudflared service install` as a boot LaunchDaemon. Scoutr consumes this preconfigured service; it does not provision Cloudflare resources.
- AndroidX `EncryptedSharedPreferences` and `MasterKey` are deprecated. Current Android guidance is to use Android Keystore directly (`KeyGenerator` with the `AndroidKeyStore` provider) and standard crypto such as AES/GCM.

Repository contradiction resolved by this brief: `PRODUCT.md` currently says "no cloud/subscriptions". The intended invariant becomes **no Scoutr-owned cloud service or required subscription**. User-owned optional exposure providers such as Tailscale or Cloudflare Tunnel are allowed; Scoutr remains self-hosted and the bridge/Herdr data plane still terminates on the user's host.

## Objective and why

Make bridge reachability an explicit deployment choice instead of a Tailscale assumption, with three supported exposure modes:

1. **Tailscale** — existing personal-host path and default for existing configs.
2. **Cloudflare Tunnel** — recommended alternative when the host must also run a work-managed Cloudflare WARP client and a second mesh VPN is undesirable.
3. **Custom** — user-supplied HTTPS/base URL for nginx, Caddy, reverse SSH/VPS, another tunnel provider, or future deployment patterns.

Cloudflare mode must be operationally complete on macOS: the Scoutr bridge runs as a user LaunchAgent, `cloudflared` is an independently preconfigured service owned by Cloudflare's tooling, the public bridge URL reaches localhost only through the tunnel, ntfy has its own public hostname, pairing works by QR, and the Android app stores its bearer credential using Android Keystore-backed encryption.

Done means:

- existing Linux/Tailscale users keep working without manually rewriting their config;
- Cloudflare/custom pairing no longer executes or requires Tailscale discovery;
- an old Android client cannot accidentally accept a Cloudflare/custom QR that needs the new exposure contract;
- the new Android client can pair to Tailscale v1 and Cloudflare/custom v2 payloads;
- the bearer token is no longer stored plaintext in Android preferences after migration;
- bridge deploy/restart/freshness checks work on Linux systemd and macOS launchd;
- a configured macOS + Cloudflare Tunnel deployment can reach authenticated HTTP and WebSocket/terminal flows from Android without Tailscale installed on that Mac.

## Scope

Included:

- explicit bridge exposure configuration for `tailscale`, `cloudflare`, and `custom`;
- one testable public-URL resolver extracted from the CLI;
- existing Tailscale auto-discovery retained only for Tailscale mode;
- Cloudflare/custom pairing payload v2 with exposure metadata;
- existing Tailscale pairing payload v1 retained as the compatibility contract for the existing path;
- Android parsing of v1 + v2 pairing payloads and persistence of exposure metadata;
- Android Keystore-backed encryption of the saved Scoutr bearer token, including migration from the existing plaintext preference;
- Linux systemd + macOS launchd bridge service ownership behind one deployment helper;
- cross-platform deployed-state checks;
- macOS + preconfigured `cloudflared` setup documentation, including separate bridge/ntfy hostnames and WARP connectivity diagnostics;
- removal of Tailscale-only wording from generic product/runtime surfaces.

Non-goals:

- no Cloudflare API integration;
- no creation/deletion/configuration of Cloudflare accounts, zones, DNS, tunnels, Access applications, service tokens, WAF rules, or tunnel credentials;
- no Scoutr management of the `cloudflared` process beyond documenting/validating the externally configured service;
- no Cloudflare Access layer: by explicit decision, the public bridge uses the existing Scoutr bearer token as its only application authentication;
- no Scoutr relay, Worker, Durable Object, reverse multiplexing protocol, or new hosted backend;
- no general VPN coexistence logic and no attempt to reconfigure company-managed WARP;
- no new ntfy authentication scheme in this blueprint; the random topic remains its capability secret;
- no Windows host-service implementation;
- no redesign of the bridge HTTP/WS API, Herdr socket boundary, or terminal protocol.

Compatibility:

- configs without an `exposure` object normalize to `tailscale` so the current Linux deployment remains the default;
- an existing `publicHost` value is migrated/treated as the public URL override for that default Tailscale exposure rather than discarded;
- existing saved Android pairings remain usable and are migrated in place from plaintext-token storage to encrypted-token storage;
- Tailscale QR payload v1 remains valid in the new app and continues to be emitted for Tailscale mode;
- Cloudflare/custom use QR payload v2; old Android builds that only know v1 reject those QR codes rather than accepting an incomplete connection;
- no v1 Cloudflare/custom QR fallback is generated.

## Global constraints

- Bridge listen remains loopback-only: `127.0.0.1:<port>`; do not bind Scoutr directly to LAN/public interfaces.
- Never expose the Herdr Unix socket; the bridge remains the only remote control boundary.
- Every bridge HTTP route and WebSocket upgrade remains protected by the Scoutr bearer token before feature work is performed.
- Pairing tokens stay in Authorization headers for HTTP/WSS traffic; do not move them into URLs.
- Android remains minSdk 26 / targetSdk 36, Kotlin + Compose, manual DI, and no Hilt/Room.
- Do not add deprecated `androidx.security:security-crypto` APIs for the credential migration; use Android Keystore + platform cryptography.
- `ConnectionStore` remains the owning abstraction for saved connection identity; callers do not learn encryption details.
- Backups/device transfer must continue excluding connection credential material.
- Tailscale and Cloudflare remain deployment providers outside the bridge runtime; `scoutr-bridge serve` must not own either daemon.
- Preserve `docs/dev-workflow.md`'s invariant that the app talks to a compiled, supervised bridge and deployment freshness is proven after restart.
- Emulator/integration/E2E/runtime acceptance is terminal: implement → cheap checks → independent review/fix → code frozen → final runtime acceptance.

## Resolved decisions

### Exposure modes

Selected: **Tailscale + Cloudflare Tunnel + custom/manual URL are all first-class**.

The bridge protocol does not change based on exposure. The exposure layer only answers: "what public base URL should pairing advertise, and who is responsible for making that URL reach localhost?"

### Cloudflare ownership

Selected: **Scoutr consumes a preconfigured `cloudflared` tunnel**.

Scoutr documents the origin ports/hostnames and validates the configured public URLs. The user/administrator creates the tunnel and DNS route through Cloudflare tooling. This avoids Cloudflare account credentials, API coupling, provisioning state, and cleanup logic inside Scoutr.

### Cloudflare authentication

Selected: **no Cloudflare Access**.

The Cloudflare bridge hostname is publicly routable and Scoutr's 144-bit bearer token remains the application authentication boundary. This is intentionally weaker defense-in-depth than Cloudflare Access + Scoutr auth. Preserve constant-time token comparison, auth-before-body parsing, and header-only client auth.

Consequential rejected alternative: Cloudflare Access service tokens were considered but explicitly rejected for this iteration. Do not add `CF-Access-Client-*` fields, headers, QR secrets, or Access provisioning.

### Android credential storage

Selected: **migrate the Scoutr bearer token to Android Keystore-backed encrypted storage**.

Do not use deprecated `EncryptedSharedPreferences`/`MasterKey`. Keep non-secret connection metadata in SharedPreferences; encrypt only secret values with an AES key generated in `AndroidKeyStore`, using AES/GCM/NoPadding with a fresh system-generated IV per write. Persist ciphertext + IV, not the key.

### Pairing protocol

Selected: **v2 for new Cloudflare/custom exposure pairings, with no v1 fallback for those modes**.

To preserve the explicitly retained existing Tailscale flow, Tailscale continues to emit v1 and the new Android parser accepts both versions. v2 adds an explicit exposure kind; it does not add Cloudflare Access credentials because Access was rejected.

### macOS completeness

Selected: **macOS is an owned host deployment path, not docs-only theoretical compatibility**.

The repository must be able to install/restart/status-check Scoutr bridge under launchd and run the same freshness gate used on Linux. `cloudflared` remains externally owned and installed using Cloudflare's documented service command.

### ntfy under Cloudflare

Use a separate public hostname such as `scoutr-ntfy.example.com -> http://127.0.0.1:8382`, while the bridge uses `scoutr.example.com -> http://127.0.0.1:8737`.

Do not try to preserve the Tailscale `/ntfy` prefix through Cloudflare path routing; the current ntfy publisher/client expect their configured `baseUrl` to be the actual ntfy root. The high-entropy topic remains the only ntfy capability secret in this blueprint. Document that anyone who obtains the topic can read/publish that topic through the public ntfy hostname.

### Product wording

Replace "no cloud" with "no Scoutr-owned cloud/backend and no required subscription". Optional user-owned network/exposure providers do not change the self-hosted product identity.

## Approach

Keep the bridge protocol boring and move all provider knowledge to a small exposure-resolution seam used only by pairing/setup.

End-to-end flow:

1. `loadOrCreateConfig` normalizes the persisted config into `exposure: { kind, publicUrl? }`, defaulting legacy configs to Tailscale and preserving any legacy `publicHost` override.
2. `scoutr-bridge pair` calls a pure/testable exposure resolver:
   - Tailscale: use configured URL override when present, otherwise discover MagicDNS via `tailscale status --json`.
   - Cloudflare/custom: require an explicit public URL and never execute `tailscale`.
3. Pairing builder emits:
   - v1 for Tailscale, preserving the current QR contract;
   - v2 for Cloudflare/custom, adding `exposure.kind` plus the same host/token/optional ntfy data.
4. Android parses either version. v1 becomes `tailscale`; v2 validates `exposure.kind` as `cloudflare` or `custom` (and may tolerate `tailscale` only if tests justify it, but the bridge does not emit v2 Tailscale in this plan).
5. `ConnectViewModel` probes the supplied host/token exactly as today, then saves the successful connection plus exposure metadata. `ConnectionStore` encrypts the token before persistence and removes any legacy plaintext token.
6. All network clients continue consuming `ConnectionStore.Saved.host/token`; they remain provider-agnostic.
7. On hosts, one `bridge-service` helper owns install/restart/status metadata:
   - Linux adapter: systemd user service.
   - macOS adapter: user LaunchAgent.
8. `npm run deploy`, root `make deploy-bridge`, and `check-deployed` use that helper so both OSes prove compiled artifact freshness, supervised process freshness, local port ownership, and authenticated `/api/health`.
9. Cloudflare docs configure a named tunnel outside Scoutr with two public hostnames pointing to the two localhost services. The bridge config stores those final URLs; Scoutr does not inspect Cloudflare account state.
10. Final acceptance proves the public HTTPS + WSS path after review/code freeze.

The core bridge server, route handlers, Herdr socket code, terminal broker, Android `ScoutrApi`, and ViewModel feature logic do not branch on Tailscale/Cloudflare/custom.

## Contracts and interfaces

### Bridge exposure config

Canonical persisted shape:

```ts
export type ExposureKind = "tailscale" | "cloudflare" | "custom";

export interface ExposureConfig {
  kind: ExposureKind;
  /** Full public base URL. Optional only for tailscale auto-discovery. */
  publicUrl?: string;
}

export interface BridgeConfig {
  configDir: string;
  token: string;
  port: number;
  ntfyUrl?: string;
  ntfyTopic?: string;
  exposure: ExposureConfig;
}
```

Normalization rules:

- missing `exposure` => `{ kind: "tailscale", publicUrl: legacyPublicHost }`;
- existing valid canonical config => preserve it;
- unknown exposure kind => fail config validation clearly; never silently fall back to Tailscale;
- Cloudflare/custom without a usable `publicUrl` may still run `serve` locally, but `pair` must fail with actionable configuration guidance because it cannot advertise a reachable host;
- preserve current token-recovery invariant: a valid parsed config whose re-persist fails must not mint a replacement token.

Environment override: keep one public-URL override supported for operational use. Reusing `SCOUTR_PUBLIC_HOST` is acceptable and minimizes compatibility churn; renaming it requires accepting the old name as an alias for existing deployments. Do not add provider-specific environment variables unless a real need appears.

### Exposure resolver

Extract CLI discovery behind a module, e.g. `bridge/src/exposure.ts`:

```ts
export interface ResolvedExposure {
  kind: ExposureKind;
  publicUrl: string;
}

export async function resolveExposure(
  config: BridgeConfig,
  deps?: { discoverTailscaleUrl?: () => Promise<string | null> },
): Promise<ResolvedExposure>;
```

Invariants:

- only `kind === "tailscale"` may invoke Tailscale discovery;
- configured public URL wins over discovery;
- Cloudflare public URL must be `https://...` in production configuration; reject/warn on insecure HTTP rather than normalizing it to HTTPS invisibly;
- custom may retain explicit `http://` for local/dev use, subject to Android's existing cleartext restrictions;
- `withScheme` is not allowed to turn a misconfigured Cloudflare URL into a different security contract silently.

### Pairing v1/v2

Tailscale v1 stays byte-for-byte shape-compatible:

```json
{
  "v": 1,
  "host": "https://artemis.example.ts.net",
  "token": "scoutr_...",
  "ntfy": { "url": "https://artemis.example.ts.net/ntfy", "topic": "scoutr_..." }
}
```

Cloudflare/custom v2:

```json
{
  "v": 2,
  "host": "https://scoutr.example.com",
  "token": "scoutr_...",
  "exposure": { "kind": "cloudflare" },
  "ntfy": { "url": "https://scoutr-ntfy.example.com", "topic": "scoutr_..." }
}
```

v2 validation:

- `host` + `token` required;
- `exposure.kind` required and known;
- optional ntfy remains all-or-nothing (`url` + `topic` together);
- no `edgeAuth` or Cloudflare credential fields.

### Android saved connection

Extend metadata but keep callers provider-agnostic:

```kotlin
enum class ExposureKind { Tailscale, Cloudflare, Custom }

data class Saved(
    val host: String,
    val token: String,          // decrypted in-memory value only
    val exposure: ExposureKind,
    val ntfyUrl: String? = null,
    val ntfyTopic: String? = null,
)
```

Manual connect uses `Custom` unless the caller has pairing metadata. QR connect passes the parsed exposure kind.

### Android credential-at-rest contract

Introduce an injectable crypto seam owned by `ConnectionStore`, for example:

```kotlin
interface ConnectionCipher {
    fun encrypt(plaintext: ByteArray): EncryptedValue
    fun decrypt(value: EncryptedValue): ByteArray
    fun clearKey()
}

data class EncryptedValue(
    val ciphertext: ByteArray,
    val iv: ByteArray,
)
```

Production implementation:

- AES key alias dedicated to Scoutr connection credentials;
- `KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")`;
- 256-bit key where supported by the platform/API contract;
- purposes encrypt + decrypt;
- GCM block mode;
- no padding;
- randomized encryption required;
- `Cipher.getInstance("AES/GCM/NoPadding")`;
- encryption initializes without caller-provided IV and persists `cipher.iv` with ciphertext;
- decryption supplies the stored IV/GCM parameters;
- no biometric/user-auth requirement: background monitoring must be able to read the connection while the device is locked.

Preference storage must contain encoded ciphertext/IV and never the plaintext token after a successful save/migration.

Legacy migration:

1. If encrypted token fields exist, decrypt them and ignore any stale plaintext field.
2. If only legacy `token` exists, encrypt it and commit ciphertext + IV + removal of plaintext in one preference transaction.
3. If encryption or commit fails, do not delete the only readable plaintext token; surface/retain the legacy value for that process and retry migration later rather than losing the pairing.
4. Once encrypted persistence succeeds, remove plaintext permanently.
5. `clear()` clears preference material and deletes the dedicated Keystore alias.

`backup_rules.xml` and `data_extraction_rules.xml` continue excluding the entire connection preference file.

### Host service helper

Create one root operational seam, e.g. `scripts/bridge-service.mjs`:

```text
node scripts/bridge-service.mjs install
node scripts/bridge-service.mjs restart
node scripts/bridge-service.mjs status --json
```

`status --json` must provide enough stable data for `check-deployed.mjs`, e.g.:

```json
{
  "manager": "systemd",
  "active": true,
  "pid": 1234,
  "startedAtMs": 1780000000000
}
```

or `manager: "launchd"` on macOS.

Install behavior:

- Linux: preserve the existing user-service semantics (`Restart=on-failure`, compiled `dist/cli.js`, absolute Node path).
- macOS: write/install a user LaunchAgent under `~/Library/LaunchAgents/` with `RunAtLoad` + `KeepAlive`, absolute Node path, working directory at `bridge/`, and log paths under the user's Library logs/cache area.
- Resolve any required executable path that launchd cannot be expected to find (notably `HERDR_BIN`) at install time or explicitly set a safe PATH; do not assume an interactive shell environment.
- Re-running `install` must be idempotent and update the definition when checkout/Node/Herdr paths changed.

`cloudflared` is **not** part of this helper.

### Cross-change interface table

| Change | Consumes | Produces |
|---|---|---|
| 1 — exposure config/resolver | legacy `publicHost`, current CLI discovery | canonical `ExposureConfig`, `resolveExposure()` |
| 2 — pairing contract | `ResolvedExposure`, token, ntfy | v1 Tailscale or v2 Cloudflare/custom QR payload |
| 3 — Android connection security | v1/v2 parsed pairing | `Saved` connection with encrypted-at-rest token |
| 4 — provider-neutral Android/network UX | `Saved.host/token/exposure` | unchanged HTTP/WSS behavior across providers |
| 5 — host service portability | compiled bridge + repo paths | systemd/launchd install/restart/status contract |
| 6 — deployment gate + Cloudflare ops | host service status, final URLs | proven local deployment + documented public tunnel |

## Changes

- [x] **1 — Make exposure configuration explicit and isolate provider discovery**
  - Anchor: `bridge/src/config.ts` → `BridgeConfig`, `loadOrCreateConfig`
  - Anchor: `bridge/src/cli.ts` → `case "pair"`
  - Create `ExposureKind` / `ExposureConfig` and a small `bridge/src/exposure.ts` (name local discretion) that owns URL resolution.
  - Normalize legacy config to Tailscale, preserving `publicHost` as the Tailscale URL override, then persist the canonical shape when possible.
  - Move `tailscale status --json` execution out of `cli.ts` and inject/stub it in tests.
  - Cloudflare/custom must require an explicit public URL for pairing and must never execute the Tailscale binary.
  - Remove Tailscale-specific `serve` console guidance; print a provider-neutral local-listen message and point pairing/setup to configured exposure instead.
  - Extend `bridge/test/config.test.ts` for canonical config, legacy migration, invalid kind, and re-persist failure invariants.
  - Add focused exposure-resolver tests proving only Tailscale invokes discovery.
  - Proof: focused bridge tests pass; running `pair` with a Cloudflare config succeeds on a machine with no `tailscale` executable, while default/legacy config still resolves through the Tailscale path.

- [x] **2 — Version pairing by exposure without breaking the existing Tailscale QR**
  - Anchor: `bridge/src/pairing.ts` → `PairingPayload`, `buildPairingPayload`, `parsePairingPayload`
  - Anchor: `bridge/test/pairing.test.ts`
  - Preserve exact v1 Tailscale payload behavior.
  - Add the v2 schema for Cloudflare/custom with mandatory `exposure.kind` and no edge-auth fields.
  - Make the builder choose the version from `ResolvedExposure.kind`; do not expose a boolean/version flag to callers that can create invalid provider/version combinations.
  - Update `scoutr-bridge pair` output/errors to name the selected exposure and final host without printing secrets to journald/service logs; explicit interactive `pair` output may continue to contain the QR/token by design.
  - Proof: bridge tests assert v1 Tailscale round-trip, v2 Cloudflare/custom round-trip, unknown/missing v2 exposure rejection, and no v1 payload generation for Cloudflare/custom.

- [x] **3 — Encrypt Android connection credentials with Android Keystore and migrate legacy prefs**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/data/ConnectionStore.kt` → `Saved`, `saved`, `save`, `clear`
  - Anchor: `android/app/src/main/res/xml/backup_rules.xml`
  - Anchor: `android/app/src/main/res/xml/data_extraction_rules.xml`
  - Create an injectable `ConnectionCipher` plus production `AndroidKeystoreConnectionCipher` (exact names local discretion).
  - Store host/exposure/ntfy metadata as normal preferences; store token only as AES-GCM ciphertext + IV.
  - Implement the one-time plaintext-token migration with no data-loss window: do not remove plaintext unless encrypted persistence succeeds.
  - `clear()` removes both preference state and the dedicated Keystore key.
  - Do not add `androidx.security:security-crypto`; current platform APIs cover minSdk 26.
  - Add JVM/Robolectric tests with a fake cipher for save/read/clear, plaintext migration, migration write failure, corrupt ciphertext, and preference-shape assertions that plaintext token is absent after success.
  - Keep backup exclusions current if preference filenames/paths change.
  - Proof: focused Android tests show legacy pairing survives migration, successful persistence contains no plaintext token, and clearing makes the credential unrecoverable from both prefs and the injected key store.

- [x] **4 — Teach Android pairing about exposure while keeping all feature networking provider-neutral**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/data/PairingPayload.kt` → `PairingPayloadParser`
  - Anchor: `android/app/src/test/java/dev/scoutr/app/data/PairingPayloadTest.kt`
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/ConnectViewModel.kt` → `connect`
  - Anchor: `android/app/src/main/java/dev/scoutr/app/ui/screens/ConnectScreen.kt` → QR callback / bridge address field
  - Anchor: `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt` and `TopologyFeedClient.kt` as invariants/negative anchors: do not add provider branches there.
  - Parse v1 as Tailscale and v2 as its explicit exposure kind; reject malformed/unknown v2 payloads.
  - Pass QR exposure metadata through the connect/save path. Manual host+token connection records `Custom`.
  - Replace `.ts.net`-specific placeholder/help text with a provider-neutral HTTPS example.
  - Update tests for v1 Tailscale, v2 Cloudflare, v2 custom, malformed v2, and manual-custom persistence.
  - Confirm HTTP, short-lived command WS, topology WS, and terminal WS all still derive solely from the saved base URL + token.
  - Proof: JVM/Compose tests pair from both v1 and v2 payloads and network client tests show identical headers/URLs regardless of exposure metadata.

- [x] **5 — Make the supervised bridge deployment contract work on systemd and launchd**
  - Anchor: `scripts/deploy-bridge.sh`
  - Anchor: `bridge/package.json` → `scripts.deploy`
  - Anchor: `bridge/scripts/check-deployed.mjs`
  - Anchor: `docs/dev-workflow.md` → `Deploying bridge changes`
  - Add the cross-platform `scripts/bridge-service.mjs` install/restart/status seam described above.
  - Linux adapter must preserve current systemd user-service behavior and existing users; do not force a reinstall just to deploy if their service is already valid.
  - macOS adapter installs/updates a user LaunchAgent with compiled `dist/cli.js`, absolute executable paths, RunAtLoad/KeepAlive, and readable log locations.
  - Change `npm run deploy` and `scripts/deploy-bridge.sh` to build, restart through the helper, then run the deployed gate. Remove direct `systemctl` calls from the package script.
  - Generalize `check-deployed.mjs` to consume helper status and perform OS-specific port-owner verification (`ss` on Linux; `lsof` or another established macOS primitive on Darwin), while retaining all three current invariants: dist newer than src, service started after dist, supervised PID owns the configured port + authenticated health succeeds.
  - Update scratch/development recipes that currently write plaintext `scoutr_connection.xml` directly; after encrypted storage, emulator pairing must go through app code (Connect UI/test seam), not hand-authored secret prefs.
  - Proof: helper unit/fixture tests cover manager selection and generated service definitions; Linux cheap verification remains green; on macOS, install/restart/status + `npm run check:deployed` succeed against the real LaunchAgent.

- [x] **6 — Document and validate the Cloudflare/macOS deployment as a first-class path**
  - Anchor: `README.md` → host prerequisites, bridge daemon, exposure, ntfy, configuration reference, day-to-day scripts
  - Anchor: `docs/dev-workflow.md` → real deployment/final diagnostics
  - Anchor: `PRODUCT.md` → product mechanism/constraints
  - Anchor: `docs/decisions.md` → architecture + security notes
  - Rewrite the architecture diagram/text so HTTP/WSS reachability is "configured exposure" rather than "tailnet" while keeping Tailscale as the easiest/default personal path.
  - Add three exposure recipes:
    - Tailscale: current `tailscale serve` behavior;
    - Cloudflare: preconfigured named tunnel, bridge hostname -> `http://127.0.0.1:8737`, ntfy hostname -> `http://127.0.0.1:8382`, `exposure.kind=cloudflare`, public bridge URL, `ntfyUrl` at the ntfy hostname;
    - custom: explicit public URL with proxy ownership outside Scoutr.
  - macOS recipe must install the Scoutr LaunchAgent via the repository helper and `cloudflared` via Cloudflare's documented service mechanism; clearly separate ownership so Scoutr never writes Cloudflare credentials/config.
  - Include `cloudflared` connectivity diagnostics for managed-WARP environments. A WARP policy that blocks tunnel edge connectivity is an infrastructure/policy failure, not a reason to add VPN workarounds inside Scoutr.
  - State the explicit security posture: no Cloudflare Access; bridge is Internet-routable through Cloudflare and relies on the random Scoutr bearer token; ntfy relies on its random topic capability. Recommend revoking/regenerating pairing credentials if a QR/token/topic is exposed.
  - Update package description/comments that still say "Tailscale tailnet" when they mean generic remote API exposure.
  - Proof: docs contain copy-pasteable Linux/Tailscale and macOS/Cloudflare paths whose config names match code; no generic runtime docs claim Tailscale is required.

## Failure handling

### Exposure/config

- Unknown exposure kind: configuration error; do not default silently.
- Cloudflare/custom pairing without `publicUrl`: `pair` exits non-zero with an exact config fix; `serve` may still run locally.
- Tailscale discovery unavailable in Tailscale mode: preserve current fallback behavior only if it produces an explicitly local/unpairable warning; do not emit a QR that appears remotely usable when the resolved host is loopback.
- Cloudflare URL uses insecure HTTP: reject for the Cloudflare mode. TLS termination belongs at Cloudflare.
- Custom HTTP URL: allow only as explicit custom/dev intent; Android release cleartext policy may reject it and the error must remain visible.

### Authentication/security

- Invalid/missing bearer token through Cloudflare: 401 before route match/body parsing, same as local/Tailscale.
- Never log the bearer token from `serve`, deployment helpers, launchd/systemd definitions, or health gates.
- Do not place Cloudflare tunnel credentials in `~/.config/scoutr/config.json`.
- Public ntfy hostname receives no new auth in this blueprint. Treat topic disclosure as credential disclosure and document rotation.

### Android Keystore

- Legacy plaintext + successful encryption: atomically persist encrypted fields and remove plaintext.
- Legacy plaintext + encryption/persistence failure: preserve the old readable token; do not strand the paired device.
- Encrypted record + missing/invalidated Keystore key or corrupt ciphertext: fail closed, surface that the saved pairing cannot be decrypted, and require re-pairing. Do not regenerate a token or invent plaintext recovery.
- `clear()`/Forget must invalidate local credential material even if later service/notification cleanup fails.
- Background monitoring must not require user presence/biometric unlock to decrypt; do not set user-auth-required key properties.

### Host service/deployment

- Unsupported OS: helper exits with a clear unsupported-manager error; no partial service install.
- launchd/systemd definition exists but points to stale checkout/Node/Herdr path: idempotent install/update replaces it and reloads/restarts the user service.
- service restart succeeds but old/manual process owns port: deployed gate fails exactly as today rather than accepting health from the wrong process.
- service starts before rebuilt `dist`: deployed gate fails.
- Cloudflare tunnel down while local health is good: classify as exposure/infrastructure failure; diagnose `cloudflared` separately from bridge code.
- Managed WARP blocks Cloudflare Tunnel edge connectivity: report the policy/connectivity result and stop; do not modify company WARP routes, split-tunnel policy, or security settings.

### WebSockets/reconnect

Cloudflare mode must not add a parallel transport. Existing OkHttp WebSocket clients use the public HTTPS base URL converted to WSS and retain current reconnect/grace behavior. A `cloudflared` restart may drop long-lived terminal/topology sockets; normal Scoutr reconnect behavior should recover where it already does. If live validation exposes a provider-specific proxy behavior that requires protocol changes, escalate rather than adding ad-hoc Cloudflare branches to each client.

## Validation

Cheap checks first:

1. Focused bridge config/exposure/pairing tests.
2. Focused Android pairing + ConnectionStore/crypto migration tests.
3. Focused host-service helper tests/fixtures; generated plist/unit files inspected without installing them in ordinary unit tests.
4. `make bridge-test`.
5. `make android-test`.
6. Static/reference scan: no provider branching in `BridgeClient`, `TopologyFeedClient`, terminal socket transport, route handlers, or Herdr modules; Tailscale references remain only in Tailscale exposure implementation/docs/tests.
7. Independent pre-commit review using `skills/scoutr-review/SKILL.md`; resolve/dismiss every concrete finding.

Final runtime acceptance only after review-clean/code-freeze, following `skills/scoutr-verification/SKILL.md`:

### Linux/Tailscale regression

- existing/default config starts under systemd;
- `npm run check:deployed` passes;
- `scoutr-bridge pair` emits v1;
- authenticated health + one WebSocket/terminal attach still work through the existing tailnet URL.

### macOS/Cloudflare acceptance

Requires a macOS host with Herdr/Scoutr prerequisites and a user-configured named Cloudflare Tunnel. If such a host is unavailable, report this acceptance item as unproven rather than replacing it with a Linux simulation.

- install/update Scoutr bridge LaunchAgent;
- install/run preconfigured `cloudflared` service independently;
- local `http://127.0.0.1:8737/api/health` succeeds with bearer;
- public `https://<scoutr-host>/api/health` returns 401 without bearer and succeeds with bearer;
- public `https://<ntfy-host>/v1/health` reaches ntfy;
- `scoutr-bridge pair` emits v2 Cloudflare QR with separate ntfy URL;
- Android scans v2, persists an encrypted token, loads Board, performs a command/steer request, and opens a terminal WebSocket through the Cloudflare hostname;
- restart `cloudflared` once and verify the documented reconnect behavior rather than assuming long-lived sockets survive;
- run `npm run check:deployed` on macOS after bridge deployment;
- if the target machine is using managed WARP, run Cloudflare's documented tunnel connectivity diagnostic and record whether policy permits `cloudflared`; do not change WARP policy as part of acceptance.

No emulator/integration/E2E loop before review. If final acceptance finds a product defect, return to cheap checks + review, freeze again, then perform one new final pass.

## Local discretion

- Exact filenames/type names for `ExposureConfig`, resolver, Android cipher, and host-service helper, provided ownership/contracts above remain intact.
- Whether exposure kind is stored as enum name or stable lowercase string in Android preferences; persisted values must be explicit and tested.
- Exact launchd label/log filenames, provided they are user-scoped, deterministic, and idempotently managed.
- Exact OS command used to prove the macOS listener PID (`lsof` is acceptable if present); the proof requirement is the supervised PID owning the configured port.
- Whether existing `SCOUTR_PUBLIC_HOST` is retained as the canonical environment variable or as an alias to a better-named `SCOUTR_PUBLIC_URL`; existing deployments must not be silently broken.
- Exact UI wording for the provider-neutral Connect placeholder/error, within the design system.
- Whether v2 parser accepts `exposure.kind=tailscale` defensively; bridge generation remains v1 for Tailscale.

## Escalation triggers

- Cloudflare Tunnel needs different HTTP/WS application semantics rather than acting as a transparent reverse proxy for an existing Scoutr endpoint.
- A fix requires provider checks inside feature ViewModels, `BridgeClient`, terminal protocol, Herdr routes, or command handlers instead of the exposure/setup boundary.
- Work-managed WARP blocks `cloudflared` and resolving it would require changing employer VPN/split-tunnel/firewall policy.
- Public ntfy exposure is judged unacceptable without adding ntfy authentication, Cloudflare Access, or a different notification architecture; that is a new security/contract decision.
- The bearer-only public bridge posture is judged insufficient and requires Cloudflare Access/mTLS/device identity; do not smuggle that back in after the explicit 3C decision.
- Keystore use forces broad async connection-state architecture or repeated main-thread stalls across unrelated callers; surface the pattern before spreading escape hatches.
- launchd cannot reproduce required Herdr/terminal environment without embedding fragile interactive-shell state; redesign service environment ownership rather than adding one-off PATH hacks per subprocess.
- Supporting Windows host services becomes required.
- Pairing v2 needs credentials or fields beyond exposure metadata/ntfy, which changes the settled schema/security model.

## Review handoff

The independent reviewer must verify:

- provider knowledge is confined to exposure/setup and does not infect the bridge API or Android feature clients;
- default/legacy Linux/Tailscale behavior survives config normalization and still emits v1 QR;
- Cloudflare/custom never invoke Tailscale discovery and always emit v2;
- old clients fail Cloudflare/custom QR by version rather than accepting a partial payload;
- no Cloudflare Access fields/code were added against the explicit decision;
- the public bridge still rejects unauthenticated HTTP and WebSocket requests before feature handling and no token appears in URLs/logs;
- Android persisted prefs contain no plaintext Scoutr token after successful migration/save;
- migration cannot delete the only usable legacy token before encrypted persistence succeeds;
- deprecated AndroidX security-crypto APIs/dependencies were not introduced;
- Forget clears preference credential material and the Keystore alias;
- systemd and launchd deployment share one conceptual status contract and the deployed gate still catches stale/wrong processes;
- Cloudflare and Scoutr service ownership remain separate;
- ntfy's weaker public capability-secret posture is explicitly documented, not mistaken for bridge bearer auth;
- runtime acceptance, if run, happened only after review/code freeze.

Rerun `make bridge-test`, `make android-test`, and the host-service focused tests after review fixes. Final runtime acceptance is then terminal.

## Completion checklist

- [x] Canonical exposure config supports tailscale/cloudflare/custom.
- [x] Legacy config defaults to Tailscale and preserves existing public-host override.
- [x] Only Tailscale mode can execute Tailscale discovery.
- [x] Tailscale QR remains v1.
- [x] Cloudflare/custom QR uses v2 with explicit exposure metadata and no Access credentials.
- [x] New Android app parses v1 + v2 and manual connect records custom exposure.
- [x] Android token is AES-GCM encrypted with a key held by Android Keystore.
- [x] Legacy plaintext token migrates without a data-loss window.
- [x] Backup/device-transfer exclusions still cover connection material.
- [x] Feature HTTP/WS/terminal clients remain exposure-agnostic.
- [x] Bridge service helper supports systemd user service and macOS user LaunchAgent.
- [x] `npm run deploy`, root deploy script, and deployed freshness gate work through the helper.
- [ ] macOS gate proves compiled freshness, supervised-process freshness, correct port owner, and authenticated health.
- [x] README/dev workflow/product decisions describe Tailscale, Cloudflare, and custom modes accurately.
- [x] Cloudflare recipe uses separate bridge and ntfy hostnames and does not provision/manage Cloudflare resources.
- [x] Bearer-only Cloudflare security posture and public ntfy topic risk are explicit.
- [x] Cheap suites pass.
- [ ] Independent review is clean.
- [ ] Linux/Tailscale regression acceptance passes last.
- [ ] macOS/Cloudflare acceptance passes last, or is explicitly reported unproven because no suitable macOS/tunnel environment was available.

## References

Repository:

- `AGENTS.md`
- `PRODUCT.md`
- `README.md`
- `docs/decisions.md`
- `docs/dev-workflow.md`
- `bridge/src/config.ts`
- `bridge/src/cli.ts`
- `bridge/src/pairing.ts`
- `bridge/src/server.ts`
- `bridge/src/routes/dispatcher.ts`
- `bridge/src/notify.ts`
- `bridge/test/config.test.ts`
- `bridge/test/pairing.test.ts`
- `bridge/package.json`
- `bridge/scripts/check-deployed.mjs`
- `scripts/deploy-bridge.sh`
- `Makefile`
- `android/app/build.gradle.kts`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/res/xml/backup_rules.xml`
- `android/app/src/main/res/xml/data_extraction_rules.xml`
- `android/app/src/main/java/dev/scoutr/app/data/ConnectionStore.kt`
- `android/app/src/main/java/dev/scoutr/app/data/PairingPayload.kt`
- `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt`
- `android/app/src/main/java/dev/scoutr/app/net/TopologyFeedClient.kt`
- `android/app/src/main/java/dev/scoutr/app/state/ConnectViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/ConnectScreen.kt`
- `android/app/src/test/java/dev/scoutr/app/data/PairingPayloadTest.kt`
- `skills/scoutr-review/SKILL.md`
- `skills/scoutr-verification/SKILL.md`

External primary documentation verified while planning:

- Cloudflare Tunnel overview: https://developers.cloudflare.com/tunnel/
- Cloudflare Tunnel setup/published applications: https://developers.cloudflare.com/tunnel/setup/
- Cloudflare Tunnel routing/protocols: https://developers.cloudflare.com/tunnel/routing/
- Cloudflare Tunnel configuration/ingress matching: https://developers.cloudflare.com/tunnel/advanced/local-management/configuration-file/
- Cloudflare Tunnel as a macOS service: https://developers.cloudflare.com/tunnel/advanced/local-management/as-a-service/macos/
- Android Keystore: https://developer.android.com/privacy-and-security/keystore
- Android `KeyGenParameterSpec`: https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec
- Android cryptography guidance: https://developer.android.com/privacy-and-security/cryptography
- Deprecated AndroidX security crypto package: https://developer.android.com/reference/androidx/security/crypto/package-summary
