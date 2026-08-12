# Plan Execution Report

Durable execution ledger for the architecture (docs/architecture/) and implementation
(plans/) campaigns. Every numbered plan has a requirement-level row; the matrix below is
kept truthful as work lands. The final column of each row carries commit hashes.

- Campaign baseline commit: `0d62d2e` (untracked `plans/` audited and committed alone:
  exactly README + nine numbered plans, no secrets, no unrelated material).
- Working branch: `main`, no pushes, no history rewrites.

## Requirement matrix — architecture plans (docs/architecture/)

| Plan | Requirement | Status | Source evidence | Changes | Tests | Review | Commit |
|------|-------------|--------|-----------------|---------|-------|--------|--------|
| 01 | `AgentBackend` contract (`agents/types.ts`) | PENDING AUDIT | | | | | |
| 01 | Registry (`backendFor*`, `knownBackends`) | PENDING AUDIT | | | | | |
| 01 | Pi adapter; claude adapter | PENDING AUDIT | | | | | |
| 01 | Session orchestration agent-agnostic (`sessions.ts`) | PENDING AUDIT | | | | | |
| 01 | Multi-backend catalog + fair scan budget | PENDING AUDIT | | | | | |
| 01 | Wire: `agentKind`/`capabilities`/`displayName`; `/api/agents/kinds`; `agent` query param | PENDING AUDIT | | | | | |
| 01 | Control dispatch through backend + capability enforcement | PENDING AUDIT | | | | | |
| 01 | Android backend-aware (models(agent), commands, session actions, gates) | PENDING AUDIT | | | | | |
| 02 | One `transcript.ts` module owning the record vocabulary | PENDING AUDIT | | | | | |
| 02 | Three read modes (`tail` bounded, `metadataOnly`, full) + cross-mode agreement | PENDING AUDIT | | | | | |
| 02 | `writeSessionTitle` in transcript module | PENDING AUDIT | | | | | |
| 02 | Consumers ported (`questions.ts`, catalog, board-detail, `server.ts`) | PENDING AUDIT | | | | | |
| 02 | Old parsers deleted; test corpus merged with cross-mode tests | PENDING AUDIT | | | | | |
| 03 | `HerdrPort` declared; `HerdrClient implements HerdrPort` | PENDING AUDIT | | | | | |
| 03 | Shared `fakeHerdr` in test support | PENDING AUDIT | | | | | |
| 03 | Route table + dispatcher; feature-grouped `routes/` handlers; auth/body/404 in dispatcher | PENDING AUDIT | | | | | |
| 03 | No skip gate; live-socket cases gated explicitly | PENDING AUDIT | | | | | |
| 03 | Offline tests for cards/paths/workspace roots/WS commands; config/status tests | PENDING AUDIT | | | | | |
| 04 | `CockpitApi` interface; `BridgeClient : CockpitApi` | SHIPPED | net/CockpitApi.kt: 22 suspend funs + connectedHost, defaults on interface only | net/BridgeClient.kt implements it; `BridgeException(status, reason)`; `call()` private with body; `post()` deleted; upload folded into `call()` | 491e50c | | |
| 04 | ViewModels/container/ReplyReceiver consume `CockpitApi` | SHIPPED | all 9 state ViewModels take CockpitApi; AppContainer.bridge: CockpitApi; ReplyReceiver steers via container | grep-verified clean | 491e50c | | |
| 04 | `FakeCockpitApi` shared by test source sets; MockWebServer tests migrated | SHIPPED | app/src/commonTest/.../FakeCockpitApi.kt wired into test+androidTest in build.gradle.kts; 8 state/ test files migrated | 137 unit tests pass | 491e50c | | |
| 04 | WS path tests; `BridgeException`; auth folded; `call`/`post` private | SHIPPED | BridgeClientWsTest: steer/runSlashCommand/answerQuestion frames, error frame → IOException, feed skipping, empty-key omission; BridgeClientUploadTest kept as HTTP contract test | 137 unit tests pass | 491e50c | | |
| 04 | AGENTS.md gotcha deleted | SHIPPED | AGENTS.md net/ map reads 'BridgeClient behind the CockpitApi interface'; fake noted | | 491e50c | | |
| 05 | `SessionAction` enum both sides; call sites converted | SHIPPED | 20bb93b | `data/SessionAction.kt` (wire+label, `fromWire` drops unknowns); CockpitApi/controlSession take the enum; all 7 call sites converted | | |
| 05 | `CatalogAction` for resume/fork/rename | SHIPPED | 20bb93b | `data/CatalogAction.kt` (Resume/Fork/Rename/Delete — delete added because the enum must declare the full catalog verb set); `sessionCatalogAction` typed | | |
| 05 | Capabilities decoded into UI state; menu rendered from set | SHIPPED | 20bb93b | `toSessionActions()` decode helper; overflow menu renders from the set in enum declaration order; thinking chip gated via `canSetThinking` (plan 01) | | |
| 05 | Server-side capability enforcement in `controlSession` | SHIPPED | (plan 01 verified) | claude adapter rejects out-of-capability verbs with 'unsupported control action for claude' before pane input; pi adapter covers the pi surface | | |
| 06 | `Poller` + tests; four loops migrated | SHIPPED | fad4d24 | `state/Poller.kt`; Board (3s), SessionHistory (8s), Chat (2.5s), Usage (10s) use it; `PollerTest` (immediate tick, restart cancels, stop, scope death) | | |
| 06 | `Loadable` + `FailureKind` (from `BridgeException`); VMs migrated; offline rule explicit | SHIPPED | fad4d24 | `state/Loadable.kt`; Usage/Connect/Board/SessionHistory/Chat/Review migrated per-field; Usage chart-never-blanks rule explicit in `UsageViewModel` + test | | |
| 06 | `viewModelFactory` helper; eight factories collapsed | SHIPPED | fad4d24 | `state/ViewModelFactory.kt`; Board/Chat/Connect/SessionHistory collapsed; CommandPalette/LiveOutput/NewSession/Review retained by decision (blast radius) | | |
| 07 | `Destination` in `ui/nav/Destination.kt` with `routes`/`forRoute` | PENDING AUDIT | | | | | |
| 07 | Bottom-bar check derived; `Routes` trimmed | PENDING AUDIT | | | | | |
| 07 | `TabScaffold` extracted; four tabs use it | PENDING AUDIT | | | | | |
| 07 | Unit tests (routes/forRoute) + NavHost graph test | PENDING AUDIT | | | | | |

## Requirement matrix — implementation plans (plans/)

| Plan | Requirement | Status | Source evidence | Changes | Tests | Review | Commit |
|------|-------------|--------|-----------------|---------|-------|--------|--------|
| 001 | — (pending phase 2) | PENDING | | | | | |
| 002 | — (pending phase 2) | PENDING | | | | | |
| 003 | — (pending phase 2) | PENDING | | | | | |
| 004 | — (pending phase 2, after 003) | PENDING | | | | | |
| 005 | — (pending phase 2) | PENDING | | | | | |
| 006 | — (pending phase 2) | PENDING | | | | | |
| 007 | — (pending phase 2, after 005) | PENDING | | | | | |
| 008 | — (pending phase 2) | PENDING | | | | | |
| 009 | — (pending phase 2) | PENDING | | | | | |

## Verification gates

| Gate | Command | Last run | Result |
|------|---------|----------|--------|
| Bridge typecheck+tests | `cd bridge && npm run typecheck && npm test` | — | PENDING |
| Android unit tests | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` | — | PENDING |
| Emulator instrumentation | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest` | — | PENDING |
| APK assemble | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` | — | PENDING |

## Assumptions and blockers

- (none yet)
