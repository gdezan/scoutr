# Android ↔ Bridge API Protocol Compatibility Blueprint

## Current situation

Scoutr versions the installed APK and host checkout, and `/api/health` reports the bridge service version plus Herdr's version/protocol, but there is no explicit version for the **Scoutr Android ↔ Scoutr bridge API** itself. `bridge/src/routes/health.ts` currently returns `{ ok, service, version, herdr, terminal, ntfy }`. Android mirrors that in `HealthResponse` in `android/app/src/main/java/dev/scoutr/app/data/Models.kt`, and `ConnectViewModel.connect` treats a reachable bridge with connected Herdr as compatible.

`BridgeClient` uses kotlinx serialization with `ignoreUnknownKeys = true`, which makes additive server fields safe, but required shape changes can still fail decoding in feature-specific calls. `BoardViewModel.loadBoard` intentionally suppresses non-I/O exceptions, so a persistent decode mismatch can look like a stale board rather than an explicit compatibility failure.

Precedent: Herdr capability/version gating is already explicit on the terminal surface; `/api/health` is the handshake used before saving a new connection; `Settings` already has an update surface. Repository verification policy is in `AGENTS.md`.

## Objective and why

Give Android one explicit compatibility handshake for the Scoutr API so an app/bridge mismatch fails early and actionably instead of surfacing as decode errors, silent stale state, or feature-specific crashes.

Done means every successful connection has a known Scoutr API protocol, Android rejects unsupported/missing protocols before persisting a new pairing, an already-paired app surfaces incompatibility distinctly from network disconnection, and future breaking API changes have one documented bump rule.

## Scope

Included:

- a bridge-owned integer API protocol constant;
- protocol/capability metadata on `/api/health`;
- Android supported protocol range and compatibility classification;
- Connect and already-paired Board behavior for incompatible bridges;
- tests and documentation for the bump contract.

Non-goals:

- no OpenAPI/code generation;
- no per-endpoint version prefixes;
- no broad DTO refactor;
- no automatic bridge deployment from Android;
- no compatibility shims for arbitrary old protocols.

Compatibility rule for this rollout:

- existing APKs ignore the new health fields;
- the new APK treats a bridge that does not advertise `api.protocol` as incompatible and tells the user to update/deploy the bridge;
- protocol `1` describes the API shape at implementation time; future additive backward-compatible fields do not require a bump, while a client-required semantic or shape break does.

## Global constraints

- Android remains Kotlin + Compose with manual DI; ViewModels consume `ScoutrApi`.
- Bridge routes remain under `bridge/src/routes/`; `/api/health` remains the connection handshake.
- Do not conflate Scoutr API protocol with Herdr protocol or app semantic version.
- User-visible errors follow `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`: inline, explicit, no spinner/skeleton workaround.
- Emulator/integration/E2E acceptance, if selected, is the final step after review-clean/code-freeze.

## Resolved decisions

- Add one integer `SCOUTR_API_PROTOCOL = 1` on the bridge and one Android supported range (`MIN_SCOUTR_API_PROTOCOL` / `MAX_SCOUTR_API_PROTOCOL`), initially both `1`.
- `/api/health` exposes a nested Scoutr API block rather than overloading the existing Herdr `protocol` field:

```json
{
  "api": {
    "protocol": 1,
    "features": ["terminal.v1", "asks.v2", "update.pull.v1"]
  }
}
```

- `features` is informational/capability metadata for future selective UI gating. Protocol compatibility is decided by the integer range, not by string feature guessing.
- Missing protocol is incompatible in the new client. This is deliberate: allowing a missing value to proceed recreates the silent-mismatch failure this work is meant to remove.
- New pairings are not saved when incompatible.
- For an already-saved pairing, keep the pairing but stop treating the Board as merely offline; expose a typed incompatible state with an action leading to Settings/Update guidance. Do not silently clear credentials.
- Protocol errors are separate from transient decode errors. After this handshake exists, feature decode failures should remain visible in diagnostics/tests, but this blueprint does not redesign every ViewModel's error taxonomy.

## Approach

Make compatibility part of the health handshake and centralize the Android decision in one pure function/value type. `ConnectViewModel` calls it before `ConnectionStore.save`. `BoardViewModel.connect` calls the same compatibility check on its health probe and stores a distinct compatibility failure in UI state; its polling loop must not overwrite that state as a generic disconnect until a subsequent health handshake confirms compatibility.

Document one bump rule beside the constants so the next protocol-changing feature has an obvious decision point.

## Contracts and interfaces

### Bridge health contract

Create a shared bridge constant, preferably in a small protocol module used by the health route:

```ts
export const SCOUTR_API_PROTOCOL = 1;
export const SCOUTR_API_FEATURES = [
  "terminal.v1",
  "asks.v2",
  "update.pull.v1",
] as const;
```

Health response adds:

```ts
api: {
  protocol: SCOUTR_API_PROTOCOL,
  features: [...SCOUTR_API_FEATURES],
}
```

### Android contract

`HealthResponse` gains:

```kotlin
val api: ScoutrApiInfo? = null

data class ScoutrApiInfo(
    val protocol: Int? = null,
    val features: List<String> = emptyList(),
)
```

Create a pure compatibility classifier, e.g.:

```kotlin
sealed interface ApiCompatibility {
    data object Compatible : ApiCompatibility
    data class Incompatible(val bridgeProtocol: Int?) : ApiCompatibility
}

fun apiCompatibility(info: ScoutrApiInfo?): ApiCompatibility
```

The exact type name is local discretion; the invariant is not: **no saved/new live session surface proceeds as compatible without a protocol in the supported range.**

### Cross-change interface table

| Change | Consumes | Produces |
|---|---|---|
| 1 | current health route | `api.protocol`, `api.features` |
| 2 | `HealthResponse.api` | pure Android compatibility result |
| 3 | compatibility result | Connect/Board compatible vs incompatible UI state |
| 4 | constants + behavior | protocol bump documentation/tests |

## Changes

- [ ] **1 — Add the bridge API protocol contract to health**
  - Anchor: `bridge/src/routes/health.ts` → `health`
  - Create a small shared protocol constants module rather than burying the integer inside the route.
  - Return `api.protocol` and `api.features` on every successful health response, including when Herdr itself is disconnected.
  - Add/extend bridge server tests around `/api/health` to assert exact protocol metadata.
  - Proof: `make bridge-test` passes and an authenticated `/api/health` response contains protocol `1`.

- [ ] **2 — Mirror and classify compatibility on Android**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/data/Models.kt` → `HealthResponse`
  - Add `ScoutrApiInfo`, supported range constants, and a pure classifier in an appropriate data/net module.
  - Add unit tests for missing, below-range, in-range, and above-range protocols.
  - Proof: focused JVM tests show only the supported range is compatible.

- [ ] **3 — Gate new and saved connections before feature traffic**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/ConnectViewModel.kt` → `connect`
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/BoardViewModel.kt` → `connect`, `loadBoard`
  - New pairing: classify health before `ConnectionStore.save`; incompatible bridges produce a specific failure message that names installed supported protocol and bridge protocol when known.
  - Saved pairing: represent incompatibility distinctly in `BoardUiState` rather than as a generic `connected=false` IOException. Stop/avoid normal board polling while known-incompatible; retrying the health handshake can recover after the bridge is updated.
  - Do not erase the saved host/token.
  - Proof: ViewModel tests cover incompatible new pairing, incompatible saved pairing, and recovery after the bridge protocol becomes supported.

- [ ] **4 — Surface actionable compatibility UI and document the bump rule**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/ui/screens/BoardScreen.kt` → disconnected/incompatible banner surface
  - Anchor: `README.md` and/or `docs/decisions.md` → configuration/versioning documentation
  - Show an inline incompatibility banner different from ordinary connectivity loss; copy should tell the user the Scoutr app and bridge versions/protocols do not match and direct them to update/deploy the counterpart rather than repeatedly reconnect.
  - Document: additive optional fields stay on the current protocol; removing/renaming required fields, changing required semantics, or requiring a new command/response behavior bumps the integer.
  - Proof: UI test asserts incompatibility copy/action and bridge/Android unit suites pass.

## Failure handling

- Missing `api` block: incompatible, not network failure.
- Protocol below/above supported range: incompatible, retaining pairing for later recovery.
- Herdr disconnected on a compatible bridge: preserve existing server/offline behavior; protocol compatibility and Herdr health are independent axes.
- Transient health IOException: preserve existing reconnect behavior.
- A bridge update that restores a supported protocol: retry health, clear incompatibility, resume normal polling.
- Never infer compatibility from semantic version strings.

## Validation

1. Focused bridge health-route tests.
2. Focused Android compatibility and Connect/Board ViewModel tests.
3. `make bridge-test`.
4. `make android-test`.
5. Independent review using `skills/scoutr-review/SKILL.md`.
6. After code freeze, use `skills/scoutr-verification/SKILL.md`; if user-visible runtime acceptance is selected, verify one incompatible fixture/state and one recovery path as the final step.

## Local discretion

- Exact module/file name for shared protocol constants on each side.
- Exact `features` strings beyond the three current major capabilities, provided they are documented and not used as a substitute for the integer compatibility check.
- Exact UI action destination (Settings vs update section) provided it leads to actionable update/deploy guidance.

## Escalation triggers

- A currently deployed supported app must intentionally work with a bridge that cannot advertise the protocol field; that is a compatibility decision, not a routine fallback.
- Implementation discovers an endpoint that must be independently versioned from the rest of the API.
- The protocol must negotiate bidirectionally per request rather than at health/connect time.
- The self-update workflow requires an automatic bridge migration/deployment to avoid a real dead-end.

## Review handoff

Verify the protocol is Scoutr-specific and not confused with Herdr protocol, missing protocol cannot silently pass, old APKs remain able to ignore the new server field, and a saved pairing can recover after the bridge is updated without being forgotten. Review all user-visible copy against the design system.

Rerun `make bridge-test` and `make android-test` after review fixes.

## Completion checklist

- [ ] Bridge advertises a single Scoutr API protocol constant on health.
- [ ] Android has one supported range and pure compatibility classifier.
- [ ] New incompatible connections are not persisted.
- [ ] Existing incompatible pairings are retained but feature traffic is gated.
- [ ] Board shows incompatibility distinctly from ordinary disconnection.
- [ ] Recovery after bridge update is covered.
- [ ] Protocol bump rules are documented.
- [ ] Bridge and Android cheap suites pass.
- [ ] Independent review is clean.
- [ ] Any runtime acceptance is performed once, last.

## References

- `AGENTS.md`
- `bridge/src/routes/health.ts`
- `bridge/test/server.test.ts`
- `android/app/src/main/java/dev/scoutr/app/data/Models.kt`
- `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt`
- `android/app/src/main/java/dev/scoutr/app/state/ConnectViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/state/BoardViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/BoardScreen.kt`
- `docs/decisions.md`
- `skills/scoutr-review/SKILL.md`
- `skills/scoutr-verification/SKILL.md`
