# Android Navigation and App Orchestration Blueprint

## Current situation

`android/app/src/main/java/dev/scoutr/app/MainActivity.kt` is both the Android activity entry point and the composition root for nearly every app-level concern:

- deep-link parsing/consumption;
- route string definitions and route encoding helpers;
- `NavController` creation and `NavHost` declaration;
- bottom-tab navigation behavior;
- activity-scoped `BoardViewModel`, `CommandPaletteViewModel`, and `ReviewViewModel` construction;
- per-destination ViewModel construction;
- Board new-session sheet ownership;
- cross-feature navigation callbacks (Board → Chat/Review, Sessions → Chat/Review, Chat → Files/Terminal, Settings → Forget, etc.);
- terminal route reuse semantics;
- connection/reset handling;
- screen scaffolding and app-shell wiring.

The file already has one successful extraction seam: `android/app/src/main/java/dev/scoutr/app/ui/nav/Destination.kt` owns bottom-tab destination metadata, and `TabScaffold` owns shared tab chrome. The app intentionally uses manual DI through `ScoutrApp.AppContainer`; there is no need to introduce Hilt or a new navigation framework.

The P1 Session Model blueprint changes Chat/navigation identity to canonical session keys, and the P1 Attention/Done plans may add Board actions. This P2 refactor must happen **after those route/product changes settle** so the code is reorganized once rather than continuously moved while its contracts are changing.

Precedent: feature UI/state already live in dedicated `ui/screens/*` and `state/*` files. The remaining concentration is application navigation/orchestration, not screen implementation.

## Objective and why

Turn `MainActivity` back into a thin Android entry point and move app navigation/orchestration into cohesive `ui/nav` modules without changing user-visible navigation behavior.

Done means:

- `MainActivity` owns platform activity concerns only: intent/deep-link ingress, edge-to-edge/system-bar setup, reduce-motion/theme bootstrap, and calling the app navigation root;
- route contracts and encoding are centralized in navigation types/helpers rather than a private `Routes` object inside the activity;
- the root `NavHost` and destination graph are split into maintainable feature graph functions/components;
- activity-scoped shared ViewModels and cross-feature actions have explicit ownership rather than ad-hoc construction among route declarations;
- navigation behavior, deep links, bottom tabs, single-top terminal behavior, and ViewModel scope semantics remain unchanged unless a prior P1 blueprint explicitly changed them.

## Scope

Included:

- move route definitions/encoding/decoding into `ui/nav`;
- move `ScoutrAppNav` out of `MainActivity.kt`;
- split the root graph into cohesive destination registration functions/files;
- make shared activity-scoped dependencies/ViewModels explicit in one navigation/app-scope holder;
- preserve existing manual DI and `viewModelFactory`/`savedStateViewModelFactory` patterns;
- add focused navigation contract tests for route building/parsing and critical graph behavior that is practical at JVM level;
- remove dead/duplicate navigation imports/helpers after extraction.

Non-goals:

- no Navigation Compose framework replacement;
- no Hilt/Koin/Dagger introduction;
- no new architecture layer such as coordinators/reducers merely for naming symmetry;
- no redesign of bottom navigation or screens;
- no route behavior changes not required by earlier blueprints;
- no conversion of every callback into events/interfaces if direct callbacks remain clear;
- no broad ViewModel refactor beyond ownership/scoping needed to split navigation safely.

## Global constraints

- Implement after all P0/P1 plans and after the other P2 state/storage plans that affect route inputs are stable.
- Preserve manual DI through `ScoutrApp.AppContainer`.
- Preserve activity-scoped shared `BoardViewModel`, `CommandPaletteViewModel`, and `ReviewViewModel` semantics unless the current code after P1 proves one is no longer shared.
- Preserve `ChatViewModel` SavedStateHandle/process-death behavior.
- Preserve bottom-tab single-top/pop behavior and terminal single-top semantics.
- Preserve `scoutr://` notification/deep-link behavior using the canonical session route model produced by P1.
- Keep composables testable: navigation helpers should not require an Android `Activity` when a plain value/function suffices.
- Follow `ui/theme/DESIGN.md`; this is an architecture refactor, not a visual redesign.
- Final emulator/integration/E2E verification is terminal after review-clean/code-freeze.

## Resolved decisions

### File/module shape

Use `android/app/src/main/java/dev/scoutr/app/ui/nav/` as the owner of application navigation. A target shape such as the following is preferred, but exact filenames are local discretion:

```text
ui/nav/
  Destination.kt          # existing bottom-tab metadata
  Routes.kt               # non-tab destinations + typed builders/parsers
  ScoutrAppNav.kt          # root nav/controller/app-scope composition
  AppNavScope.kt           # shared app-level VMs/actions if useful
  BoardGraph.kt
  SessionsGraph.kt
  UtilityGraph.kt          # chat/files/settings/terminal or smaller graph files
```

Do not create one file per destination just to reduce line count. Split by cohesive ownership and cross-feature dependency boundaries.

### Typed route contract, not a new navigation framework

Replace the private stringly `Routes` object with small route value/helper types under `ui/nav`. Continue using Navigation Compose strings internally because that is the current library contract, but keep URL encoding/decoding and argument names centralized.

After Session Model v3, route helpers must use its settled canonical session-route input. Do not preserve obsolete `paneId + optional sessionPath` route shape just to minimize this refactor.

Example style (adapt to the actual P1 result):

```kotlin
object AppRoutes {
    const val Connect = "connect"
    const val Settings = "settings"
    // ...

    fun chat(session: SessionRouteKey): String = ...
}
```

The route module owns argument names/defaults and builders together so they cannot drift.

### Root navigation ownership

Create a public/internal `ScoutrAppNav(...)` composable in `ui/nav` that owns:

- `rememberNavController()`;
- start destination based on connection state;
- current destination/bottom-bar visibility;
- deep-link consumption passed from the Activity;
- shared app-level ViewModels;
- command palette overlay;
- root Scaffold/NavHost.

`MainActivity` passes only platform ingress state required by the nav root. It should not know feature route strings.

### Shared app-scope dependencies

Do not introduce a generic service locator. `ScoutrApp.AppContainer` remains the dependency source. If the root nav needs several shared values, define a small navigation-only holder such as:

```kotlin
data class AppNavScope(
    val boardViewModel: BoardViewModel,
    val reviewViewModel: ReviewViewModel,
    val paletteViewModel: CommandPaletteViewModel,
    val openSettings: () -> Unit,
    val openTerminal: (SessionRouteKey?) -> Unit,
)
```

The exact holder shape is discretionary. It must clarify ownership, not hide arbitrary dependencies behind a giant bag.

### Feature graph extraction

Use extension functions on `NavGraphBuilder` or composable graph functions where they improve locality:

```kotlin
fun NavGraphBuilder.boardDestination(...)
fun NavGraphBuilder.sessionsDestination(...)
fun NavGraphBuilder.chatDestination(...)
```

Group destinations by shared dependencies. A graph function receives the callbacks/dependencies it genuinely needs; it must not reach back into `MainActivity` or create a second `NavController`.

### Deep links

`MainActivity` remains the Android intent ingress because `onNewIntent()` is a platform callback. It parses/holds the incoming `ScoutrDeepLink` (or passes raw URI to a navigation parser if that parser becomes the canonical owner), then `ScoutrAppNav` consumes it once the graph is ready.

Do not migrate to Navigation Compose manifest deep-link magic if doing so changes the existing validated/sanitized `parseScoutrUri` security boundary.

### Rejected alternatives

- Hilt/navigation framework migration: rejected; no product problem justifies the dependency/architecture change.
- One giant `AppCoordinator` class owning all screens: rejected; it would move the monolith rather than split it.
- Feature modules with independent NavHosts/controllers: rejected; Scoutr has one phone navigation hierarchy and cross-feature navigation is intentional.
- Refactor before P1 route/session work: rejected; it would create avoidable merge/refactor churn.

## Approach

First lock the route contract around the post-P1 session identity. Then extract the root nav composable from the Activity unchanged. Once behavior is preserved, split graph registration into cohesive modules and make shared activity-scope ViewModel ownership explicit. Finally shrink `MainActivity` and add regression tests around route encoding, deep-link consumption, tab behavior helpers, and any pure navigation decisions.

This is a behavior-preserving refactor: move verified behavior first, then simplify duplication. Do not combine it with unrelated screen redesign or state-model changes.

## Contracts and interfaces

### Activity → nav root

Target boundary:

```kotlin
class MainActivity : ComponentActivity() {
    // owns platform intent state
    override fun onCreate(...) { ... ScoutrAppNav(deepLink = deepLink) }
    override fun onNewIntent(...) { ... }
}
```

`MainActivity` must not import individual feature screens/ViewModels after completion.

### Route module

The route module owns:

- constant route patterns;
- nav argument names/defaults;
- builders for routes containing encoded values;
- pure parser/helper functions where needed;
- route key/value objects required to prevent mixing pane/session/file values.

All feature graphs consume those helpers rather than string concatenation.

### Nav graph modules

Every extracted graph registration function receives only the dependencies/callbacks required by its destinations. Shared activity ViewModels are created once in the root and passed where needed.

### Cross-change interface table

| Change | Consumes | Produces |
|---|---|---|
| 1 | post-P1 route/session contracts | centralized `AppRoutes` helpers |
| 2 | current `ScoutrAppNav` body | root nav composable outside Activity |
| 3 | root shared VMs/actions | explicit app-nav scope/ownership |
| 4 | monolithic `NavHost` destinations | cohesive graph registration modules |
| 5 | extracted nav root | thin `MainActivity` + focused tests |

## Changes

- [x] **1 — Centralize route patterns, arguments, and encoding**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/MainActivity.kt` → private `Routes`
  - Anchor: `android/app/src/main/java/dev/scoutr/app/ui/nav/Destination.kt`
  - Move non-tab route constants/builders into `ui/nav` and adapt them to the final P1 canonical session route shape.
  - Keep file/path encoding in one helper path; no destination should hand-roll `URLEncoder` fragments after this change.
  - Keep argument declarations adjacent to route ownership or expose helpers consumed by graph registration.
  - Proof: pure/JVM route tests cover reserved characters, empty optional values, canonical session key round-trip, file paths, and terminal optional target.

- [x] **2 — Extract `ScoutrAppNav` from `MainActivity` without behavior changes**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/MainActivity.kt` → `ScoutrAppNav`
  - Move the root nav/controller/scaffold/overlay composition into `ui/nav/ScoutrAppNav.kt` (or equivalent).
  - Keep start destination, deep-link one-shot consumption, bottom bar visibility, tab pop/singleTop behavior, command palette overlay, and root transition specs identical.
  - At this stage prefer mechanical extraction over redesign.
  - Proof: Android JVM/Compose tests compile and existing navigation/UI tests remain green; diff review shows behavior moved rather than rewritten.

- [x] **3 — Make shared app-scope ViewModel/action ownership explicit**
  - Anchor: extracted `ScoutrAppNav` → activity-scoped `BoardViewModel`, `CommandPaletteViewModel`, `ReviewViewModel`
  - Create these shared ViewModels exactly once at the root using existing factories/manual DI.
  - Pass them/callbacks into graph functions instead of recreating them per destination.
  - Preserve Board badge + Board screen sharing one Board snapshot/poller.
  - Preserve Review selection from Board/Sessions and the global command palette behavior.
  - Avoid a generic mega-context; graph functions should still show their important dependencies in signatures.
  - Proof: tests/assertions or code-level scope tests show one shared VM instance per activity nav root where required.

- [x] **4 — Split destination registration into cohesive graph modules**
  - Anchor: extracted root `NavHost` destination declarations
  - Extract Board/New Session wiring, Sessions/History wiring, and non-tab utility flows (Chat/Files/Settings/Terminal) into cohesive `ui/nav` graph files/functions.
  - Keep destination-specific ViewModel creation next to the destination that owns it.
  - Preserve Chat's `savedStateViewModelFactory` and keying semantics.
  - Keep Settings forget/reset behavior and terminal single-top behavior intact.
  - Do not create nested NavHosts.
  - Proof: `ScoutrAppNav` reads as app-shell/orchestration rather than hundreds of lines of destination implementation, and `MainActivity` imports no feature screens.

- [x] **5 — Shrink `MainActivity` to platform bootstrap and clean up dead orchestration**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/MainActivity.kt` → `onCreate`, `onNewIntent`
  - Retain edge-to-edge/system-bar setup, reduce-motion store/theme bootstrap, and deep-link intent ingress.
  - Remove feature screen/ViewModel/navigation imports and private route helpers.
  - Keep `parseScoutrUri` validation ownership explicit; if parsing moves into `ui/nav`, preserve the same validation contract and update notification/deep-link tests accordingly.
  - Proof: `MainActivity` is a small platform shell and no app behavior depends on activity-private route state/functions.

- [x] **6 — Add focused navigation regression coverage**
  - Anchor: existing Android JVM/navigation/UI test conventions under `android/app/src/test`
  - Test route builders/parsers as pure functions.
  - Add the narrowest practical tests for initial Connect vs Board destination, deep-link consumption only when paired, bottom-tab destination recognition, and terminal route single-target construction.
  - Reuse existing Compose navigation tests if present; do not build a huge instrumentation suite solely for this refactor.
  - Proof: `make android-test` passes and tests fail if route argument names/encoding drift.

## Failure handling

- Invalid/foreign deep link: preserve existing validated-parser behavior; never navigate arbitrary URI data into route construction.
- Deep link received while unpaired: preserve current behavior (do not enter Chat without a saved authenticated connection); consume/drop according to current settled contract rather than creating hidden pending state.
- Canonical session route fails to decode: surface/return to a safe route according to P1 behavior; do not reconstruct identity from stale pane/path fragments.
- Process recreation: destination-owned ViewModels and Chat SavedStateHandle must retain current semantics.
- Repeated terminal action: preserve `launchSingleTop` and one route/socket semantics.
- Repeated tab taps: preserve current pop/singleTop behavior and do not stack duplicate tab roots.

## Validation

1. Focused route/helper unit tests while extracting.
2. Existing ViewModel/UI JVM tests for Board, Sessions, Chat, Settings, Review, Terminal.
3. `make android-test`.
4. Independent review using `skills/scoutr-review/SKILL.md`; reviewer should compare before/after navigation semantics, not merely style.
5. After code freeze, final runtime acceptance using `skills/scoutr-verification/SKILL.md`: cold launch paired/unpaired, switch every bottom tab, Board → Chat, Sessions → resume → Chat, Review navigation, Files, Settings back/forget, global + per-session Terminal, notification/deep-link into Chat, and back-stack behavior. Run this once as the terminal verification pass.

## Local discretion

- Exact graph file grouping, provided modules are cohesive and not one-file-per-route ceremony.
- Whether a small `AppNavScope` holder is useful; direct parameters are preferred when signatures remain readable.
- Exact route value-object names after Session Model v3.
- Whether start-destination calculation is a helper or local root-nav state.

## Escalation triggers

- P1 implementations leave route/session identity unresolved or still in active migration; do not freeze an obsolete route contract.
- Extracting graph modules reveals shared ViewModels that actually depend on destination-local SavedStateHandles or lifecycles; verify before changing scope.
- Maintaining behavior requires nested NavHosts/controllers; that indicates the proposed cut is wrong and should be revisited.
- A navigation framework/version upgrade is required merely to compile the refactor; keep the current dependency unless an independent repository requirement already mandates the upgrade.
- Refactor starts changing user-visible navigation semantics, screen layout, or state ownership beyond what P0/P1/P2 plans already settled.

## Review handoff

Reviewer must compare the old monolithic navigation behavior with the extracted architecture across these paths: cold start, pair/forget, each bottom tab, Board/Sessions → Chat, Chat → Files/Terminal, Board/Sessions → Review, Settings back, global terminal, and notification deep link.

The architectural approval criteria are:

- `MainActivity` is platform-only;
- one root NavController/NavHost remains;
- routes/encoding have one owner;
- shared ViewModels have one explicit root owner;
- destination-local ViewModels remain destination-local;
- no feature graph reaches into Activity internals or creates parallel navigation state.

## Completion checklist

- [x] Non-tab route patterns/builders/parsers live under `ui/nav`.
- [x] Route contract reflects final Session Model v3 identity.
- [x] `ScoutrAppNav` no longer lives in `MainActivity.kt`.
- [x] Shared Board/Review/Palette ViewModels have explicit single root ownership.
- [x] Root `NavHost` destinations are split into cohesive graph modules.
- [x] No nested/parallel NavController architecture was introduced.
- [x] `MainActivity` owns only Android/platform bootstrap and intent ingress.
- [x] Deep-link validation and terminal/tab back-stack semantics are preserved.
- [x] Focused route/navigation JVM tests exist.
- [x] `make android-test` passes.
- [x] Independent review is clean.
- [x] Final runtime acceptance passes once, last.

## Outcome (2026-08-21)

Implemented on `main`. New modules under
`android/app/src/main/java/dev/scoutr/app/ui/nav/`: `AppRoutes.kt`
(non-tab route constants, builders, arg names, one encode helper,
`navigateToChat` extensions, `decodedChatSessionKey`, `initialStartDestination`),
`ScoutrAppNav.kt` (shell: shared activity-scoped ViewModels, deep-link
consumption, pairing handlers, wide-window split, bottom bar), plus
`BoardGraph.kt`, `SessionsGraph.kt`, `ChatGraph.kt`, `FilesGraph.kt`,
`UtilityGraph.kt`. `MainActivity.kt` shrank from ~730 to ~55 lines
(edge-to-edge, reduce-motion store, deep-link ingress only).

Checks: `compileDebugKotlin` clean; `make android-test` green including 13 new
`AppRoutesTest` route-contract tests; independent review CLEAN (old-vs-new
navigation semantics compared against `HEAD:MainActivity.kt`).

Runtime acceptance (emulator-5554, live scratch bridge): unpaired cold start →
Connect; UI pairing → Board with bottom bar; paired cold start → Board; all
four tabs switch with live data; Board row → Chat by canonical key;
Chat → Files browser (per-cwd) → viewer → back×2 intact; Chat → Terminal
(single-top) → back chain to Board; Sessions history row → Chat resume;
Review tab shows the shared-ViewModel repo picker; `scoutr://chat/w83:p0`
deep link opens that chat; Settings back works and Forget (confirm dialog) →
Connect-only graph.

Follow-up fix: `NavHostGraphTest.bottomBarShowsAllFourTabsAndSwitchesBetweenThem`
was failing at HEAD before this refactor (papercut cbc4603, since closed): its dead-port
seed never yields `apiCompatibility=Compatible`, so the shell hides the bottom bar by
design. The seed rule now runs an in-process MockWebServer answering the health
handshake and an empty board; the test passes on emulator-5554.

## References

- `AGENTS.md`
- `.plans/p1-done-ship-readiness-summary.md`
- `.plans/p2-host-workspace-namespace.md`
- `android/app/src/main/java/dev/scoutr/app/MainActivity.kt`
- `android/app/src/main/java/dev/scoutr/app/ScoutrApp.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/nav/Destination.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/nav/TabScaffold.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`
- `skills/scoutr-review/SKILL.md`
- `skills/scoutr-verification/SKILL.md`
