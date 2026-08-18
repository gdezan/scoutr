# Host Identity and Workspace Metadata Blueprint

## Current situation

Scoutr is still intentionally single-host at the product layer, but two internal assumptions make later multi-host support and current workspace reuse more fragile than necessary.

First, the Android pairing store in `android/app/src/main/java/dev/scoutr/app/data/ConnectionStore.kt` persists a bridge URL/token and ntfy configuration but no durable bridge identity. The P1 Canonical Session Model blueprint introduces `SessionKey { agentKind, path }`, which is correctly bridge-local, and migrates pin/archive metadata to that key. Without a host namespace, the same device cannot safely retain local metadata for two bridges that happen to expose the same backend/path pair.

Second, `bridge/src/sessions.ts` → `findWorkspaceForCwd` has to infer a Herdr workspace's project root from the first/root pane because the Herdr snapshot carries no workspace cwd. That inference is explicitly documented as the only currently available grounding. It is vulnerable to the root pane changing cwd and provides no durable statement of which project Scoutr intended a workspace to represent.

The bridge already has the right persistence home: `bridge/src/config.ts` loads `~/.config/scoutr/config.json`, preserves existing credentials, and exposes `configDir` for sibling bridge-owned state. `/api/health` is already the pairing/compatibility handshake. Android device-local session metadata already has a store seam, and after `.plans/p1-session-model-v3.md` it is expected to use canonical `SessionKey` rather than raw paths.

Precedent: bridge-owned configuration persists identity/security material, while Android treats the health response as authoritative pairing metadata. Workspace reuse already belongs to `sessions.ts`; this plan strengthens that seam rather than introducing a second workspace manager.

## Objective and why

Establish two durable namespaces before multi-host work expands the surface:

1. give every bridge installation a stable opaque `hostId` exposed by the health handshake and persisted with the pairing;
2. persist Scoutr's intended `workspaceId → canonical project cwd` mapping so workspace reuse does not depend solely on live root-pane cwd inference.

Done means Android can qualify bridge-local session metadata with a stable host identity, and Scoutr-created/reused Herdr workspaces can be matched to their project roots from bridge-owned metadata while validating every persisted mapping against the current Herdr snapshot.

## Scope

Included:

- stable bridge `hostId` generated and persisted in bridge config;
- health/API exposure and Android pairing persistence of `hostId`;
- a host-qualified Android session preference identity built on the P1 `SessionKey`;
- migration of P1 session-key-only device metadata to the current `hostId` namespace;
- bridge-owned persisted workspace metadata under `configDir`;
- workspace reuse that prefers validated persisted metadata and falls back to current snapshot inference for workspaces Scoutr has never recorded;
- pruning/healing stale workspace metadata;
- tests for config migration, host qualification, workspace reuse, stale entries, and changed pane cwd.

Non-goals:

- no multi-host picker, connection list, or simultaneous bridge connections;
- no database;
- no attempt to persist live pane/tab ids as session identity;
- no ownership claim over arbitrary user-created Herdr workspaces beyond recording a mapping after Scoutr deliberately reuses one;
- no replacement of Herdr as the runtime source of truth for whether a workspace currently exists;
- no changes to agent launch semantics other than how the target workspace is selected.

## Global constraints

- Implement after `.plans/p1-session-model-v3.md`; do not reintroduce raw-path session identity.
- `hostId` is opaque installation identity, not hostname, public URL, token, machine hostname, or user-visible security material.
- Rotating a token or changing `publicHost` must not change `hostId`.
- Deleting/recreating the bridge config may create a new host identity; that is an intentional new installation boundary.
- Persisted workspace metadata is advisory indexing; the live Herdr snapshot remains authoritative for workspace existence.
- Workspace paths are canonicalized through existing path helpers before comparison or persistence.
- Android remains manual-DI and one active saved connection for this blueprint.
- Final emulator/integration/E2E verification is terminal after review-clean/code-freeze.

## Resolved decisions

### Stable bridge identity

Extend `BridgeConfig` with a required runtime `hostId` persisted into `config.json`:

```ts
interface BridgeConfig {
  // existing fields...
  hostId: string;
}
```

On loading an older valid config with no `hostId`, mint one and persist it without changing the token, port, ntfy topic, or other existing settings. Use a random opaque identifier with a clear Scoutr prefix; do not derive it from URL, token, cwd, hostname, or Tailscale name.

Expose it through `/api/health`, e.g.:

```json
{
  "ok": true,
  "service": "scoutr-bridge",
  "hostId": "host_..."
}
```

If the P0 protocol feature contract is present, advertise a stable feature such as `host_identity`; do not raise the protocol version solely for adding an ignored-safe response field unless the established protocol rules require it.

### Android host-qualified local identity

After P1, the persisted coding-agent identity remains:

```kotlin
data class SessionKey(val agentKind: String, val path: String)
```

Device-local metadata uses a host-qualified wrapper:

```kotlin
data class HostSessionKey(
    val hostId: String,
    val session: SessionKey,
)
```

Do not put `hostId` inside the bridge's `SessionKey`; the bridge already knows which host it is. Qualification is needed at the Android/device boundary where data from multiple bridge installations may eventually coexist.

`ConnectionStore.Saved` persists `hostId` from a successful health handshake. A new app build must not silently bind existing session metadata to a bridge merely because the URL string matches; the migration occurs after a successful authenticated handshake reveals the bridge's actual identity.

### Workspace metadata

Create a small bridge-owned JSON store under `configDir`, for example `workspace-roots.json`:

```ts
interface WorkspaceRootRecord {
  workspaceId: string;
  cwd: string;       // canonical absolute project path
  updatedAtMs: number;
}
```

The store records only mappings Scoutr has observed and intentionally selected/created. It does not recreate workspaces and is not a source of runtime liveness.

Workspace lookup order:

1. take one current Herdr snapshot;
2. prune persisted records whose `workspaceId` is absent from that snapshot;
3. among live persisted records, find a record whose canonical `cwd` matches the requested cwd; prefer the lowest Herdr workspace number if duplicates exist;
4. if none matches, use the existing root-pane inference over the same snapshot;
5. when that fallback selects a workspace, record the mapping because Scoutr has now deliberately reused it;
6. when Scoutr creates a workspace, record its returned id/cwd immediately after creation succeeds;
7. when Scoutr closes/rolls back a workspace it created, remove that record.

A recorded workspace's root pane later `cd`-ing elsewhere must not change its recorded project root. Explicit future "change workspace root" behavior would be a separate operation.

### Persistence behavior

Workspace metadata should use a tiny bridge helper with serialized writes and atomic replace (`write temp → rename`) or an equivalently crash-safe existing repository precedent. Malformed/unreadable workspace metadata must degrade to an empty registry, never prevent the bridge from launching a session.

### Rejected alternatives

- Namespace by `saved.host`: rejected because URLs/aliases can change while the bridge installation remains the same.
- Namespace by token: rejected because token rotation must not orphan local metadata and tokens are security material, not identity.
- Treat persisted workspace metadata as authoritative without a snapshot check: rejected because Herdr workspace ids are runtime resources and stale records must not resurrect dead state.
- Persist every Herdr workspace automatically: rejected because Scoutr should only state roots it has actually selected/created, not infer ownership of unrelated user workspaces.

## Approach

Extend the existing bridge config migration to create stable installation identity, then expose/persist it through the current health pairing path. Add a small workspace-root registry owned by the bridge's session-launch layer and make `openLaunchTarget` use a single snapshot plus that registry to select a workspace.

On Android, qualify only device-persisted session preferences with `hostId`. Keep bridge API session descriptors bridge-local. This creates the multi-host namespace seam without prematurely building multi-host UI or changing every API type.

## Contracts and interfaces

### Bridge identity

```ts
export interface BridgeConfig {
  configDir: string;
  hostId: string;
  token: string;
  // ...
}
```

`health()` exposes the same `hostId` on every restart for one config installation.

### Workspace registry

A module such as `bridge/src/workspace-roots.ts` may own:

```ts
export interface WorkspaceRootRecord {
  workspaceId: string;
  cwd: string;
  updatedAtMs: number;
}

export interface WorkspaceRootStore {
  list(): Promise<WorkspaceRootRecord[]>;
  record(workspaceId: string, cwd: string): Promise<void>;
  remove(workspaceId: string): Promise<void>;
  prune(liveWorkspaceIds: ReadonlySet<string>): Promise<void>;
}
```

The exact class/function shape is local discretion; the ownership and invariants are not.

### Android namespace

```kotlin
data class HostSessionKey(val hostId: String, val session: SessionKey)
```

The session preference store accepts/returns host-qualified keys after this change.

### Cross-change interface table

| Change | Consumes | Produces |
|---|---|---|
| 1 | existing bridge config migration | stable `BridgeConfig.hostId` |
| 2 | `hostId` | health handshake + persisted `ConnectionStore.Saved.hostId` |
| 3 | P1 `SessionKey` + saved `hostId` | `HostSessionKey` local metadata namespace |
| 4 | `configDir` + canonical cwd | persisted workspace-root store |
| 5 | workspace store + Herdr snapshot | metadata-first `openLaunchTarget` selection |

## Changes

- [ ] **1 — Add stable bridge installation identity**
  - Anchor: `bridge/src/config.ts` → `BridgeConfig`, `loadOrCreateConfig`
  - Add `hostId` generation/validation.
  - Migrate an existing valid config missing `hostId` by adding only that field and preserving all existing pairing/security values.
  - A malformed `hostId` in an otherwise readable config is a config-field migration problem, not a reason to silently mint a new token.
  - Proof: config tests show old configs gain one stable host id, repeated loads retain it, and token/publicHost changes do not alter it.

- [ ] **2 — Carry `hostId` through pairing**
  - Anchor: `bridge/src/routes/health.ts` → `health`
  - Anchor: `android/app/src/main/java/dev/scoutr/app/data/ConnectionStore.kt` → `Saved`, `save`
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/ConnectViewModel.kt` → successful health handshake
  - Add the host id to health DTOs and persist it only after a successful authenticated/Herdr-connected handshake.
  - Existing pairing replacement must overwrite the saved namespace with the newly authenticated bridge identity.
  - Proof: bridge health tests and Android connection tests demonstrate stable persistence and replacement when pairing to a different host id.

- [ ] **3 — Namespace device-local session metadata by host**
  - Anchor: P1 result in `android/app/src/main/java/dev/scoutr/app/data/SessionCatalogStore.kt` → canonical-key pin/archive membership
  - Introduce `HostSessionKey` (or equivalent value object) for device persistence.
  - Migrate the P1 session-key-only preference values into the authenticated current `hostId` namespace once, after the first successful health handshake on the upgraded app.
  - Do not change bridge wire `SessionKey` merely to carry Android namespace state.
  - Keep migration idempotent and preserve unresolved legacy values until they can be safely qualified rather than dropping them.
  - Proof: store tests show identical `(agentKind,path)` values under two host ids do not collide and current-host P1 values survive migration.

- [ ] **4 — Add crash-safe bridge workspace-root metadata**
  - Anchor: `bridge/src/config.ts` → `BridgeConfig.configDir`
  - Anchor: `bridge/src/sessions.ts` → `findWorkspaceForCwd`, `openLaunchTarget`
  - Add a bounded JSON registry under `configDir` containing only workspace id, canonical cwd, and update time.
  - Reads tolerate missing/malformed files as an empty registry and expose enough observability for a warning without making launches fail.
  - Writes are serialized and crash-safe; cap/prune the file so stale records cannot grow forever.
  - Proof: focused tests cover empty, persisted, malformed, concurrent-write, and prune behavior.

- [ ] **5 — Prefer validated workspace metadata over root-pane cwd inference**
  - Anchor: `bridge/src/sessions.ts` → `findWorkspaceForCwd`, `openLaunchTarget`, launch rollback helpers
  - Refactor selection to take/reuse one snapshot for metadata validation and fallback inference.
  - Select only workspace ids present in the current snapshot.
  - Record mappings when Scoutr creates or deliberately reuses a workspace.
  - Remove mappings when a Scoutr-created workspace is rolled back/closed through this launch path.
  - Preserve the existing lowest-workspace-number tie-break when duplicates represent the same cwd.
  - Proof: tests show a recorded workspace is reused even after its root pane changes cwd, stale records are ignored/pruned, and an unrecorded existing workspace still works through the current inference fallback.

## Failure handling

- Existing config has no `hostId`: add one without rotating the token.
- Config cannot persist a newly required id after being read successfully: preserve the loaded pairing and surface a configuration persistence error rather than silently creating unstable identity on each restart.
- Health response has no host id when the upgraded app requires the feature: treat this as bridge/app incompatibility through the P0 protocol mechanism, not as `host = hostId` fallback.
- Workspace registry missing/corrupt: warn and behave as empty; live snapshot inference remains available.
- Persisted workspace id no longer exists: prune it and continue.
- Recorded cwd no longer exists: it cannot match a requested resolved cwd; do not recreate anything from the record.
- Duplicate recorded workspaces for one cwd: deterministic lowest Herdr workspace number wins.
- Android session metadata has no qualified host yet during migration: do not guess from URL/token alone.

## Validation

1. Focused bridge config/health/workspace registry/session-launch tests.
2. Focused Android `ConnectionStore` and session preference migration tests.
3. `make bridge-test`.
4. `make android-test`.
5. Independent review using `skills/scoutr-review/SKILL.md`, specifically checking credential preservation, path canonicalization, stale workspace behavior, and migration idempotency.
6. After review-clean/code-freeze, final runtime acceptance via `skills/scoutr-verification/SKILL.md`: restart the bridge and confirm stable pairing identity; launch two sessions in one project and confirm workspace reuse; change a workspace root pane cwd and confirm a later launch still uses the recorded project workspace; restart Scoutr and verify local session flags remain attached to the same host/session.

## Local discretion

- Exact random host-id encoding/prefix.
- Workspace registry filename and internal helper shape.
- Whether registry warnings use stderr or an existing logger, provided malformed state remains observable and non-fatal.
- Exact serialized Android `HostSessionKey` representation, provided it is versioned/parseable and does not use token material.

## Escalation triggers

- Herdr begins exposing a first-class immutable workspace root in its supported protocol before this is implemented; prefer that source over a new registry and revise the plan.
- Workspace ids are not stable for the lifetime assumed by one running Herdr instance, making persisted id validation meaningless.
- Multi-host UI/simultaneous connections are pulled into scope; that changes `ConnectionStore` from one active pairing to a collection and needs its own plan.
- A P1 implementation chose a durable identity incompatible with `(agentKind,path)` and the host qualification cannot wrap it cleanly.
- Correct migration would require binding historical data to a bridge without first authenticating and receiving its `hostId`.

## Review handoff

Trace two invariants independently:

1. bridge installation identity: old config → migrated config → restart → token/publicHost update must retain exactly one `hostId`;
2. project workspace identity: launch → record → pane cwd changes → next launch must reuse the same live workspace because the persisted root is validated against the snapshot, while a dead workspace id is never reused.

On Android, verify that local session metadata is namespaced by authenticated `hostId` and no token or URL string is used as identity.

Rerun `make bridge-test` and `make android-test`; perform runtime acceptance only after all review findings are resolved.

## Completion checklist

- [ ] Bridge config has stable persisted `hostId` with safe old-config migration.
- [ ] Health exposes `hostId` and Android persists it on successful pairing.
- [ ] Device-local session metadata is host-qualified on top of P1 `SessionKey`.
- [ ] Existing P1 preference values migrate idempotently after authenticated host identification.
- [ ] Bridge persists bounded workspace-root metadata under `configDir`.
- [ ] Workspace selection validates registry ids against one live snapshot.
- [ ] Root-pane cwd changes no longer break reuse of a recorded Scoutr workspace.
- [ ] Unrecorded workspaces still use the existing inference fallback and become recorded when reused.
- [ ] Stale/corrupt metadata degrades safely.
- [ ] Bridge and Android cheap suites pass.
- [ ] Independent review is clean.
- [ ] Final runtime acceptance passes once, last.

## References

- `AGENTS.md`
- `.plans/p0-api-protocol-compatibility.md`
- `.plans/p1-session-model-v3.md`
- `bridge/src/config.ts`
- `bridge/src/routes/health.ts`
- `bridge/src/sessions.ts`
- `android/app/src/main/java/dev/scoutr/app/data/ConnectionStore.kt`
- `android/app/src/main/java/dev/scoutr/app/data/SessionCatalogStore.kt`
- `android/app/src/main/java/dev/scoutr/app/state/ConnectViewModel.kt`
- `skills/scoutr-review/SKILL.md`
- `skills/scoutr-verification/SKILL.md`
