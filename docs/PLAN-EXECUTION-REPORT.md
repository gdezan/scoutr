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
| 07 | `Destination` in `ui/nav/Destination.kt` with `routes`/`forRoute` | SHIPPED | ui/nav/Destination.kt: enum Board/Sessions/Usage/Review with `routes` set + `forRoute`; Routes in MainActivity trimmed to connect/chat/live/settings | | | | | | |
| 07 | Bottom-bar check derived; `Routes` trimmed | SHIPPED | CockpitAppNav bar visibility is `currentRoute in Destination.routes`; tab routes flow through `Destination.X.route` everywhere (startDestination, popUpTo, composable(), onTab) | | | | | | |
| 07 | `TabScaffold` extracted; four tabs use it | SHIPPED | ui/nav/TabScaffold.kt (zero-inset Scaffold + AppTopBar, optional FAB slot); Board/Sessions/Usage/Review blocks all use it; AppTopBar moved to ui/components/AppTopBar.kt (was private in MainActivity) | | | | | | |
| 07 | Unit tests (routes/forRoute) + NavHost graph test | SHIPPED | DestinationTest (4 unit tests); NavHostGraphTest launches the real MainActivity (seeded connection + monitoring reset), asserts 4 tabs and tap-through; inset contract verified structurally + on-device (GMD 89/89) | | |
| 07 | POST_NOTIFICATIONS gated on monitoring (test blocker fix) | SHIPPED | MainActivity requests the permission only when MonitoringStore.enabled; Settings toggle requests it before starting the FGS (was: dialog on every cold start, and a FGS SecurityException if granted never arrived); enables real-activity NavHost tests | | | | | |

## Requirement matrix — implementation plans (plans/)

| Plan | Requirement | Status | Source evidence | Changes | Tests | Review | Commit |
|------|-------------|--------|-----------------|---------|-------|--------|--------|
| 001 | Step 1: `herdrSubscribe` settles on every failure path (error/close-before-ack/ack-timeout/error-ack) instead of wedging | SHIPPED | client.ts `settled` flag + `fail()` (clears ackTimeout, sets `closed` to suppress onClose, destroys, rejects); post-resolve errors still call `callbacks.onError` | **Deviation:** resolve happens on a *parsed ack* inside the data handler, not the plan's literal `sock.once("data")` — the plan's done criterion says to confirm the resolve path coexists with the reject paths; intent satisfied | 6 offline subscribe tests (missing path, close-before-ack, stall→3s reject, bad ack, late-ack-after-stop, feed retry) + live suite (0 skipped) | fresh-agent review: strict ack validation (id echo + `subscription_started`, live-probed) | a844b69 |
| 001 | Step 2: `herdrRequest` rejects on empty close | SHIPPED | client.ts `finish()`: `first === undefined` guard rejects with "herdr closed the connection without responding" (was: parse `"{}"` → resolve `undefined`) | | offline test: close-with-no-bytes rejects | | a844b69 |
| 001 | Step 3: feed retries failed rebuild with backoff; retry handle stored and cleared in `stop()` | SHIPPED | feed.ts `SUBSCRIBE_RETRY_MS = 1000`; `retryTimer` field; subscribe wrapped in try/catch emitting `feed_error` + scheduling retry guarded by `stopped`; **review hardening:** `rebuildTimer` + `reconnectTimer` also tracked and cleared in `stop()` (stale timers cannot rebuild a restarted feed) | | offline test: restart after socket death → exactly 2 subscribes, no spurious rebuild | fresh-agent review finding 3 fixed | a844b69 |
| 001 | Step 4: snapshot re-synced at top of `doBuildSubscription` | SHIPPED | feed.ts `try { await this.refreshSnapshot(true) } catch {}` then re-check `stopped`; **review hardening:** `refreshSeq` generation guard so an older in-flight refresh cannot overwrite a newer snapshot | | | fresh-agent review finding 4 fixed | a844b69 |
| 001 | Step 5: crash-proof HTTP/WS handlers | SHIPPED | server.ts: async `createServer` callback wrapped (500 JSON only if `!response.writableEnded`, secondary write failures swallowed); `wss.on("error")` + `ws.on("error")` (log + same cleanup as close); `handleFeed` guards `ws.send` with `readyState === OPEN` | | server.test.ts offline HTTP suite exercises the path | | a844b69 |
| 001 | Step 6: pane-close kind sets + tracker prune | SHIPPED | server.ts `STATUS_KINDS`/`CLOSE_KINDS` explicit snake+dot sets (no blind `replace(/_/g,'.')`); close branch runs `tracker.prune(live pane ids)` + `boardDetail.prune(snapshotPaths(...))`; `grep -n tracker.prune src/server.ts` → exactly 1 match | **Deviation:** server-level prune assertion in `server.test.ts` per the plan's explicit alternative (test-plan item 5), instead of `status.test.ts` | server.test.ts: pane_exited + pane_agent_status_changed prune tests | reviewer instructed not to flag server.test.ts (plan permits it) | a844b69 |
| 002 | Step 1: narrow the config catch — only missing/unparseable mints a token; a failed re-persist of a valid config keeps the token | SHIPPED | config.ts `readOk` flag; write failure with `readOk` → log + return parsed config; fresh-config write failure → throw (nothing to pair against) | | config.test.ts: file made read-only (0o400) so `writeFile` genuinely fails EACCES; token + port preserved; warning emitted (grep-verified) | fresh-agent review: test did not reach the failure branch (dir chmod does not block truncation of an existing file) → fixed by chmod-ing the file itself | 569acbb |
| 002 | Step 2: remove the token from the serve banner | SHIPPED | cli.ts: `token: run 'cockpit-bridge pair' or read <path>`; `grep -rn 'token: ${config.token}' bridge/src/` → nothing | | | | 569acbb |
| 002 | Step 3: restrict query-token auth to the WS upgrade | SHIPPED | dispatcher.ts `isAuthorized(request, token, opts: { allowQueryToken? })`; server.ts upgrade passes `{ allowQueryToken: true }`; `dispatchRoute` passes nothing | STOP-condition check: BridgeClient.kt uses `?token=` only for the WS connect (line 310), all HTTP via Bearer header | server.test.ts: `GET /api/health?token=<token>` → 401; existing ws test still asserts the query-token upgrade succeeds | | 569acbb |
| 002 | Step 4: Android backup exclusion for the connection prefs | SHIPPED | `res/xml/backup_rules.xml` + `data_extraction_rules.xml` exclude `sharedpref cockpit_connection.xml`; manifest carries both attributes; prefs file name grep-verified against ConnectionStore.kt | | Android unit tests (BUILD SUCCESSFUL) + assembleDebug — manifest/XML change, no new unit tests per plan | | 569acbb |
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
| Bridge typecheck+tests | `cd bridge && npm run typecheck && npm test` | 2026-08-12 (plans 001–002) | 267 tests / 42 suites, 0 fail, 0 skipped (live herdr socket ran); typecheck exit 0 |
| Android unit tests | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` | 2026-08-12 (plan 002) | BUILD SUCCESSFUL |
| Android unit tests | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` | — | PENDING |
| Emulator instrumentation | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest` | — | PENDING |
| APK assemble | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` | — | PENDING |

## Assumptions and blockers

- (none yet)

## Deferred notes

- `void line;` in `client.ts`'s subscribe data handler (line ends with `\r` branch)
  is a pre-existing dead statement; plan 009 candidate, deliberately not in 001
  scope.
