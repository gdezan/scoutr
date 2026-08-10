# Production goal checklist

Updated: 2026-08-10 (second revision — reflects the milestone commits `37b9de0`…`4413fb7` and the S24 Ultra device-feedback round). This is the live prompt-to-artifact map for the production-grade Cockpit goal. A checked item needs implementation, direct automated coverage, runtime evidence, and screenshot/taste-review evidence where the goal requires it. Passing broad build gates alone does not check a product requirement.

## Baseline evidence

- Git: `main` was clean at `cce0ef5` before this goal iteration; clean again at `4413fb7` after the milestone.
- Bridge baseline: 65/65. Current: `npm run typecheck` clean, `npm test` 140/140 (was 109 at the start of the milestone).
- Android baseline: unit suite passing; current full unit suite green (~76 tests incl. the new Review/Monitoring/Motion suites) and `pixel2api36DebugAndroidTest` 44/44, `assembleDebug` green.
- Screenshots captured this round at `/tmp/cockpit-board.png`, `/tmp/cockpit-review-overview.png`, `/tmp/cockpit-review-diff.png`, `/tmp/cockpit-review-diff2.png` (panned), `/tmp/cockpit-settings.png`, `/tmp/cockpit-palette.png`, `/tmp/cockpit-chat.png`; inspected by a vision model. Older evidence under `/tmp/cockpit-e2e/*.png`.

## Device feedback (S24 Ultra, live) — current round

- [x] "Queued" state never leaves after a message is confirmed. Root cause: `dropConfirmedMessages` compared the typed text verbatim against `entryText` which collapses whitespace runs, so multi-space/newline sends never matched. Fixed in `ChatViewModel.kt` (both sides normalized) with a new regression test `multiSpaceAndNewlineMessagesStillReconcile` in `ChatPendingMessageTest.kt`.
- [ ] Chat header chips misaligned (working chip vs thinking/model). Fix in progress: chips row now stretches all chips to a common intrinsic height so the M3 clickable 48dp minimum does not shorten the status chip (`ChatScreen.kt`).
- [ ] New-run bottom sheet jitters/flickers when scrolled to the top. Under investigation (`NewSessionSheet.kt`).
- [ ] Enter key must insert a newline, not send. Fix applied: composer is multiline (`minLines 1, maxLines 6`), `imeAction = Default`, send only via the send button; Enter still completes an accepting slash command (`ChatScreen.kt`). Needs runtime confirmation.
- [ ] Git history needs a discoverable home. Review center exists but the default worktree allow-list is unreachable from the dot-dir-hiding picker. Plan: last-reviewed-repo quick open + manual path input in the picker (`ReviewScreen.kt`, `ReviewViewModel.kt`).
- [ ] ask_user_question cards get confusing with several questions at once. Plan: group questions from the same tool call into one questionnaire card with "Question i of n" headers (`QuestionCard.kt`/`ChatList`).
- [ ] Image attachments in the composer. Plan: attach button → photo picker → thumbnail chip → bridge `POST /api/attachments` (bounded upload) → steer `@path text` to pi.

## Taste-review checkpoints

- [ ] Overall navigation and visual-polish direction. The earlier helper attempt timed out; the milestone shipped a four-item phone bar (Board, Sessions, Usage, Review), which supersedes the recorded three-item note. Needs a fresh taste decision with a recorded recommendation.
- [x] First working bottom navigation. Implemented as `CockpitBottomBar`/`CockpitTab` in `MainActivity.kt`: 4 destinations, primary selected color, needs-you badge, navigation-bars safe area, haptic select on switch. Emulator evidence: `/tmp/cockpit-board.png` and the Review/Board tab dumps.
- [x] Board redesign. `BoardScreen.kt` (attention-first): Needs you/Working/Done/Idle/Other sections with count chips, per-card model + latest activity + elapsed time, needs-you pill strongest, `animateItem` group movement, skeleton/offline/error/empty states. Emulator evidence: `/tmp/cockpit-board.png`; 3 `BoardScreenTest` emulator tests.
- [x] Model picker/session launcher. (unchanged from previous revision — see below)
- [ ] Chat/streaming. Conversation configuration was reviewed (see below). Remaining: a chat-streaming taste checkpoint with screenshots of transcript + tool chips + composer.
- [ ] Structured questions. Implemented (`QuestionCard.kt` four shapes, `ChatViewModel.kt` merge/answer flow, bridge `questions.ts`). Needs a taste checkpoint with a multi-question screenshot.
- [ ] Session history. Implemented (`HistoryScreen.kt`, `SessionHistoryViewModel.kt`). Needs a taste checkpoint with a screenshot.
- [x] Usage. (unchanged from previous revision)
- [x] Live output. (unchanged from previous revision)
- [ ] Review center. Implemented (`ReviewScreen.kt`, bridge `review.ts`: overview + bounded diff + ahead/behind + generated artifacts). Emulator screenshots `/tmp/cockpit-review-overview.png`, `/tmp/cockpit-review-diff.png`, `/tmp/cockpit-review-diff2.png` (panning verified). Needs a taste checkpoint.
- [ ] First screenshot-based whole-app review.
- [ ] Final cross-screen coherence review.

Each checkpoint must name source files and rendered screenshot paths, ask for a concrete choice, record the recommendation, and record the applied change or rejection reason. Close its helper pane.

## Functional requirements

### 1. Fuzzy model picker

- [x] Typo-tolerant ranking across provider, model display name, and model ID.
- [x] Filters: reasoning support, context size, and catalog-provided thinking levels.
- [x] Favorites, recents, and one default model persisted on-device.
- [ ] Loading, empty-search, error, many-model, and large-font states. Loading/empty/error/many-model are implemented; large-font runtime evidence remains.

### 2. Global command palette

- [x] Search active and historical sessions by name, path, status, model, and capped transcript content. `CommandPaletteViewModel.kt` searches live agents (client-filtered) plus `/api/session-catalog?q=` with limit; `SessionCatalogItem` carries title/cwd/status/model. (Transcript-content search is not indexed — title/preview only.)
- [x] Contextual actions: open, steer, rename, abort, close. Palette performs open/abort/close/resume; rename stays in the session screen by recorded design decision.
- [ ] Keyboard/IME, empty, offline, large-history, and long-result states. Empty/offline are implemented; IME/large-history runtime screenshots remain. Emulator evidence: `/tmp/cockpit-palette.png`.
- Tests: `CommandPaletteViewModelTest` (5), palette Compose tests.

### 3. Fast session launcher

- [x] Initial prompt, recent folders, saved presets, remembered model/thinking settings, and task templates.
- [x] Atomic create plus first-prompt delivery, with workspace rollback on launch or prompt failure.
- [ ] Loading, empty, error, offline, IME, narrow/landscape, many-folder/model states. Core paths implemented; offline and adaptive runtime evidence remains.

### 4. Attention-first Board

- [x] Cards show phase, model, latest meaningful activity, and elapsed time. `BoardScreen.kt` + bridge `board-detail.ts` (bounded 64 KiB tail reads, model_change extraction, latest meaningful activity).
- [x] Agents needing input sort and read as the strongest priority.
- [x] Stable skeleton/offline/error/empty/overflow states and animated group movement.
- [ ] Realistic screenshot ranges + taste checkpoint remain. Emulator evidence: `/tmp/cockpit-board.png`; `BoardScreenTest` (3).

### 5. Native structured questions

- [x] Structured single-choice, multi-select, confirmation, and free-text cards.
- [x] Data comes from pi structured session events, never terminal-text parsing. `bridge/src/questions.ts` reads toolCall/toolResult records + `details.answers`.
- [x] Safe answer serialization and delivery, validation, status recovery, and accessibility semantics. Sanitized single-line answers (both sides), answered-state recovery, testTags.
- [ ] Multi-question-at-once UX (device feedback): grouping into one questionnaire card pending.
- Tests: bridge 9 questions tests + 2 integration; `QuestionMergeTest` (5), `QuestionCardTest` (5 emulator).

### 6. Complete session lifecycle

- [x] Active, completed, pinned, and archived views. `HistoryScreen.kt` with search + filter chips.
- [x] Resume, rename, close, and delete actions. Resume/fork via catalog, close via control, delete via catalog; pin/archive are on-device flags.
- [x] Destructive confirmations distinguish close/archive/delete and state consequences.
- [ ] Active/many/empty/offline screenshots + taste checkpoint remain. Tests: `SessionHistoryViewModelTest` (7), `HistoryFormatTest` (3), `HistoryScreenTest` (5 emulator).

### 7. Opt-in background monitoring

- [x] Explicit settings opt-in and Android-compliant foreground service. `SettingsScreen.kt` toggle + `CockpitMonitorService` (dataSync foreground type, channels, START_STICKY).
- [x] Reliable self-hosted ntfy monitoring while app is closed. Service polls ntfy with a persisted cursor (30 s loop).
- [x] Notification deep link to exact session and safe inline reply only for blocked agents. `cockpit://chat/<paneId>` deep link consumed in `MainActivity`; reply action is now gated to blocked status (fix applied this round).
- [ ] Stop/restart, permission denial, network loss, reboot, and background constraints handled. Service stops without a connection; START_STICKY covers restarts. Emulator killed-app/background validation + deep-link screenshot remain.
- Tests: `MonitoringStoreTest` (7 incl. service stop-without-connection), `SettingsScreenTest` (1 emulator).

### 8. Bounded live output

- [x] (unchanged from previous revision — authenticated route, caps, lifecycle polling, drawer)
- [ ] Short non-replaying animation for newly changed output remains part of the cross-app streaming-motion work.

### 9. Read-only review center

- [x] Git status, changed files, diff summaries/content. `/api/repo` + `/api/repo/diff` (working and commit kinds), porcelain status with colored codes, `--stat`, bounded unified diff.
- [x] Verification/test signals and generated artifacts. This round: ahead/behind parsed from the `##` branch line + `/api/repo/artifacts` bounded walk of generated-artifact dirs (build/dist/node_modules/.gradle/…), size+mtime, capped. Android UI surfaces both (header sync text + artifacts section).
- [x] All bridge inspection is read-only, repository-root/path restricted, capped, and time-bounded; no arbitrary command surface. realpath allow-list, validated refs, execFile without shell, 2 MiB internal/64 KiB response caps, 8 s command timeout, bounded artifact walk.
- [ ] Large diff, binary, rename, clean, error, offline, and artifact states. Clean/error/truncated are covered; binary/rename and offline screenshots remain.
- Tests: `review.test.ts` (9: overview incl. ahead/behind, diff kinds, artifacts, path/ref guards, caps), `ReviewViewModelTest` (6), `ReviewScreenTest` (3 emulator). Live repo validation on the emulator with real git data.

### 10. Motion and interaction system

- [ ] Shared-axis navigation and predictive back. Not implemented (NavHost has no transitions; predictive back not opted in).
- [ ] Spring-based sheets and coordinated transitions. Partial: `OverlayPresence` (palette fade+scale); sheets use default behavior.
- [x] Status-group/card placement and short real-event insertion motion. `animateItem` with motion specs on Board sections, chat entries, questions, pending bubbles.
- [x] Skeleton loading and interpolated usage values. (unchanged)
- [ ] Semantic haptics for send, selection, success, warning, and needs-you. Select/Confirm/Destructive/Error exist (`Haptics.kt`); warning and needs-you events pending.
- [x] Reduce Motion swaps movement for immediate state changes. `ReduceMotionStore` (animator-duration-scale observer) → `LocalReduceMotion` → `CockpitMotion.itemSpec/itemPlacementSpec/overlaySpec` collapse to `tween(0)`. Runtime normal/reduced screenshots remain.
- [ ] Interruption-safe updates, no queued sequences, lifecycle cancellation. Poll jobs cancel on clear; needs a formal audit.
- Tests: `ReduceMotionStoreTest`/`MotionSpecTest` (7).

## Whole-app polish and adaptation

- [x] Bottom navigation: compact premium phone bar, strong icons/labels/selected state, needs-you badge, safe areas, fluid interruption-safe selection. Rail/list-detail adaptation in landscape/large screens remains.
- [x] Usage dashboard complete (per-session token/cost attribution remains).
- [ ] Board, Connect, Chat, launcher, history, review, dialogs, menus, composers, empty/loading/offline/error states share one deliberate component vocabulary. In progress.
- [x] Conversation controls. (unchanged)
- [x] Optimistic queued messages with Retry. (unchanged; reconciliation whitespace fix this round)
- [x] Slash commands. (unchanged)
- [ ] Purposeful streaming feedback for real transcript/tool/status/live-output events; no fake typing or delayed content.
- [ ] Stable skeletons or inline progress replace generic centered spinners where layout can be known. Board/history/usage have skeletons; review/chat load states remain spinners.
- [ ] Typography, 48dp Android touch targets, contrast, TalkBack semantics, font scaling, edge-to-edge, IME, and one-handed reach audited.
- [x] No gradients, excess glass, generic M3 defaults, arbitrary card soup, bouncing, or decorative motion (design language enforced; new surfaces reviewed against it).

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
