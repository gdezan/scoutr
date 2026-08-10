# Production goal checklist

Updated: 2026-08-10. This is the live prompt-to-artifact map for the production-grade Cockpit goal. A checked item needs implementation, direct automated coverage, runtime evidence, and screenshot/taste-review evidence where the goal requires it. Passing broad build gates alone does not check a product requirement.

## Baseline evidence

- Git: `main` was clean at `cce0ef5` before this goal iteration.
- Bridge baseline: `cd bridge && npm run typecheck && npm test` — 65/65 passing on 2026-08-10.
- Android baseline: `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` — passing on 2026-08-10; 29 tasks executed. `assembleDebug` succeeds.
- Existing screenshots: `/tmp/cockpit-e2e/*.png`. Directly inspected `28-final-bottom.png`, `29-board-v2.png`, and `33-phone-final.png`.
- Existing UI evidence is incomplete for this goal: there are no current screenshots for History, model search/filters, structured questions, Usage details, live output, review center, notification deep links, or Reduce Motion.

## Taste-review checkpoints

- [ ] Overall navigation and visual-polish direction. Attempted with `taste-nav` against `MainActivity.kt`, `BoardScreen.kt`, `Theme.kt`, `DESIGN.md`, the Cursor brief, and screenshots 28/29/33. `herdr agent start` first failed with `agent_pane_busy`; the retry started Claude but `herdr agent prompt --wait --timeout 180000` timed out. The helper pane was closed. Local decision pending implementation: use a three-destination phone bar (Board, Sessions, Usage), keep Review contextual to a session, expose the global palette from the app bar, and use a compact rail plus contextual review pane on wide screens. This preserves the running-agent anchor and avoids an overloaded four-item phone bar.
- [ ] First working bottom navigation.
- [ ] Board redesign.
- [x] Model picker/session launcher. Direct visual review of `/tmp/cockpit-e2e/53-launcher-final.png`, `48-model-picker-live.png`, and `49-model-search-live.png`: retained the prompt-first launcher, focused full-screen pickers, sticky primary action, compact metadata rows, and restrained single-accent styling. The prior Claude helper timed out, so per the taste-review fallback this model made and applied the call directly.
- [ ] Chat/streaming. Conversation configuration was directly reviewed on emulator screenshots `/tmp/cockpit-e2e/46-config-chat-header-emulator.png` and `47-conversation-config-emulator.png`: the title keeps its own line, active model and thinking are visible as grouped chips, and an explicit setup sheet replaces cycle-thinking. Optimistic queued messages and slash commands remain.
- [ ] Structured questions.
- [ ] Session history.
- [ ] Usage.
- [x] Live output. Claude taste review (`taste-live`) read `LiveOutputPanel.kt`, `ChatScreen.kt`, `Theme.kt`, and `/tmp/cockpit-e2e/47-live-output-success.png`; it chose the strip plus reserved drawer over an inline card or modal sheet. Applied: the drawer now shrinks rather than covers the transcript, keeps scroll-to-end available, computes visible lines from height, labels stale/truncated states, previews the latest meaningful line, and exposes a 48dp accessible toggle. Helper pane `w4Q:p3N` was closed. Final inspected screenshot: `/tmp/cockpit-e2e/49-live-output-final.png`.
- [ ] Review center.
- [ ] First screenshot-based whole-app review.
- [ ] Final cross-screen coherence review.

Each future checkpoint must name source files and rendered screenshot paths, ask for a concrete choice, record the recommendation, and record the applied change or rejection reason. Close its helper pane.

## Functional requirements

### 1. Fuzzy model picker

- [x] Typo-tolerant ranking across provider, model display name, and model ID.
- [x] Filters: reasoning support, context size, and catalog-provided thinking levels.
- [x] Favorites, recents, and one default model persisted on-device.
- [ ] Loading, empty-search, error, many-model, and large-font states. Loading/empty/error/many-model are implemented; large-font runtime evidence remains.
- Current evidence: `ModelPickerSearch.kt`, `LauncherSettingsStore.kt`, `SessionPickers.kt`, and `NewSessionViewModel.kt`; live catalog screenshots at `/tmp/cockpit-e2e/48-model-picker-live.png` and `49-model-search-live.png`.
- Tests: ranking/filter and persistence unit tests, ViewModel tests, launcher Compose tests, bridge/Android build gates. Large-font Compose/runtime coverage remains.

### 2. Global command palette

- [ ] Search active and historical sessions by name, path, status, model, and capped transcript content.
- [ ] Contextual actions: open, steer, rename, abort, close.
- [ ] Keyboard/IME, empty, offline, large-history, and long-result states.
- Current evidence: no session catalog/search API or palette UI.
- Required artifacts: bounded bridge session index/search API, Android palette state/UI, tests for caps/path guards/actions, runtime search screenshot, taste evidence through the relevant surface review.

### 3. Fast session launcher

- [x] Initial prompt, recent folders, saved presets, remembered model/thinking settings, and task templates.
- [x] Atomic create plus first-prompt delivery, with workspace rollback on launch or prompt failure.
- [ ] Loading, empty, error, offline, IME, narrow/landscape, many-folder/model states. Core loading/empty/error/IME/many-item paths are implemented; offline and adaptive runtime evidence remains.
- Current evidence: `sessions.ts#createSession` validates a home-rooted cwd, starts pi with one `pane.send_input`, waits for Herdr agent detection, sends the exact prompt through `agent.prompt`, and closes the workspace on failure. Android persists launcher settings and consumes successful navigation once.
- Tests/evidence: bridge orchestration, validation, malformed-body, rollback, multiline/option-like prompt tests; Android persistence/ViewModel/Compose tests; live exact multiline prompt at `/tmp/cockpit-e2e/51-live-create-output.txt`; final launcher screenshot `/tmp/cockpit-e2e/53-launcher-final.png`.

### 4. Attention-first Board

- [ ] Cards show phase, model, latest meaningful activity, and elapsed time.
- [ ] Agents needing input sort and read as the strongest priority.
- [ ] Stable skeleton/offline/error/empty/overflow states and animated group movement.
- Current evidence: `BoardScreen.kt` groups status and shows title/path/status time. Model, phase vocabulary, latest activity, skeletons, and group-movement evidence are missing.
- Required bridge/API work: derive model and bounded latest meaningful transcript activity without unbounded reads.
- Required tests/screenshots/taste review: board mapping/sorting, Compose states, rapid status changes, realistic screenshot ranges.

### 5. Native structured questions

- [ ] Structured single-choice, multi-select, confirmation, and free-text cards.
- [ ] Data comes from pi structured session events, never terminal-text parsing.
- [ ] Safe answer serialization and delivery, validation, status recovery, and accessibility semantics.
- Current evidence: `bridge/src/pi/session.ts` drops `custom` records; blocked pane answers type free text plus Enter. No structured DTO or card.
- Required tests: parser fixtures for all variants, response mapping, bridge route/WS tests, ViewModel/UI tests, live ask/answer loop, screenshot, taste review.

### 6. Complete session lifecycle

- [ ] Active, completed, pinned, and archived views.
- [ ] Resume, rename, close, and delete actions.
- [ ] Destructive confirmations distinguish close/archive/delete and state consequences.
- Current evidence: chat controls cover abort/retry/compact/fork/rename/cycle thinking. There is no session-history/catalog screen, pin/archive state, close, or delete.
- Required tests: catalog classification/persistence, path restriction, lifecycle actions, confirmations, active/many/empty/offline screenshots, taste review.

### 7. Opt-in background monitoring

- [ ] Explicit settings opt-in and Android-compliant foreground service.
- [ ] Reliable self-hosted ntfy monitoring while app is closed.
- [ ] Notification deep link to exact session and safe inline reply only for blocked agents.
- [ ] Stop/restart, permission denial, network loss, reboot, and background constraints handled.
- Current evidence: `BoardViewModel` polls while the app process is alive. `CockpitApp.kt` notifications open `MainActivity` generically; no service, deep link, or `RemoteInput`.
- Required tests/evidence: service lifecycle/unit tests, notification intent tests, emulator background/killed-app validation, exact deep-link screenshot, foreground-service disclosure and security audit.

### 8. Bounded live output

- [x] Authenticated bridge route uses `HerdrClient.agentRead` with fixed `visible`/text/ANSI-stripped settings, 1–120 line validation, a 48 KiB cap, 3-second timeout, and current-agent pane targeting.
- [x] Android retains only the capped response, polls every 1.5 seconds only while the drawer is expanded and the lifecycle is started, cancels on collapse/background, and reserves layout space so transcript scroll state and controls remain reachable.
- [ ] Loading, empty, error, offline, truncated, and success states are implemented and covered; short non-replaying animation for newly changed output remains part of the cross-app streaming-motion work.
- Current evidence: `bridge/src/live-output.ts`, authenticated `GET /api/agents/:paneId/read`, `BridgeClient.liveOutput`, `ChatViewModel` lifecycle polling, and `LiveOutputPanel.kt`. A real working pi pane was read successfully after switching from history-only `recent_unwrapped` to `visible`.
- Tests/evidence: `bridge/test/live-output.test.ts`, Herdr socket-cancellation and server route coverage, `ChatLiveOutputViewModelTest.kt`, `LiveOutputPanelTest.kt`, and `ChatControlsTest.kt`; bridge 82/82 tests, Android unit/build gates, and targeted emulator Compose tests passed. The deployed bridge revalidated the agent with `agent.get` and returned 2,481 bounded bytes from real pane `w4Q:pG`. Inspected screenshots: `/tmp/cockpit-e2e/47-live-output-success.png`, `48-live-output-taste-applied.png`, and `49-live-output-final.png`.

### 9. Read-only review center

- [ ] Git status, changed files, diff summaries/content, verification/test results, and generated artifacts.
- [ ] All bridge inspection is read-only, repository-root/path restricted, capped, and time-bounded; no arbitrary command surface.
- [ ] Large diff, binary, rename, clean, error, offline, and artifact states.
- Current evidence: no Git/review API or UI.
- Required tests: command allow-list/path traversal/output/time cap tests, parser tests, Compose review tests, live repository validation, screenshots, taste review.

### 10. Motion and interaction system

- [ ] Shared-axis navigation and predictive back.
- [ ] Spring-based sheets and coordinated transitions.
- [ ] Status-group/card placement and short real-event insertion motion.
- [ ] Skeleton loading and interpolated usage values that continue from the previous value.
- [ ] Semantic haptics for send, selection, success, warning, and needs-you.
- [ ] Reduce Motion swaps movement for immediate/cross-fade state changes.
- [ ] Interruption-safe updates, no queued sequences, lifecycle cancellation.
- Current evidence: chat has `animateItem` and one `AnimatedVisibility`; no app-wide motion policy, predictive back, haptics, Reduce Motion, or skeleton system.
- Required tests: motion-policy unit tests, semantics/UI tests, runtime normal/reduced-motion screenshots and rapid-update/background checks.

## Whole-app polish and adaptation

- [ ] Bottom navigation: compact premium phone bar, strong icons/labels/selected state, needs-you badge, safe areas, fluid interruption-safe selection, rail/list-detail adaptation in landscape/large screens.
- [ ] Usage dashboard: hierarchy, provider cards, horizontal progress bars with prior-value interpolation, reset-time and warning thresholds, empty/error states, per-session tokens/cost.
- [ ] Board, Connect, Chat, launcher, history, review, dialogs, menus, composers, empty/loading/offline/error states share one deliberate component vocabulary.
- [x] Conversation controls: the header keeps the session title legible on its own line, shows active status/model/thinking as grouped chips, and opens a focused setup sheet with exact thinking-level selection plus fuzzy model search. Bridge controls validate exact catalog models and cycle from the transcript-reported active thinking level; lifecycle actions remain in a separate labeled menu. Evidence: `ConversationConfigSheet.kt`, `ChatViewModel.kt`, `sessions.ts`, `ChatControlsTest.kt`, and emulator screenshots `/tmp/cockpit-e2e/46-config-chat-header-emulator.png` and `47-conversation-config-emulator.png`.
- [x] Sent messages appear optimistically in the transcript at once, remain visibly queued until the session JSONL confirms them, reconcile by fresh entry ID plus exact text without duplicate rows, and retain an actionable `Not sent · Retry` state on delivery failure. Evidence: `PendingUserMessage` and `dropConfirmedMessages` in `ChatViewModel.kt`, delivery UI in `ChatScreen.kt`, `ChatPendingMessageTest.kt`, `ChatListTest.kt`, and directly inspected emulator screenshots `/tmp/cockpit-e2e/49-message-queued-emulator.png` and `50-message-failed-emulator.png`.
- [ ] Slash commands are functional and discoverable: typing `/` opens autocomplete for built-in pi commands and installed skills; filtering, keyboard/touch selection, exact safe delivery, loading/empty/error/no-match, and long-list states are covered.
- [ ] Purposeful streaming feedback for real transcript/tool/status/live-output events; no fake typing or delayed content.
- [ ] Stable skeletons or inline progress replace generic centered spinners where layout can be known.
- [ ] Typography, 48dp Android touch targets, contrast, TalkBack semantics, font scaling, edge-to-edge, IME, and one-handed reach audited.
- [ ] No gradients, excess glass, generic M3 defaults, arbitrary card soup, bouncing, or decorative motion.

Direct screenshot observations from the current build: the Board bottom bar is a tall stock Material bar with only Board/Usage, weak hierarchy, and an incongruent green selected label; the Board leaves excessive vertical space, cards omit model/activity, and the large title competes with agent state. Chat is more coherent but tool cards dominate the transcript. Connect has excessive top dead space, stock fields/buttons, and weak disabled-state contrast.

## Verification gates before completion

- [ ] `cd bridge && npm run typecheck && npm test` fresh.
- [ ] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` fresh.
- [ ] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest --rerun-tasks` fresh.
- [ ] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` fresh enough to produce/install the final APK.
- [ ] Emulator runtime matrix for each feature: loading, empty, success, error, offline, overflow, background, destructive action.
- [ ] Screenshots directly inspected for bottom nav, Board, model picker, launcher, structured question, Chat streaming, history, Usage, live output, review, notification deep link, and Reduce Motion.
- [ ] Stress checks: long transcripts, many models/sessions, large diffs, narrow/landscape/large screens, large font, IME, rapid status/reconnects/interrupted motion.
- [ ] Accessibility/security/performance audit: semantics, touch targets, contrast, font scaling, motion reduction, bounded work, lifecycle cancellation, list stability, recomposition, read-only auth/model/Git handling, no raw herdr socket exposure.
- [ ] `/simplify` before every commit and final completion audit against this document and real evidence.
- [ ] Final report maps every checked item to implementation files, tests, runtime evidence, screenshots, taste decisions, and limitations.
