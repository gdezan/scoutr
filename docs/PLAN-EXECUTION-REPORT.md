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
| 003 | Step 1: `readSlice` loops until the window is full or EOF | SHIPPED | transcript.ts: `bytesRead` accumulate + `buffer.subarray(0, total)` — short reads no longer NUL-pad the JSON parser | | transcript.test.ts: `Buffer.byteLength(text) === file length` + 3 entries parsed | | 5a89196 |
| 003 | Step 2: transcript memo keyed (mtimeMs, size) on the chat read path | SHIPPED | routes/sessions.ts `readTranscriptMemoized` (cap 8, FIFO eviction) — steady-state chat poll costs one stat, not a multi-MB read+parse | STOP-condition check: `extractQuestions` builds only new arrays; `readSession` only slices — sharing the parsed Transcript is safe | session-read.test.ts: unchanged file returns the same entry *objects* (identity, timing-free); append invalidates (size shift) | | 5a89196 |
| 003 | Step 3: catalog memo keyed (mtimeMs, size) per file | SHIPPED | session-catalog.ts `catalogMemo` (cap 600, just above MAX_SCANNED_FILES); `SessionFile.size` recorded in all three stat sites; null results memoized deliberately (readability flips change mtime/size) | | session-catalog.test.ts: unchanged store returns identical metadata; new file shifts keys → fresh listing | | 5a89196 |
| 003 | Step 4: tolerance guards around active-refs stat, nested readdir, addSessionFile | SHIPPED | session-catalog.ts: stat → `continue`, readdir → `continue`, `addSessionFile` body → swallow; a bad entry can no longer abort the whole scan (was: 500 on Sessions tab) | | session-catalog.test.ts: dangling symlink tolerated; unreadable subdir (0o000, root-skipped) skipped | | 5a89196 |
| 003 | Step 5: git-root memo, parallel cwds, reviewRoots TTL with in-flight coalescing | SHIPPED | routes/review.ts: `gitRepoRootCached` per canonical cwd (cap 500, null cached too); `Promise.all` over collected cwds; `REVIEW_ROOTS_TTL_MS = 2_000` global with shared in-flight promise so the Review-open burst shares one scan | **Deviation (user-approved):** the TTL keeps a just-removed workspace reviewable ≤2s; server.test.ts pinned instant revocation and was updated to assert in-window 200 + post-TTL 403 | review.test.ts: concurrent burst all-200; TTL window/expiry legs; server.test.ts revocation test adapted | fresh-agent review finding (concurrent first-wave requests still scanned separately) → in-flight coalescing + burst test | 5a89196 |
| 004 | Step 1: one shared pane→backend resolver | SHIPPED | agents/registry.ts `resolveBackendForPane` (three-step chain, sync, null on miss); commands.ts feeds it the feed snapshot, sessions.ts `await herdr.snapshot()` keeping its throw-404 policy | | | fresh-agent review: clean | 35fc162 |
| 004 | Step 2: validate `send_text` and `steer` | SHIPPED | commands.ts: send_text rejects (not alters) newline/control/length > MAX_ANSWER_LENGTH text; steer rejects NUL/DEL or > MAX_PROMPT_LENGTH, multi-line accepted; `MAX_PROMPT_LENGTH`/`PROMPT_FORBIDDEN_CHAR` exported from sessions.ts | | ws-commands: send_text newline/NUL/empty → 400-class throws; steer multi-line accepted, NUL rejected | fresh-agent review finding (empty steer text hit the legacy message instead of the mandated one) → fixed by separating target/text validation | 35fc162 |
| 004 | Step 3: drop the keys-replay generic fallback | SHIPPED | commands.ts: unknown agents get type+Enter only; keys without text rejected ('requires text for unknown agents') | | ws-commands: unknown+keys rejection; keys-only and keys+text cases moved to the pi backend path | | 35fc162 |
| 004 | Step 4: route-level action validation + capability enforcement | SHIPPED | routes/sessions.ts `CONTROL_ACTIONS` satisfies readonly ControlAction[] (no more `body.action as never`); sessions.ts `backend.capabilities.has(action)` before `backend.control`, 400 naming the backend; abort fast path untouched | **Contract-pin update:** sessions.test.ts capability message pins (outside scope list) updated to the new check — STOP condition checked: CLAUDE_CAPABILITIES genuinely lacks fork/retry, so the pins were the old switch-default message, not a product conflict | sessions-http: unknown action 400, claude+fork 400 with backend named, missing action 400 | | 35fc162 |
| 004 | Step 5: correct status codes from `readSessionRoute` | SHIPPED | routes/sessions.ts throws `SessionsError(..., 403)` for outside-store paths; local try/catch deleted — dispatcher maps 403 and 502 | **Contract-pin update:** server.test.ts path-guard pin 500→403 (plan's own anticipated branch: the pin covered the exact case the plan converts) | sessions-http: outside-store read → 403 | | 35fc162 |
| 004 | Step 6: match answers by `questionIndex` | SHIPPED | questions.ts `byIndex` map per call id; positional fallback only when no answer in the call carries an index | | questions: out-of-order index attach; partially-indexed call never falls back | | 35fc162 |
| 005 | Step 1: lifecycle-scoped polling wrappers on Chat/Board/History VMs | SHIPPED | startPolling()/stopPolling() with a lifecycleActive guard (true no-op when already polling); Poller's immediate first tick doubles as the first paint, so the init one-shot refresh is gone; BoardViewModel.connect() restarts loops only while the board is visible | **Drift (planned at 1ece5c9, before architecture 06):** Poller.kt already shipped the loop mechanics; the plan anticipated this and declared the wrapper shape compatible. Keeping the plan's literal init one-shot was rejected on fresh-agent review: it raced Poller's immediate tick and violated Poller's documented no-first-refresh contract | PollLifecycleTest: no polling until started; second start is a no-op; stop halts; resume restarts | fresh-agent review: High (late health probe resurrected board loops after stop) + Medium (overlapping init refreshes) + Medium (wrappers restarted instead of no-op) — all fixed as prescribed; follow-up review: no findings | 6410595 |
| 005 | Step 2: LifecycleStartEffect wiring in the three screens | SHIPPED | ChatScreen/BoardScreen/HistoryScreen: `LifecycleStartEffect(Unit) { startPolling(); onStopOrDispose { stopPolling() } }` (pattern from LiveOutputPanel); ConnectScreen STOP-condition checked: it owns ConnectViewModel, BoardViewModel only lives under BoardScreen | | emulator suite covers the STARTED path for all three screens | | 6410595 |
| 005 | Step 3: cancellation is never rendered as failure | SHIPPED | all 36 `catch (Exception)` sites in state/ rethrow `CancellationException` first; the 2 IOException sites were already safe (CancellationException is a RuntimeException); no site wraps pure parsing | | PollLifecycleTest.cancelledRefreshDoesNotWriteFailure: scope cancelled mid-refresh → transcript never Loadable.Failed | | 6410595 |
| 005 | Step 4: pushed deep links are validated payload | SHIPPED | service/DeepLink.kt hosts `resolveNotificationLink` + `statusForTitle`; message.click runs through parseCockpitUri and is rebuilt canonically via cockpitChatUri; parsed paneId beats the raw payload field for the reply action; invalid click falls back to raw paneId; neither → notification skipped (matches the old ?: return); CockpitMonitorService.showEventNotification and CockpitApp.showAgentNotification both consume it | **Deferral:** the service notification flow has no unit-test seam until plan 007's DI refactor — the pure helper is tested directly instead (plan-sanctioned helper extraction; DeepLink.kt is its home) | DeepLinkValidationTest (7 cases: foreign click falls back, null without paneId, parsed click beats raw paneId, no-click fallback with title-derived status, scheme rejection) | | 6410595 |
| 006 | — (pending phase 2) | PENDING | | | | | |
| 007 | — (pending phase 2, after 005) | PENDING | | | | | |
| 008 | — (pending phase 2) | PENDING | | | | | |
| 009 | — (pending phase 2) | PENDING | | | | | |

## Verification gates

| Gate | Command | Last run | Result |
|------|---------|----------|--------|
| Bridge typecheck+tests | `cd bridge && npm run typecheck && npm test` | 2026-08-12 (plans 001–004) | 286 tests / 43 suites, 0 fail, 0 skipped (live herdr socket ran); typecheck exit 0 |
| Android unit tests | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` | 2026-08-12 (plan 005) | 156 tests, 0 fail (BUILD SUCCESSFUL) |
| Emulator instrumentation | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest` | 2026-08-12 (plan 005) | 89/89, 0 fail |
| APK assemble | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` | 2026-08-12 (plan 005) | BUILD SUCCESSFUL |

## Assumptions and blockers

- (none yet)

## Deferred notes

- `void line;` in `client.ts`'s subscribe data handler (line ends with `\r` branch)
  is a pre-existing dead statement; plan 009 candidate, deliberately not in 001
  scope.
