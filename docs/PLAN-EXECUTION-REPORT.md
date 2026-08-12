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
| 04 | `CockpitApi` interface; `BridgeClient : CockpitApi` | PENDING AUDIT | | | | | |
| 04 | ViewModels/container/ReplyReceiver consume `CockpitApi` | PENDING AUDIT | | | | | |
| 04 | `FakeCockpitApi` shared by test source sets; MockWebServer tests migrated | PENDING AUDIT | | | | | |
| 04 | WS path tests; `BridgeException`; auth folded; `call`/`post` private | PENDING AUDIT | | | | | |
| 04 | AGENTS.md gotcha deleted | PENDING AUDIT | | | | | |
| 05 | `SessionAction` enum both sides; call sites converted | PENDING AUDIT | | | | | |
| 05 | `CatalogAction` for resume/fork/rename | PENDING AUDIT | | | | | |
| 05 | Capabilities decoded into UI state; menu rendered from set | PENDING AUDIT | | | | | |
| 05 | Server-side capability enforcement in `controlSession` | PENDING AUDIT | | | | | |
| 06 | `Poller` + tests; four loops migrated | PENDING AUDIT | | | | | |
| 06 | `Loadable` + `FailureKind` (from `BridgeException`); VMs migrated; offline rule explicit | PENDING AUDIT | | | | | |
| 06 | `viewModelFactory` helper; eight factories collapsed | PENDING AUDIT | | | | | |
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
