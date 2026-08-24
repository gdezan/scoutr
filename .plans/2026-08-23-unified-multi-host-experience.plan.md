---
status: blocked
created: 2026-08-23
owner: implementation-agent
scope: unified-multi-host-experience
depends_on:
  - .plans/2026-08-23-multi-host-foundation.plan.md
blocks: []
---

# Unified multi-host experience

## Goal

Turn the host-qualified foundation into the user-facing multi-host product. Board and Sessions merge data from every compatible paired host. Settings manages the host list. Hostless actions show and honor the persistent default host.

Do not start this plan until `.plans/2026-08-23-multi-host-foundation.plan.md` is complete, reviewed, and accepted. This plan assumes `HostRegistryStore`, `HostClientFactory`, host-qualified routes, push identity, default/update host invariants, and singleton migration are already in place.

## Product contract

- Board and Sessions default to All hosts. One in-memory filter is shared between those two screens and resets to All on a cold process start.
- Under All hosts, merge rows into the existing status/group structure, order them globally by status and recency, and show a host alias on every row. Under one host, omit the redundant row labels.
- Each host refreshes independently. One slow, offline, identity-changed, or incompatible host never delays or clears data from another host.
- Board snapshots live only for the process lifetime. If a host goes offline during the run, keep its last Board snapshot and mark it stale. After a cold start there is no offline Board snapshot.
- Session snapshots persist with fetch timestamps. Cached rows appear after restart while their host is offline.
- Offline cached Session rows keep local pin/archive actions. Do not enable, queue, or replay remote mutations, resume, Chat commands, or terminal attachment while their host is unavailable.
- An incompatible host is flagged separately and excluded from merged Board/Session rows. Compatible hosts continue normally.
- Settings replaces the singleton connection card with a Hosts list. It supports add, rename, default selection, credential refresh, status/update inspection, and forget.
- Removing the default host chooses the most recently used remaining host. Network failure never changes the default.
- New Session, Usage, and a Terminal launch that did not originate from a session expose the default host before acting. An offline default remains selected; the user must explicitly choose a reachable host. There is no silent redirect.
- Session-originated Chat, Review, Files, Usage, and Terminal actions always use the session's host-qualified route.
- Only the update host supplies Android update checks, builds, and APK downloads.

## Hosted view models

Add local wrapper models rather than inserting Android-only host fields into bridge DTOs:

```kotlin
data class HostedSession(
    val profile: HostProfileKey,
    val session: SessionDescriptor,
)

data class HostedCatalogItem(
    val profile: HostProfileKey,
    val key: HostSessionKey,
    val item: SessionCatalogItem,
    val fetchedAtMs: Long,
    val availability: HostAvailability,
)
```

Resolve aliases from current `HostRegistryState` at presentation time so rename takes effect without refetching bridge data. Never use alias as identity. Build wrappers with the current `HostProfileKey`; row actions and routes use it, while retained pin/archive membership continues to use generation-free `HostSessionKey`.

Add a process-local `HostStatusRepository` under `state/` or `data/`. It owns independent status for each registered id:

```kotlin
sealed interface HostAvailability {
    data object Unknown : HostAvailability
    data class Online(val checkedAtMs: Long) : HostAvailability
    data class Offline(val lastSuccessAtMs: Long?, val message: String) : HostAvailability
    data class Incompatible(val message: String) : HostAvailability
    data class IdentityChanged(val reportedHostId: String) : HostAvailability
}
```

`HostStatusRepository.probe(hostId)` captures the current `connectionRevision`, registers the probe with `HostWorkCoordinator`, calls the identity-guarded fixed-host API, requires `host-identity.v1` plus a nonblank reported id, and classifies compatibility. Immediately before updating status, require the binding to remain active; otherwise discard the response. A missing identity or feature is `Incompatible`; a different nonblank id is `IdentityChanged`. Both outcomes stop Board/Session fetches for that binding. Coalesce concurrent probes only for the same `(hostId, connectionRevision)`, never behind one global mutex. No outcome mutates the registry.

The status repository is process-local. Session snapshot timestamps provide durable freshness; do not persist a claim that a host is currently online.

## Shared host filter

Add `HostFilterStore` to `AppContainer` with `StateFlow<String?>`, where `null` means All hosts. Do not write it to SharedPreferences, SavedStateHandle, or a route. Board and Sessions collect the same instance.

Rules:

- start at All on every process creation;
- keep the selected id while navigating between Board and Sessions;
- if that host is forgotten, reset to All;
- do not change it because the host is offline or incompatible;
- list All first, then profiles in Settings order.

Build one compact Material 3 selector component under `ui/components/` and reuse it on Board and Sessions. Follow `ui/theme/DESIGN.md`; do not introduce new colors or motion. The selector must expose current scope and per-host availability to accessibility services.

## Independent Board aggregation

Refactor `BoardViewModel.kt` from one `Poller` and one API into per-host workers owned by the screen lifecycle.

Maintain:

```kotlin
data class HostBoardState(
    val sessions: List<SessionDescriptor> = emptyList(),
    val availability: HostAvailability = HostAvailability.Unknown,
    val fetchedAtMs: Long? = null,
    val refreshing: Boolean = false,
)
```

When Board enters STARTED:

1. Collect registry changes and reconcile one worker per `(hostId, connectionRevision)` binding. Register it with `HostWorkCoordinator`; credential refresh or identity replacement retires and joins the old binding before starting its replacement.
2. Each worker probes its own health/compatibility and fetches `agents()` on the existing cadence.
3. Use sibling child jobs under a supervisor so an exception or timeout stays local to that host.
4. Before applying any response, verify the profile still exists with the captured `connectionRevision` and compatible status; otherwise discard it even if cancellation lost the race. On success, replace only that host's snapshot and record its timestamp.
5. On a network failure, keep that host's in-memory sessions, mark them stale/offline, and continue polling with the existing bounded behavior.
6. On incompatibility or identity mismatch, exclude that host's rows and expose an actionable host status. Do not clear other snapshots.
7. On host removal, cancel its worker and discard its process-local Board snapshot.

On STOPPED, cancel all Board workers and requests just as the current screen cancels its singleton polling. Re-entering STARTED performs an immediate refresh. Pull-to-refresh starts all selected and unselected compatible host requests concurrently and completes when they have all settled; a failed host must not fail the whole gesture.

Derive the visible Board after applying `HostFilterStore`:

- preserve the existing Board status section order;
- sort cards inside each status globally by `updatedAtMs` descending, then alias and host id for deterministic ties;
- under All, add the host alias to each card and test its semantics;
- under one host, omit the label;
- stale cards show a quiet offline/freshness marker without changing their status color;
- incompatible/identity-changed hosts appear in a compact status area with a Settings action, not as empty cards.

Every quick answer, open, revive, Review, Files, or Terminal callback takes the hosted `HostProfileKey`, requires it to remain current, and uses `hostClients.api(profile.hostId)` or a profile-qualified route. Track quick-answer busy state by `HostPaneKey`.

Do not persist Board snapshots. A fresh process with all hosts offline shows the host statuses and an empty Board, not yesterday's live state.

## Persistent Sessions aggregation

Add `SessionSnapshotStore` in `data/`. Use one JSON file per host under `context.filesDir/scoutr/session-snapshots`; do not use `cacheDir`, because Android may evict it independently of process death. File names must derive from a stable digest of `hostId`, not raw host input. A record contains schema version, host id, `fetchedAtMs`, `truncated`, and the serializable `SessionCatalogItem` list.

Add one `validateSessionCatalogResponse` function and run it before any `SessionCatalogItem.key` access, merge, or cache write for both live HTTP responses and persisted snapshots. Validate schema, embedded host id for persisted records, and every item. Each item must have a nonnull `SessionDescriptor.key` whose `agentKind` and `path` are nonblank and round-trip through the canonical `SessionKey` codec. A bad live response becomes a host-local catalog failure that retains the previous snapshot and does not affect other hosts. A bad persisted record is deleted and treated as a cache miss.

Write valid snapshots through a temporary file and atomic rename after each successful unfiltered catalog fetch. Expose `read(hostId)`, `write(hostId, snapshot)`, and `clear(hostId)`. Forget and identity replacement clear the old host's snapshot; re-pair after an ordinary forget starts without transcript-derived cached rows.

Refactor `SessionHistoryViewModel.kt` into per-host catalog workers:

1. Load every paired host's cache concurrently before the first network refresh.
2. Render cached items with each snapshot's timestamp and current host availability.
3. Reconcile independent unfiltered workers by `(hostId, connectionRevision)`, register them with `HostWorkCoordinator`, and run `sessions(limit = 200)` on the current lifecycle cadence. Before applying or caching a response, require the binding to remain active with compatible status. Successful responses update only that host and its cache.
4. Keep a failed host's cached/current snapshot and mark it offline.
5. Exclude incompatible and identity-changed hosts after classification while leaving their cache on disk until forget/replace.
6. Cancel a removed host worker and clear its in-memory items.

Keep `truncated` beside each host's catalog in memory and on disk. When it is true, show "Showing latest 200 from <alias>" in that host's status detail; under All, list each truncated host rather than collapsing them into one misleading global count. Search still queries online hosts independently as described below.

For a blank search, merge the full cached/live snapshots. For a nonblank search:

- debounce input, increment a query generation, cancel all jobs from the previous generation, and register each new per-host search with `HostWorkCoordinator`;
- filter cached items locally for offline hosts;
- query each online compatible host so matches outside its cached newest 200 remain discoverable within the bridge's existing bounded 500-file scan; preserve each query response's `truncated` flag and show the same bounded-results copy rather than implying exhaustive search;
- keep query results separate from the unfiltered cache and never write them to disk;
- merge results by `HostSessionKey`.

Before applying any host response, verify the captured query generation/text, host `connectionRevision`, current profile membership, and compatible host status still match. Registry removal, credential refresh, identity replacement, or incompatible/identity-changed status cancels that host's active search and removes its query-result map even if the transport ignores cancellation. A blank query cancels all search jobs and immediately derives rows from base snapshots. Add delayed-response tests proving an older query or retired host binding cannot overwrite current or blank state.

Apply pin/archive membership through the host-qualified `SessionCatalogStore`. Keep the existing All, Active, Completed, Pinned, and Archived scopes and existing date headers. Sort rows inside the selected scope/date grouping by session recency across all selected hosts, with alias and host id as deterministic ties. Any busy state uses `HostSessionKey`.

Offline rows remain visible with alias and "Last synced …" copy. Keep pin, unpin, archive, and restore enabled because those are local writes. Disable remote resume/revive, session commands, and terminal attachment. Do not create a retry queue. If the user opens a host-bound destination from a row that became offline, that destination shows its normal unavailable state and still must not switch hosts.

Update `HistoryFormatTest`, repository filter helpers, and Session panel actions to accept hosted items without duplicating the ordering logic in Compose.

## Hosts settings

Replace `ConnectionSection` in `SettingsScreen.kt` with a Hosts section backed by a new `HostsViewModel`. Keep appearance, notification, and terminal settings unchanged.

Each host row shows:

- alias as the primary label;
- normalized URL as secondary text;
- Default and Updates badges when applicable;
- Online, Offline, Checking, Incompatible, or Identity changed state;
- last successful check when useful;
- an overflow menu for Rename, Set default, Refresh credentials, Use for updates, and Forget.

Tapping a row opens status details with bridge/herdr versions, API compatibility, exposure, and last error. Refresh checks only that host.

### Add and refresh

- Add host launches the existing QR/manual pairing UI as a nested Settings flow. It never replaces an existing profile merely because one exists.
- Pairing a new id adds it. Pairing an existing id refreshes URL/token/exposure and returns to its row without changing alias, default, update host, pins, archive flags, or terminal display preferences.
- Refresh credentials starts from a specific profile and uses the identity-mismatch outcome from the foundation plan.
- For an identity mismatch whose reported id is not paired, show "Bridge identity changed" with Replace and Add as new. Replace has a separate, unchecked "Move pin and archive flags" confirmation. If the source is the update host, also require Use replacement for updates after the signing warning, choose another update host, or disable updates. Default ownership transfers automatically as defined by the foundation. Execute Replace only through `HostLifecycleCoordinator` so old work retires and transient cleanup is tombstoned. Neither option migrates snapshots, notifications, mutes, or terminal ownership. If the reported id is already paired, show Refresh existing profile and Cancel instead; never offer a duplicate-producing Replace/Add choice.

### Rename, default, and update host

- Rename is local, nonblank, trimmed, and restored after forget/re-pair of the same id.
- Set default updates the persistent default immediately. It does not navigate an already open host-bound screen.
- Use for updates first shows a warning that hosts may build APKs with different signing keys and that an APK signed with another key may not install over the current app. Only confirmation changes `updateHostId`.
- Update status and `AppUpdater` continue to resolve the one update-host client supplied by the foundation plan.

For incompatible hosts, tailor the row action from the compatibility result. A bridge protocol newer than the app may offer "Update app from this host"; selecting it still requires the update-host signing warning. An older bridge, a missing protocol, or missing required bridge features shows the bridge deploy/update guidance already produced by `formatScoutrApiIncompatibility`. Do not route an incompatible host's data into Board or Sessions.

### Forget

Drive forgetting through the foundation's `HostLifecycleCoordinator`; Settings only collects the required disposition:

1. If the host is the update host and other profiles remain, require either a replacement update host or explicit in-app update disablement.
2. `HostLifecycleCoordinator.forget` retires the binding in `HostWorkCoordinator`, waits for registered Board/Session/status/search/push work to settle, performs the final best-effort unregister while credentials exist, then atomically removes the profile, applies default/update invariants, and creates its cleanup tombstone.
3. The pending-cleanup handler clears that host's Session snapshot, process-local Board snapshot/status, notifications, mutes, host-derived terminal preferences, Review repository path, and launcher settings/presets. Run the clear after quiescence/removal even if an earlier eager clear ran.
4. Remove the cleanup tombstone only after every local cleanup operation succeeds; retry tombstones on application startup.
5. Keep remembered alias and pin/archive flags.
6. If it was the final host, clear update source and navigate to Connect. Otherwise remain in Settings.

Make cleanup idempotent so process recreation or a repeated confirmation cannot strand half-removed state.

## Hostless action targeting

Use the registry's persistent `defaultHostId`; do not add another "current host" concept.

### New Session

Add a host selector at the top of the New Session sheet. Initialize it from the default host. Each selection allocates a UI generation and captures `(HostProfileKey, connectionRevision, selectionGeneration)` for launcher-settings, directory, and model loads. Register every host-bound load/create job with `HostWorkCoordinator`. Changing host cancels outstanding loads, loads that host's launcher settings, and reloads host-derived choices through that host's API. Before applying any result, require the captured tuple to equal the current active selection and binding; cancellation alone is insufficient.

Refactor `LauncherSettingsStore` to `load(hostId)`, `save(hostId, settings)`, `migrateLegacy(firstHostId)`, and `clear(hostId)`. Scope default/recent model keys, thinking levels, favorite model keys, recent folders, and presets to the bridge whose model catalog and filesystem they reference. Migrate the current singleton settings once to the first host. Forget or identity replacement clears the old host's launcher settings, including preset prompts and paths; do not copy them to a new identity.

The selected alias remains visible through creation. If selected host is offline, incompatible, or identity-changed, disable Create and show the status with a selector action. Do not preselect another host. On Create, capture the selection tuple and disable selector/Create until settlement. Invoke and route success with the captured profile key, never a later selection; require that binding to remain active before navigating to `chat(capturedProfile, bootstrapPaneId)`. If it retired, show the host status instead. Mark only the captured host used.

### Usage

Keep Usage as one host-bound route at a time. Put the inline host selector above its content. A selection replaces the concrete Usage route with the current `HostProfileKey` and creates a host-bound `UsageViewModel`. An offline default opens selected but unavailable until the user chooses another reachable host. Never merge usage totals in this effort.

### Global Terminal

A Terminal opened from a session already carries its profile key and bypasses host choice. For the shell's global Terminal action, show a compact host target sheet before navigation. It starts on the default and requires explicit Continue, making the target visible before any WebSocket or hierarchy request. Disable Continue for unavailable hosts. Navigate to `terminal(currentProfileKey, paneId = null)` only after confirmation.

### Other host-bound entries

A Review tab opened without session context and the command palette resolve the default host into a concrete host route/client. Show the alias in their header so requests are not anonymous. They do not aggregate across hosts in this plan. Session-originated Review and command results keep their source host id.

## App shell and navigation integration

Update `ScoutrAppNav.kt`, `BoardGraph.kt`, `SessionsGraph.kt`, `UtilityGraph.kt`, and shell callbacks to pass hosted identities rather than raw `SessionKey` or pane id.

Bottom-tab route matching must still work when Usage and Review destinations contain host arguments. Use destination route patterns for selection and concrete route builders for navigation. Do not store a mutable host singleton in `ScoutrAppNav`.

When the final host is forgotten, clear host-bound back-stack entries before navigating to Connect. When one of several hosts is forgotten while its Chat/Terminal route is open, that destination shows "Host forgotten" and lets the user return; it must not reinterpret the stale `HostProfileKey` against the new default or a later same-id re-pair.

## Implementation order

1. Add `HostAvailability`, `HostStatusRepository`, `HostFilterStore`, and `SessionSnapshotStore` with focused tests.
2. Refactor Board into independent per-host workers and hosted actions. Add filter, aliases, stale markers, and per-host status UI.
3. Refactor Sessions into cache-first independent workers, hosted identity, global grouping/sorting, offline action rules, and shared filtering.
4. Build `HostsViewModel` and the Settings Hosts list, then wire add/refresh/identity-change/default/update/forget flows.
5. Add New Session and Usage selectors plus the global Terminal target sheet. Bind Review and command palette to a visible concrete host.
6. Complete final-host navigation, cleanup hooks, accessibility semantics, empty/error states, and design-system polish.
7. Run the simplify pass, repository review, and final runtime acceptance.

Keep Board and Sessions working for one migrated host after steps 2 and 3. A single-host user should see the same content with no redundant host labels.

## Tests

Add focused JVM tests for:

- per-revision status probes, same-binding single-flight, cross-host concurrency, and stale-response rejection after refresh/removal;
- missing `host-identity.v1`, missing reported id, and mismatched id classification without registry mutation or data fetch;
- shared filter persistence across tab ViewModels and reset on new `AppContainer`;
- selected-filter reset only when that host is forgotten;
- Board merge ordering, label visibility, stale snapshot retention, and no disk persistence;
- one hanging/failing Board host not delaying another host's success, and delayed responses from removed/refreshed/incompatible bindings being discarded;
- per-host pull-to-refresh settlement;
- shared live/cache catalog validation, including corrupt/schema/host mismatch and null/invalid canonical key handling, plus atomic snapshot round trip in `filesDir` and clear-on-forget;
- independent per-host `truncated` persistence and "latest 200" status copy under All and single-host filters;
- cache-first Session rendering after process recreation;
- online query plus offline local-filter merge without overwriting the base cache, including delayed old-query and removed/refreshed/incompatible-host responses that must be discarded;
- identical `SessionKey` values on two hosts staying distinct through pin/archive/busy/actions;
- offline local actions enabled and all remote actions disabled with no queued retry;
- incompatible hosts excluded while compatible hosts continue;
- profile rename immediately changing displayed labels without refetch;
- Hosts add/refresh/identity-change/default/update/forget outcomes, including replacement role disposition;
- forget/replacement worker quiescence, no post-clear repopulation, and startup completion of cleanup tombstones;
- update-host warning and removal disposition requirements;
- final-host forget navigating to Connect;
- host-qualified launcher settings, one-time legacy migration, clear-on-forget, and no folder/model/preset bleed between hosts;
- New Session selection generations discarding delayed old-host loads, freezing the captured binding during create, and routing only to that captured active host;
- Usage route replacement and global Terminal confirmation;
- no silent fallback from an offline default.

Extend `BoardViewModelTest`, `BoardFormatTest`, `BoardAttentionTest`, `SessionHistoryViewModelTest`, `HistoryFormatTest`, `HistoryRepositoryFilterTest`, `NewSessionViewModelTest`, `UsageViewModelTest`, `CommandPaletteViewModelTest`, `ReviewViewModelTest`, route tests, and Settings Compose tests. Add instrumentation only for interactions that JVM/Compose host tests cannot prove.

## Verification

Use focused JVM and Compose checks while implementing. After code settles, run:

```bash
make android-test
```

Run `skills/scoutr-review/SKILL.md`, resolve or dismiss every finding, then freeze code. Select final acceptance with `skills/scoutr-verification/SKILL.md`. This is broad Android state and UI work, so acceptance needs the `cockpit` emulator and two reachable scratch/live bridges with distinct host ids. Keep Gradle and instrumentation serial.

Prove these flows:

1. All-host Board merges sessions, labels hosts, and keeps host A live when host B goes offline.
2. Board retains B's stale cards only until process death; after restart its offline Board is empty.
3. Sessions loads both caches after restart, shows timestamps, permits pin/archive offline, and disables resume/terminal.
4. One host's protocol mismatch appears as its own action while compatible data remains usable.
5. The filter is shared between Board and Sessions and resets to All after a cold start.
6. Add, refresh same identity, rename, set default, set update source, and forget all preserve their stated invariants.
7. An identity mismatch does nothing until Replace/Add; metadata moves only when separately confirmed, and default/update ownership follows the chosen replacement disposition.
8. New Session, Usage, and global Terminal retain an offline default and require an explicit reachable selection.
9. Session-originated Chat, Review, Files, and Terminal stay on their source host after the default changes.
10. Removing the update host requires replacement/disablement, and removing the final host returns to Connect.

No bridge deploy is required if the dependency plan's bridge contract is already live and unchanged. If acceptance uses the supervised service, verify its deployed commit before treating failures as product behavior. Kill `emulator-5554` when done.

## Non-goals

- Do not merge Usage totals, Review repositories, file trees, Chat transcripts, or terminal hierarchies across hosts.
- Do not persist Board snapshots.
- Do not add Room/Hilt or a background sync service.
- Do not queue remote work for offline hosts.
- Do not auto-select an online host when the default is offline.
- Do not infer identity from URL, alias, token, pane id, or session path.
- Do not let any host other than the explicit update host build or download an APK.

## Done when

- Board and Sessions are useful across multiple hosts and preserve isolation under partial failure.
- Session data survives restart with clear freshness; Board data does not.
- Settings fully manages host lifecycle, default ownership, and update ownership.
- Every hostless action exposes its target and refuses silent fallback.
- Forget and identity replacement obey the cleanup/retention rules.
- One-host behavior remains direct and uncluttered.
