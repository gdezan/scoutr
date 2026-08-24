---
status: ready
created: 2026-08-23
owner: implementation-agent
scope: multi-host-foundation
depends_on: []
blocks:
  - .plans/2026-08-23-unified-multi-host-experience.plan.md
---

# Multi-host foundation

## Goal

Replace Android's singleton bridge connection with a host registry and carry the bridge `hostId` through every host-bound operation. This plan also updates the push protocol so identical pane ids on two bridges cannot collide.

This is the compatibility layer for the later unified Board and Sessions work. At the end of this plan, the app may still present one host at a time in most screens, but no API request, route, notification, reply, mute, terminal preference, update request, or persisted session flag may depend on an implicit global connection.

## Product decisions already made

- A bridge installation's identity is the opaque `hostId` already persisted in `bridge/src/config.ts` and returned by `GET /api/health`.
- A host profile has a local editable alias. Its initial alias comes from the paired URL. URLs and tokens may change without changing host identity.
- Pairing a known `hostId` refreshes its URL, token, and exposure metadata without duplicating it. Keep its alias and host-qualified preferences.
- If saved credentials reach a different `hostId`, do not silently replace the old identity. The caller must choose Replace or Add as new. Replacing keeps the alias; pin/archive migration requires a separate confirmation.
- The default host and update host are separate persistent choices. The default host targets hostless session actions. Only the update host may check, build, or download an APK.
- Changing the update host requires a signing-key warning. Removing it requires a replacement or explicit update disablement unless it is the final host.
- Forgetting a host tries to unregister the phone from that bridge but always finishes locally. It removes credentials, caches, notifications, mutes, and token-derived terminal state. It keeps the host alias and pin/archive flags.
- The app registers its current FCM token with every paired bridge. Every FCM data message carries `hostId`; messages from unknown or forgotten hosts are discarded.
- Pane and session identity are `(hostId, paneId)` and `(hostId, SessionKey)` respectively.
- Existing singleton installs migrate without returning the user to Connect. If the old record has no `hostId`, adopt one only after an authenticated health response.

## Existing code to preserve

- `bridge/src/config.ts` already creates and persists stable host ids.
- `bridge/src/routes/health.ts` already returns `config.hostId`, and `bridge/src/api-protocol.ts` advertises `host-identity.v1`. Add `host-identity.v1` and the new `push-profile-generation.v1` to Android's `REQUIRED_SCOUTR_API_FEATURES`; multi-host code must never accept a protocol-compatible response that omits stable identity or generation-qualified push. Pairing rejects it, pending migration keeps waiting, and registered-host probes classify it as incompatible before fetching or merging data.
- `bridge/src/push/devices.ts` already makes registration idempotent and supports `unregister(token)` internally.
- `android/app/src/main/java/dev/scoutr/app/data/SessionKeyCodec.kt` already defines `HostSessionKey` and the `hsk1` codec.
- `SharedPreferencesSessionCatalogStore` already hides flags belonging to other hosts instead of deleting them. Refactor its public API; do not discard its migration behavior.
- Keep the terminal architecture in `docs/terminal.md`: terminal output stays in Terminal and terminal transport stays off `ScoutrApi`/`HerdrPort`.

## Resulting ownership model

### Host registry

Add `android/app/src/main/java/dev/scoutr/app/data/HostRegistry.kt` and `HostRegistryStore.kt`.

Use these durable records:

```kotlin
@Serializable
data class HostProfile(
    val hostId: String,
    val alias: String,
    val baseUrl: String,
    val exposure: ExposureKind,
    val profileGeneration: Long,
    val connectionRevision: Long,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
)

data class HostRegistryState(
    val profiles: List<HostProfile>,
    val defaultHostId: String?,
    val updateHostId: String?,
    val inAppUpdatesEnabled: Boolean,
    val pendingLegacyConnection: Boolean,
    val pendingLegacyMetadataHostId: String?,
    val pendingCleanupHostIds: Set<String>,
    val nextProfileGeneration: Long,
    val nextConnectionRevision: Long,
    val legacyLinkGeneration: Long?,
)
```

`HostProfile` must not expose the bearer token. `HostRegistryStore.credentials(hostId)` returns a `HostCredentials` value only to host-bound transport code.

Make `ExposureKind` serializable with explicit `@SerialName("tailscale")`, `@SerialName("cloudflare")`, and `@SerialName("custom")` entries. Do not accept Kotlin enum names as the persisted format. Registry round-trip tests must prove the lowercase wire spellings stay compatible with `ConnectionStore` and pairing payloads.

Store profile metadata, encrypted token ciphertext/IV pairs, remembered aliases, default id, update id, and update-enabled state in one SharedPreferences file. Encrypt every token with the existing `ConnectionCipher`; use one encrypted value per host. Removing one profile deletes its ciphertext. Delete the Keystore key only when the final profile and pending legacy record are gone.

Persist a pending migration as an internal `PendingLegacyRecord(baseUrl, exposure, encryptedToken, createdAtMs)`, not only the public Boolean. `HostRegistryStore` exposes `pendingCredentials()` to the migration coordinator, `stageLegacyConnection(...)` to write the encrypted pending record before legacy cleanup, and `promotePending(reportedHostId)` to atomically add the first profile/default/update ownership, allocate both epochs, set `legacyLinkGeneration` and `pendingLegacyMetadataHostId`, and remove the pending record in one preferences edit. These APIs must survive process restart without exposing the token through `HostRegistryState`.

`HostRegistryStore` owns all invariant-preserving mutations:

- add a probed host;
- refresh credentials for the same `hostId`;
- rename and remember the alias;
- mark a host used;
- set the default host;
- set the update host after UI confirmation;
- disable updates;
- replace an identity with or without pin/archive migration;
- forget with an explicit update-host disposition.

Do not expose a writable profile list. Enforce unique nonblank `hostId` values inside every mutation; add-or-refresh is keyed by host id and duplicate-producing Replace/Add calls fail without partial writes. Reject removal of the current update host when other profiles remain unless the call supplies a replacement id or disables updates. When the default host is removed, choose the remaining profile with the greatest `lastUsedAtMs`. Keep an offline default selected; never switch merely because a probe fails.

Forget and identity replacement atomically remove the old profile and add its id to `pendingCleanupHostIds`; successful cleanup removes the tombstone later. Never delete remembered alias or pin/archive data from this queue. `AppContainer` resumes pending cleanup on startup, so a crash after credential removal cannot strand Session files, notifications, mutes, Review/launcher paths, or other transient host state. Reject or defer add/refresh of a `hostId` while its cleanup tombstone exists; cleanup must finish before re-pair can create new state for that id. The dependent plan adds its snapshot cleanup to this same coordinator.

Normalize URLs in one helper before persistence. Derive the initial alias from URI host plus a non-default port. If a remembered alias exists for the probed `hostId`, restore it.

Define `lastUsedAtMs` as explicit user targeting, not background activity. Central navigation/action dispatch marks a host used when the user sets it as default, opens a host-bound Chat, Review, Files, Usage, or Terminal destination, confirms a hostless target, or starts a remote session action such as create/resume/revive. Record the attempt even if that selected host is offline. Health checks, polling, push handling, update checks, and FCM registration never change recency. New profiles start with `createdAtMs` as their initial use time.

Allocate two globally monotonic persisted epochs. `profileGeneration` changes only when a profile is first created after migration/pairing, so forget and same-id re-pair cannot revive stale external handles. `connectionRevision` changes on profile creation and whenever URL/token/exposure/identity changes, so in-flight network work cannot cross a credential binding. Neither counter resets or reuses a value after final-host forget. Async work captures `(hostId, connectionRevision)`; notifications, push registrations/messages, PendingIntents, deep links, and routes capture `(hostId, profileGeneration)`. Alias/default/update changes allocate neither value. Clear `legacyLinkGeneration` when its migration profile is forgotten or replaced; ordinary pairing never recreates it.

### Singleton migration

Keep `ConnectionStore.kt` only as a legacy reader during this plan. No ViewModel or transport may use it after migration. Add a legacy-only preference cleanup method that removes the old ciphertext/IV and metadata without calling `ConnectionCipher.clearKey()`; the existing `ConnectionStore.clear()` is not safe for transfer because it deletes the shared Keystore key.

Add an application-scoped `HostMigrationCoordinator` with a `StateFlow<LegacyMigrationState>` (`None`, `Pending`, `Probing`, and `WaitingToRetry(message)`). `AppContainer` creates and starts it before UI push registration. The durable source of truth remains the pending legacy record in `HostRegistryStore`; `Probing` is process-local.

At app-container creation:

1. Inspect the registry first. If it already has a profile or pending record, do not import the singleton again. If stale legacy preference fields remain from a crash after a durable registry write, clear them with the legacy-only cleanup method, then continue the pending probe or metadata-adoption work below.
2. Only when the registry is empty, read the old singleton record.
3. If it has a nonblank `hostId`, create the first profile in one registry write. Make it both default and update host, enable updates, allocate both epochs, derive its alias, and set `legacyLinkGeneration` plus `pendingLegacyMetadataHostId`. Then clear the old fields.
4. If `hostId` is absent, call `stageLegacyConnection` to persist URL, exposure, and freshly encrypted token, then clear the old fields. `HostMigrationCoordinator` obtains those credentials through `pendingCredentials()` and probes `/api/health`. Do not show Connect just because the bridge is offline.
5. On the first authenticated, fully compatible health response with a nonblank `hostId` and both required `host-identity.v1` and `push-profile-generation.v1` features, call `promotePending`. A health response may identify the bridge even when herdr is disconnected.
6. Whenever `pendingLegacyMetadataHostId` is set, call an idempotent `SessionCatalogStore.adoptLegacyEntries(hostId)` that synchronously commits unqualified pin/archive ownership before clearing the marker in the registry. A crash before either commit safely reruns this step. `LegacyMigrationState` is not `None` until the marker is cleared. Never remove the only durable credential copy.

`ScoutrAppNav` treats either a profile or a pending legacy record as an existing installation and starts on Board. While migration is pending, Board renders its normal shell with a connection/probing state and no remote actions. The foundation version of `BoardViewModel` observes registry state, binds to the default host when the coordinator creates it, and starts its existing single-host refresh without recreating the activity. If no profile and no pending record exist, start on Connect.

An unauthorized response, network failure, missing `hostId`, or incompatible old health payload moves the coordinator to `WaitingToRetry` and leaves the pending record intact. Retry on app foreground and explicit Board retry. Add tests for process restart at each boundary and for the pending-to-profile route transition.
### Host-bound API and transport factories

Refactor `BridgeClient` so an instance can only resolve credentials for one `hostId`. Remove the optional `host` and `token` override from `ScoutrApi.health`; normal clients call `health()` like every other API method.

Add `HostClientFactory` in `android/app/src/main/java/dev/scoutr/app/net/`:

```kotlin
interface HostClientFactory {
    fun api(hostId: String): ScoutrApi // Identity-guarded; no raw registered-host client escapes.
    fun terminal(hostId: String): TerminalTransport
    fun topologyFeedFactory(hostId: String): TopologyFeed.Factory
    fun probe(host: String, token: String): ScoutrApi
}
```

Represent a request target as an immutable `HostConnectionBinding(hostId, connectionRevision, baseUrl, token, exposure)`. A registered-host facade resolves one current binding per operation; its health check and delegated request use that same snapshot. A probe client uses fixed form credentials and is never retained after pairing.

Make identity validation a transport gate, not a Board/Sessions convention. Add a per-host `HostConnectionCoordinator` that holds the binding lock across expected-identity health validation and dispatch with the same immutable binding. `api(hostId)` returns an `IdentityGuardedScoutrApi`; raw registered-host clients stay private. Credential refresh, identity replacement, and forget retire or replace a binding under that same lock, so no operation can validate old credentials and dispatch with new ones. The gate updates host status and fails closed on missing/mismatched identity for FCM details, foreground reconciliation, Chat, Review, Files, Usage, updates, device registration/unregistration, and session commands.

Topology and terminal transports validate and open with one captured binding under the coordinator, then validate again before every reconnect. Credential refresh or retirement closes sockets for the old revision before replacing/removing the binding; reconnect resolves the new one. FCM processing validates before detail fetch; the update coordinator validates before status/build/download. A rejected operation exposes `Incompatible` or `IdentityChanged` and never silently retries against another profile. Pairing probes are the only raw API path and already validate identity before registration.

Add an application-scoped `HostWorkCoordinator` keyed by the non-reusable `(hostId, connectionRevision)` binding. Every host-bound ViewModel, repository worker, receiver, push job, and transport registers its cancellation/join handle and checks `isActive(binding)` immediately before every state/cache/notification write. `retire(binding)` synchronously rejects new work, cancels and joins registered work, and closes old sockets before forget, credential replacement, or identity replacement proceeds. If the profile remains after credential refresh, its screens observe the new revision and restart against that binding. The dependent Board, Sessions, status, search, and launcher workers use this coordinator rather than coroutine cancellation alone.

Route credential refresh, identity replacement, and forget through one `HostLifecycleCoordinator`; callers may not invoke the underlying registry mutations directly. Every path validates its disposition and retires the old binding first. Refresh then commits the new revision and lets the push observer register it. Forget/replacement performs push's final unregister while old credentials exist, commits the registry mutation plus cleanup tombstone, and drains local cleanup. On crash, `pendingCleanupHostIds` resumes the local cleanup portion. A cleanup pass runs again after worker quiescence/profile removal, so cancellation-resistant work cannot repopulate cleared state.

Refactor:

- `BridgeClient.kt` to execute against an immutable `HostConnectionBinding` supplied by the coordinator, never a mutable global store;
- `TopologyFeedClient.kt` to obtain an identity-verified immutable binding for each open/reconnect;
- `TerminalSocketClient.kt` behind a host-bound wrapper so `TerminalOpenRequest` no longer carries arbitrary host/token values;
- `TerminalViewModel.kt` to receive host-bound API, terminal transport, and feed factory without `ConnectionStore`;
- `ScoutrApp.AppContainer` to expose `hostRegistry`, `hostClients`, push coordination, and host-scoped stores instead of `container.bridge`.

Keep one shared `OkHttpClient` and `PerformanceCounters`; clients are ownership boundaries, not new connection pools.

### Host-qualified local identity

Add `HostProfileKey(hostId, profileGeneration)` and `HostPaneKey(profile: HostProfileKey, paneId)` beside `HostSessionKey` in `SessionKeyCodec.kt`, with encoded forms suitable for routes, Android intent extras, notification tags, and transient SavedState. `HostSessionKey` remains generation-free because retained pin/archive flags deliberately survive forget and same-id re-pair.

Refactor `SessionCatalogStore` to take `HostSessionKey` values directly. Remove its `currentHostId` callback. Add explicit operations to:

- adopt old `sk1` and unqualified legacy paths into the first host after migration;
- copy pin/archive membership from one host id to another only after identity-replacement confirmation;
- keep entries on forget.

Refactor `MuteStore`, `TerminalPreferencesStore`, and `ReviewStore` to take a host id in every host-derived key. Key `ReviewStore.lastRepoPath` by host so host B never reuses host A's repository path; migrate the one legacy path to the first host, then clear that host's path on forget or identity replacement. Terminal display choices that are truly device-wide may stay global; selected pane, ownership, or any token/connection-derived value must be host scoped and clearable by host.

Busy/action state in ViewModels must use `HostSessionKey` or `HostPaneKey`, never plain pane id or `SessionKey`, once a list can contain hosted values.

### Host-qualified navigation

Update `AppRoutes.kt`, `Destination.kt`, `ScoutrAppNav.kt`, and all graph files under `ui/nav/`.

Board and Sessions remain hostless destinations because the next plan merges them. Every other remote destination carries current `HostProfileKey` plus its existing arguments:

- Chat carries host id plus canonical session key or bootstrap pane id.
- Review carries host id plus repository path when entered from a session.
- Files carries host id through browser and viewer routes.
- Usage carries host id.
- Terminal carries host id plus optional pane id.

Include host id and profile generation in ViewModel keys and SavedState ownership keys. Every callback that opens Chat, Review, Files, Usage, or Terminal must pass the source `HostProfileKey`. Before resolving a route or external action, require it to equal the registry's current generation for that host; stale generations show "Host forgotten" and never bind to a re-paired profile. A session-originated action never consults the current default.

Until the next plan adds selectors, tapping a hostless shell entry for Usage, Review, Terminal, New Session, or the command palette resolves the persistent default host once and builds a concrete host route. Show a clear unavailable state if there is no profile or the selected profile is offline. Do not fall back to another host.

Keep compatibility for already posted pre-migration notification links only when exactly one profile exists and its `profileGeneration` equals `legacyLinkGeneration`: resolve a link with no host/generation to that profile. Newly built links always require host id plus profile generation. Forget/replacement clears the marker, so an unversioned legacy link cannot revive after re-pair.

### Push wire contract

For updated registrations, change bridge push data from `{ kind, paneId }` to `{ kind, hostId, profileGeneration, paneId }`. The generation is the opaque decimal string supplied by that Android registration; it has meaning only to that device. Preserve the two-field legacy path described below for older registered app versions.

- Change `DeviceRegistry` entries from bare tokens to token plus optional `profileGeneration`; registering the same token/value is idempotent and registering the token with a new generation replaces the old value. Decode existing bare-token files as generation-null records.
- Extend `FcmSender.send` in `bridge/src/push/fcm.ts` with `hostId` and optional registration `profileGeneration`. `FcmPublisher` iterates registration records, not bare tokens, so two devices may receive different local generations for one event. Send the four-field payload to generation records; keep the old `{ kind, paneId }` payload only for null-generation registrations from older app versions.
- Pass `config.hostId` into `FcmPublisher` from `bridge/src/cli.ts`.
- Assert the exact two-field legacy and four-field generation-qualified data payloads; neither includes a notification body or agent metadata.
- Keep delivery priority, TTL, edge deduplication, and stale-token pruning unchanged.

Extend authenticated `POST /api/devices` to accept an optional positive decimal-string `profileGeneration`, and advertise `push-profile-generation.v1`. Updated Android always sends its current generation. A missing value remains a legacy registration so older released apps keep receiving their two-field payload; startup registration by the updated app replaces the same token with a generation-qualified record.

Add authenticated `POST /api/devices/unregister` in `bridge/src/routes/devices.ts`. It accepts the same validated `fcmToken` body as registration and calls `DeviceRegistry.unregister`. Return success when the token is already absent. Wire and document the method in the Android `ScoutrApi` contract. Keeping this a POST avoids widening the route method/body parser solely for one command endpoint.

Update `bridge/test/push-fcm.test.ts`, `push-publisher.test.ts`, `devices.test.ts`, `routes.test.ts`, and server fixtures. Do not create a second device registry per host; each bridge process already owns its own registry.

### Android push coordinator and notifications

Add a small `PushRegistrationManager` and persistent `FcmTokenStore`.

- `PushRegistrationManager` records Firebase's current token, observes registry profile additions for its full application lifetime, and registers `(token, profileGeneration)` with each newly available profile. This covers profiles created after a pending singleton migration completes and replaces any legacy bare-token registration.
- App startup and `ScoutrMessagingService.onNewToken` update `FcmTokenStore`; the manager registers with profiles in independent coroutines, so one failure never stops the others.
- Pairing a host needs no separate one-shot callback beyond the registry observer, though an explicit immediate registration may share the same idempotent method.
- Forgetting calls `PushRegistrationManager.retire(hostId)` while credentials still exist. `retire` marks the host ineligible for observer launches, cancels and joins its active registration/detail/reconciliation jobs, waits behind any in-flight registration on the host connection lock, then attempts unregister with the stored token. Only after that settlement may the forget coordinator remove the profile; unregister failure is logged and local deletion continues. This ordering guarantees a registration cannot complete after the final unregister attempt.

`ScoutrMessagingService.onMessageReceived` must parse nonblank `hostId` and positive `profileGeneration` first, then require an exact current `HostProfileKey` match before network or notification work. Missing, unknown, forgotten, or stale-generation pings return immediately. Capture that profile's `connectionRevision`, fetch through `hostClients.api(hostId)`, then recheck profile key, revision, and non-retiring state immediately before posting or reconciling a notification. Discard delayed results after forget, credential refresh, identity replacement, or same-id re-pair.

Refactor `FcmPingHandler`, `NotificationPresenter`, `NotificationReplyReceiver`, `NotificationMuteReceiver`, `DeepLink.kt`, and `MainActivity` around `HostPaneKey`.

Use `NotificationManager.notify(tag, id, notification)` where the tag encodes `(hostId, profileGeneration, paneId)` and the id distinguishes blocked from done. PendingIntent data and request identity include host id, profile generation, pane id, and action kind. Foreground reconciliation clears stale notifications for one current profile generation without touching another host; forget clears every notification tag with that host-id prefix and its mute entries.

Replies and mute actions require their `HostProfileKey` to match the current profile before acting. If it was forgotten/re-paired between post and tap, cancel the stale notification and do nothing else.

### Pairing and identity changes

Refactor `ConnectViewModel` around the probe client and a typed outcome rather than saving directly:

```kotlin
sealed interface PairingOutcome {
    data class Added(val hostId: String) : PairingOutcome
    data class Refreshed(val hostId: String) : PairingOutcome
    data class IdentityChanged(
        val previousHostId: String,
        val reportedHostId: String,
        val reportedHostAlreadyPaired: Boolean,
    ) : PairingOutcome
}
```

First launch still uses Connect. A successful first pairing becomes default and update host. Later Add Host UI belongs to the dependent plan, but the registry and ViewModel operations must already support add and refresh.

When refreshing a specific profile, compare the authenticated health `hostId` with the profile id. On mismatch, retain the old profile and return `IdentityChanged`. Provide separate commands for:

- Replace: remove old credentials/caches, add the reported id, copy the alias, and optionally copy pin/archive flags after explicit confirmation.
- Add as new: keep the old profile and add the reported id with its own URL-derived or remembered alias.

Replace is one atomic registry mutation. If the old id is default, transfer default ownership to the new id. If it is the update host, require an explicit update disposition before mutation: use the new id after the signing-key warning, choose another existing id, or disable updates. Do not apply the ordinary most-recently-used fallback inside Replace. Preserve the old profile's alias and `lastUsedAtMs` on the replacement; allocate new non-reusable profile and connection epochs.

Enforce one profile per `hostId` in every registry mutation. Ordinary pairing of an already paired id is credential refresh, never Add. If an identity mismatch reports an id owned by another profile, set `reportedHostAlreadyPaired`; reject both Replace and Add-as-new because either would duplicate identity. Offer Refresh existing profile or Cancel while leaving the source profile unchanged.
Never migrate Board/Session snapshots, notification state, mutes, or terminal ownership across an identity replacement.

### Update-host boundary

Stop passing an arbitrary `ScoutrApi` into `UpdateSection`. Resolve one only from `HostRegistryState.updateHostId` when updates are enabled. All `updateStatus`, `updateBuild`, `updateApkStatus`, and APK download calls in one run use that host-bound API.

The dependent plan builds the full Hosts UI. In this plan, preserve the current Update section for the migrated/default update host and show "In-app updates disabled" when there is none. Put mutation rules and confirmation-ready methods in the registry now so UI cannot bypass them later.

## Implementation order

1. Update the bridge FCM payload and add authenticated device unregistration with focused bridge tests.
2. Add `HostProfile`, `HostRegistryStore`, alias retention, encrypted per-host credentials, default/update invariants, and singleton migration tests.
3. Refactor `SessionCatalogStore`, `MuteStore`, `TerminalPreferencesStore`, and `ReviewStore` to explicit host-qualified keys.
4. Introduce `HostClientFactory` and `HostConnectionCoordinator`; remove `ConnectionStore` from `BridgeClient`, topology feed, Terminal, AppContainer, and ViewModels.
5. Qualify routes, navigation callbacks, ViewModel keys, and SavedState with host id.
6. Add push registration coordination, host-qualified notifications/deep links/replies/mutes, and unknown-host rejection.
7. Refactor pairing outcomes and bind self-update to the update host.
8. Run the simplify pass, repository review, and final acceptance below.

Keep the app compiling and the migrated single-host flow working after steps 2 through 7. Do not wait until the final step to reconnect all constructor call sites.

## Tests

Add or update JVM tests for:

- registry round trips with two encrypted tokens, lowercase `ExposureKind` wire values, and monotonic non-reusable profile/connection epochs;
- compatibility rejection when `host-identity.v1`, `push-profile-generation.v1`, or a nonblank health `hostId` is absent;
- same-id credential refresh preserving alias and preferences;
- identity mismatch producing no mutation until Replace/Add is selected, an already-owned reported id rejecting duplicate-producing choices, and replacement atomically transferring default plus the confirmed update disposition;
- default fallback by `lastUsedAtMs` only on removal, with explicit user targeting updating recency and background work leaving it unchanged;
- update-host removal rejection, replacement, final-host clearing, and explicit disablement;
- old singleton migration with and without stored host id, including encrypted pending-record restart, atomic promotion, stale legacy-field cleanup, crash-safe metadata-adoption marker recovery, failed/restarted probe, pending Board routing, and pending-to-profile transition;
- a token received before migration completion registering with the profile created later by the registry observer;
- alias restoration and pin/archive retention after forget and re-pair;
- explicit pin/archive migration during identity replacement;
- host-qualified Review repository paths, first-host legacy adoption, and clear-on-forget/replacement;
- fixed host clients never reading another profile after default changes;
- one immutable binding being held across identity check and HTTP/socket dispatch, with refresh/retirement unable to create a check/use race;
- identity gate blocking every registered-host HTTP delegate and socket open/reconnect on missing/mismatched identity, including FCM details and update operations;
- refreshed credentials being observed by an existing host-bound client;
- globally non-reused connection revisions discarding delayed network work and profile generations rejecting delayed push/routes/actions after forget and same-id re-pair;
- lifecycle retirement draining registered work, crash-resumable cleanup tombstones clearing transient state only after profile removal, and re-pair waiting for same-id cleanup;
- route encode/decode and stale-resolution tests for profile generation plus host/session/pane/file arguments;
- same pane id on two hosts or two generations of one host producing separate notification, deep-link, PendingIntent, and reply identities;
- unknown/missing host or missing/stale profile-generation push rejection before API fetch;
- bridge legacy registry migration/two-field delivery plus per-token generation replacement/four-field delivery;
- per-host notification reconciliation and forget cleanup;
- token registration with all hosts despite one failure;
- forget draining registration before final unregister and discarding delayed FCM detail/reconciliation results after revision or membership changes;
- update calls using only the update host.

Extend existing tests rather than cloning them where practical: `ConnectionStoreTest`, `SessionCatalogStoreTest`, `TerminalPreferencesStoreTest`, `MuteStoreTest`, `AppRoutesTest`, `DestinationTest`, `ShellRouteTest`, `ScoutrMessagingServiceTest`, `NotificationPresenterTest`, receiver tests, `DeepLinkValidationTest`, `ConnectViewModelTest`, `TopologyFeedClientTest`, `TerminalViewModelTest`, and `AppUpdaterTest`.

## Verification

During implementation, use the narrowest tests for the changed module. The main cheap gates after code settles are:

```bash
make bridge-test
make android-test
```

Before final runtime work, run `skills/scoutr-review/SKILL.md`, resolve or dismiss every finding, and freeze the code. Then use `skills/scoutr-verification/SKILL.md` to select final acceptance. This plan changes the bridge/Android contract and notification routing, so final acceptance must include an emulator and two independently identified bridge instances or deterministic scratch-bridge equivalents. Prove:

1. a singleton install migrates and opens its previous Board without Connect;
2. the same pane id from two hosts posts independent notifications and routes replies to the right bridge;
3. unknown/forgotten-host and stale-generation pings, routes, and notification actions do nothing, including after same-id re-pair;
4. a default-host change does not change an open Chat/Files/Terminal destination;
5. update requests reach only the configured update host;
6. forgetting one host removes its sensitive/transient state without removing retained alias or pin/archive flags.

Deploy finalized bridge work with the recipe in `docs/dev-workflow.md` before testing the live app. Kill `emulator-5554` when acceptance is complete.

## Done when

- `container.bridge` and runtime `ConnectionStore.saved` reads are gone outside the legacy migration adapter.
- Every host-bound route/external handle includes current `HostProfileKey`; durable retained metadata remains keyed by `hostId`.
- Push messages, notifications, replies, mutes, and deep links distinguish identical pane ids on different hosts and reject stale profile generations.
- Default and update host invariants live in `HostRegistryStore`, not UI conditionals.
- Existing single-host users migrate without setup interruption.
- The dependent unified-experience plan can request `api(hostId)`, enumerate profile state, and build hosted Board/Session models without further connection-layer redesign.
