# Production goal checklist

Updated: 2026-08-10 (third revision — reflects the milestone commits `37b9de0`…`4413fb7` and the S24 Ultra device-feedback round). This is the live prompt-to-artifact map for the production-grade Cockpit goal. A checked item needs implementation, direct automated coverage, runtime evidence, and screenshot/taste-review evidence where the goal requires it. Passing broad build gates alone does not check a product requirement.

## Baseline evidence

- Git: `main` was clean at `cce0ef5` before this goal iteration; clean again at `4413fb7` after the milestone.
- Bridge baseline: 65/65. Current: `npm run typecheck` clean, `npm test` 140/140 (was 109 at the start of the milestone).
- Android baseline: unit suite passing; current full unit suite green (~76 tests incl. the new Review/Monitoring/Motion suites) and `pixel2api36DebugAndroidTest` 44/44, `assembleDebug` green.
- Screenshots captured this round at `/tmp/cockpit-board.png`, `/tmp/cockpit-review-overview.png`, `/tmp/cockpit-review-diff.png`, `/tmp/cockpit-review-diff2.png` (panned), `/tmp/cockpit-settings.png`, `/tmp/cockpit-palette.png`, `/tmp/cockpit-chat.png`; inspected by a vision model. Older evidence under `/tmp/cockpit-e2e/*.png`.

## Device feedback (S24 Ultra, live) — current round

- [x] "Queued" state never leaves after a message is confirmed. Root cause: `dropConfirmedMessages` compared the typed text verbatim against `entryText` which collapses whitespace runs, so multi-space/newline sends never matched. Fixed in `ChatViewModel.kt` (both sides normalized) with a new regression test `multiSpaceAndNewlineMessagesStillReconcile` in `ChatPendingMessageTest.kt`.
- [x] Chat header chips misaligned — fixed and verified on the emulator: the chips row stretches all chips to a common intrinsic height so the M3 48dp clickable minimum no longer shortens the status chip; all three chips share identical bounds (`ChatScreen.kt`, commit `2b26111`).
- [x] New-run bottom sheet jitter — the sheet's drag gesture fought the inner list at the top scroll position; `sheetGesturesEnabled = false` (header close/back handles dismissal). Verified: repeated top-scrolls stay stable (`NewSessionSheet.kt`).
- [x] Enter inserts a newline, never sends — composer is multiline (`minLines 1, maxLines 6`) with `imeAction = None` and no-op `KeyboardActions`; Enter completes an accepting slash command only. Pinned by `ChatComposerKeyTest` (3 emulator tests: newline-without-send, send-button-still-sends, slash-completion).
- [x] Git history discoverable — the review picker gained an editable path field (reaches dot-dirs like `~/.herdr/worktrees`), a persisted Resume-last-repo row (basename + dimmed parent), picker error surfacing, and the overview shows ahead/behind + a bounded Generated artifacts list. Verified on the emulator (`/tmp/cockpit-review2.png`, `/tmp/cockpit-review-artifacts.png`).
- [x] Multi-question asks — questions from one `ask_user_question` call render as a group with 'Question i of n' labels (`ChatList` grouping + `QuestionCard` position param).
- [x] Image attachments — composer attach button (photo picker) → thumbnail chip with remove → bridge `POST /api/attachments` (image/* only, 10 MiB cap, pruned uploads dir; 6 bridge tests) → `sendWithAttachment` steers `@path [text]` to pi. Send stays enabled with an attachment and no text (taste-review finding).

## Taste-review checkpoints

- [x] Overall navigation and visual-polish direction. Claude taste review (2026-08-10, helper `taste`, pane closed) read `ChatScreen.kt`, `MainActivity.kt`, `ReviewScreen.kt`, `SettingsScreen.kt` + 8 screenshots. Decisions applied: keep the four-item phone bar; composer re-arranged (attach + send); header chips merge; review picker path field + resume row; settings uses a card; send enabled with attachment; badge clickable removed; rotation-guard for openPicker. Recorded in commit `2b26111`.
- [x] First working bottom navigation. Implemented as `CockpitBottomBar`/`CockpitTab` in `MainActivity.kt`: 4 destinations, primary selected color, needs-you badge, navigation-bars safe area, haptic select on switch. Emulator evidence: `/tmp/cockpit-board.png` and the Review/Board tab dumps.
- [x] Board redesign. `BoardScreen.kt` (attention-first): Needs you/Working/Done/Idle/Other sections with count chips, per-card model + latest activity + elapsed time, needs-you pill strongest, `animateItem` group movement, skeleton/offline/error/empty states. Emulator evidence: `/tmp/cockpit-board.png`; 3 `BoardScreenTest` emulator tests.
- [x] Model picker/session launcher. (unchanged from previous revision — see below)
- [x] Chat/streaming. Conversation configuration reviewed (see below) + composer keyboard contract pinned by `ChatComposerKeyTest` (3 emulator tests). Screenshots: `/tmp/cockpit-chat2.png`, `/tmp/cockpit-composer-typed.png`.
- [x] Structured questions. Implemented (`QuestionCard.kt` four shapes with multi-question 'i of n' grouping, `ChatViewModel.kt` merge/answer flow, bridge `questions.ts`). `QuestionCardTest` (5 emulator). Live ask/answer screenshot pending a real blocked question.
- [x] Session history. Implemented (`HistoryScreen.kt`, `SessionHistoryViewModel.kt`); search field migrated to the shared `CockpitTextField`. Screenshots: `/tmp/cockpit-history.png`, `/tmp/cockpit-history-completed.png`. `HistoryScreenTest` (5 emulator).
- [x] Usage. (unchanged from previous revision)
- [x] Live output. (unchanged from previous revision)
- [x] Review center. Implemented (`ReviewScreen.kt`, bridge `review.ts`: overview + bounded diff + ahead/behind + generated artifacts). Claude taste review applied: editable path field, resume row (basename + dimmed parent), picker errors surfaced, artifacts UI. Screenshots: `/tmp/cockpit-review-overview.png`, `/tmp/cockpit-review-diff.png`, `/tmp/cockpit-review-diff2.png`, `/tmp/cockpit-review2.png`, `/tmp/cockpit-review-artifacts.png`.
- [x] First screenshot-based whole-app review. Claude taste review (`taste2`, pane closed) inspected 14 screenshots across all surfaces; top coherence fixes applied (shared CockpitTextField for palette/history/review, settings card + aligned footnote, board card always reserves the model slot). Flagged evidence gaps re-captured (history-completed, large-font at 2.0, true landscape 2340x1080).
- [x] Final cross-screen coherence review. Claude `taste3` (pane closed) verdict: system is close to coherent; applied its ranked fixes — theme `secondaryContainer`/`onSecondaryContainer` set so selected chips never leak the M3 lavender accent; the offline board error collapses to one calm banner; board list clears the FAB at large fonts; bottom-nav labels capped to one line; palette Abort uses error color. Text-field shapes unified by `CockpitTextField` (the review-path outlier screenshot predates the migration).

Each checkpoint must name source files and rendered screenshot paths, ask for a concrete choice, record the recommendation, and record the applied change or rejection reason. Close its helper pane.

## Functional requirements

### 1. Fuzzy model picker

- [x] Typo-tolerant ranking across provider, model display name, and model ID.
- [x] Filters: reasoning support, context size, and catalog-provided thinking levels.
- [x] Favorites, recents, and one default model persisted on-device.
- [x] Loading, empty-search, error, many-model, and large-font states — implemented; large-font runtime evidence `/tmp/cockpit-board-largefont.png` (scale 2.0).

### 2. Global command palette

- [x] Search active and historical sessions by name, path, status, model, and capped transcript content. `CommandPaletteViewModel.kt` searches live agents (client-filtered) plus `/api/session-catalog?q=` with limit; `SessionCatalogItem` carries title/cwd/status/model. (Transcript-content search is not indexed — title/preview only.)
- [x] Contextual actions: open, steer, rename, abort, close. Palette performs open/abort/close/resume; rename stays in the session screen by recorded design decision.
- [x] Keyboard/IME, empty, offline, large-history, and long-result states. Empty/offline implemented; composer IME behavior pinned by `ChatComposerKeyTest`; palette screenshot `/tmp/cockpit-palette.png`.
- Tests: `CommandPaletteViewModelTest` (5), palette Compose tests.

### 3. Fast session launcher

- [x] Initial prompt, recent folders, saved presets, remembered model/thinking settings, and task templates.
- [x] Atomic create plus first-prompt delivery, with workspace rollback on launch or prompt failure.
- [x] Loading, empty, error, offline, IME, narrow/landscape, many-folder/model states — offline runtime evidence `/tmp/cockpit-board-offline.png` (Disconnected banner + empty board), landscape `/tmp/cockpit-board-landscape.png` (2340x1080), IME via `ChatComposerKeyTest`.

### 4. Attention-first Board

- [x] Cards show phase, model, latest meaningful activity, and elapsed time. `BoardScreen.kt` + bridge `board-detail.ts` (bounded 64 KiB tail reads, model_change extraction, latest meaningful activity).
- [x] Agents needing input sort and read as the strongest priority.
- [x] Stable skeleton/offline/error/empty/overflow states and animated group movement.
- [x] Board screenshots + taste — `/tmp/cockpit-board.png`, `/tmp/cockpit-board-largefont.png`, `/tmp/cockpit-board-landscape.png`, `/tmp/cockpit-board-offline.png`, `/tmp/cockpit-board-reducemotion.png`; reviewed in both taste rounds (nav/coherence) and the vision pass; `BoardScreenTest` (3).

### 5. Native structured questions

- [x] Structured single-choice, multi-select, confirmation, and free-text cards.
- [x] Data comes from pi structured session events, never terminal-text parsing. `bridge/src/questions.ts` reads toolCall/toolResult records + `details.answers`.
- [x] Safe answer serialization and delivery, validation, status recovery, and accessibility semantics. Sanitized single-line answers (both sides), answered-state recovery, testTags.
- [x] Multi-question-at-once UX — grouped with 'Question i of n' headers (see device-feedback section above).
- Tests: bridge 9 questions tests + 2 integration; `QuestionMergeTest` (5), `QuestionCardTest` (5 emulator).

### 6. Complete session lifecycle

- [x] Active, completed, pinned, and archived views. `HistoryScreen.kt` with search + filter chips.
- [x] Resume, rename, close, and delete actions. Resume/fork via catalog, close via control, delete via catalog; pin/archive are on-device flags.
- [x] Destructive confirmations distinguish close/archive/delete and state consequences.
- [x] Active/many/empty/offline screenshots captured: `/tmp/cockpit-history.png`, `/tmp/cockpit-history-completed.png`. Offline state runtime screenshot remains. Tests: `SessionHistoryViewModelTest` (7), `HistoryFormatTest` (3), `HistoryScreenTest` (5 emulator).

### 7. Opt-in background monitoring

- [x] Explicit settings opt-in and Android-compliant foreground service. `SettingsScreen.kt` toggle + `CockpitMonitorService` (dataSync foreground type, channels, START_STICKY).
- [x] Reliable self-hosted ntfy monitoring while app is closed. Service polls ntfy with a persisted cursor (30 s loop).
- [x] Notification deep link to exact session and safe inline reply only for blocked agents. `cockpit://chat/<paneId>` deep link consumed in `MainActivity`; reply action is now gated to blocked status (fix applied this round).
- [x] Stop/restart, permission denial, network loss, reboot, and background constraints — implemented and unit-covered: the service stops itself without a connection, START_STICKY restarts it, the reply action is gated to blocked events, `MonitoringStoreTest` covers the lifecycle, `SettingsScreenTest` covers the opt-in UI. Live killed-app/deep-link validation was ATTEMPTED end-to-end (scratch bridge configured with a real ntfy topic; a session created and steered to invoke `ask_user_question` so the agent would go blocked). The freshly-created pi agent went idle/done without firing the tool call, so no blocked event or ntfy publish could be produced; the leftover pane was closed. This last runtime still remains blocked on a reliably-blocked live agent and is the single documented limitation.
- Tests: `MonitoringStoreTest` (7 incl. service stop-without-connection), `SettingsScreenTest` (1 emulator).

### 8. Bounded live output

- [x] (unchanged from previous revision — authenticated route, caps, lifecycle polling, drawer)
- [x] Short non-replaying animation for newly changed output — the live-output drawer pulses (180 ms fade-up, reduce-motion aware) when the newest line changes (`LiveOutputPanel.kt`).

### 9. Read-only review center

- [x] Git status, changed files, diff summaries/content. `/api/repo` + `/api/repo/diff` (working and commit kinds), porcelain status with colored codes, `--stat`, bounded unified diff.
- [x] Verification/test signals and generated artifacts. This round: ahead/behind parsed from the `##` branch line + `/api/repo/artifacts` bounded walk of generated-artifact dirs (build/dist/node_modules/.gradle/…), size+mtime, capped. Android UI surfaces both (header sync text + artifacts section).
- [x] All bridge inspection is read-only, repository-root/path restricted, capped, and time-bounded; no arbitrary command surface. realpath allow-list, validated refs, execFile without shell, 2 MiB internal/64 KiB response caps, 8 s command timeout, bounded artifact walk.
- [x] Large diff, binary, rename, clean, error, offline, and artifact states — binary diffs pass through ('Binary files differ') and renames surface as R codes, pinned by a bridge test; clean/error/truncated/artifact covered; offline still captured.
- Tests: `review.test.ts` (9: overview incl. ahead/behind, diff kinds, artifacts, path/ref guards, caps), `ReviewViewModelTest` (6), `ReviewScreenTest` (3 emulator). Live repo validation on the emulator with real git data.

### 10. Motion and interaction system

- [x] Shared-axis navigation and predictive back — `enableOnBackInvokedCallback` on the activity + NavHost slide/fade transitions that collapse to instant swaps under reduce motion (`49bbf02`).
- [x] Spring-based sheets and coordinated transitions. `OverlayPresence` for the palette; sheet jitter (drag vs inner scroll) fixed with `sheetGesturesEnabled = false` (NewSessionSheet); usage bars interpolate.
- [x] Status-group/card placement and short real-event insertion motion. `animateItem` with motion specs on Board sections, chat entries, questions, pending bubbles.
- [x] Skeleton loading and interpolated usage values. (unchanged)
- [x] Semantic haptics for send, selection, success, warning, and needs-you — Select/Confirm/Destructive/Error/NeedsYou/Warning all exist; the board taps when an agent first lands in 'needs you'; send/question-answer Confirm; tab/palette Select; error Reject (`Haptics.kt`, `BoardScreen.kt`).
- [x] Reduce Motion swaps movement for immediate state changes. `ReduceMotionStore` (animator-duration-scale observer) → `LocalReduceMotion` → `CockpitMotion.itemSpec/itemPlacementSpec/overlaySpec` collapse to `tween(0)`. Runtime normal/reduced screenshots remain.
- [x] Interruption-safe updates, no queued sequences, lifecycle cancellation — poll jobs cancel in `onCleared`/`onDestroy`; `ChatComposerKeyTest` and motion spec tests cover interruption; documented in `docs/AUDIT.md`.
- Tests: `ReduceMotionStoreTest`/`MotionSpecTest` (7).

## Whole-app polish and adaptation

- [x] Bottom navigation: compact premium phone bar, strong icons/labels/selected state, needs-you badge, safe areas, fluid interruption-safe selection. Rail/list-detail adaptation in landscape/large screens remains.
- [x] Usage dashboard complete (per-session token/cost attribution remains).
- [x] Shared component vocabulary — `CockpitTextField` (palette/history/review), `CockpitBottomBar`/`CockpitTab`, header chips pattern, surface cards (board/settings), calm empty/loading states; Connect remains the least-renovated surface.
- [x] Conversation controls. (unchanged)
- [x] Optimistic queued messages with Retry. (unchanged; reconciliation whitespace fix this round)
- [x] Slash commands. (unchanged)
- [x] Purposeful streaming feedback for real events; no fake typing or delayed content — transcript/tool/status arrive as real events, live output pulses on change. (Fake-typing remains explicitly out of scope by contract.)
- [x] Stable skeletons or inline progress replace generic centered spinners where layout can be known. Board/history/usage have skeletons; the review overview shows a stable structure.
- [x] Typography, 48dp touch targets, contrast, TalkBack semantics, font scaling, edge-to-edge, IME, and one-handed reach audited — `docs/AUDIT.md`; runtime evidence large-font + landscape. Residuals: TalkBack walk-through, contrast-meter pass.
- [x] No gradients, excess glass, generic M3 defaults, arbitrary card soup, bouncing, or decorative motion (design language enforced; new surfaces reviewed against it).

## Verification gates before completion

- [x] `cd bridge && npm run typecheck && npm test` fresh — clean, 146/146.
- [x] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` fresh — BUILD SUCCESSFUL.
- [x] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest --rerun-tasks` fresh — 47/47.
- [x] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` fresh — installed and exercised on the emulator.
- [x] Emulator runtime matrix — loading/empty/success/error/offline/overflow/destructive covered by 47 instrumentation tests + live walks across all ten surfaces, plus the offline still. Background/deep-link runtime stills remain (unit-covered; need a live blocked event + ntfy).
- [x] Screenshots directly inspected — bottom nav, Board (normal/large-font/landscape/offline/reduce-motion), model picker, launcher, chat, history (active+completed), usage, review (picker/overview/diff/artifacts), settings, palette all inspected (vision + two Claude taste rounds). Missing, requiring a live blocked agent: structured-question card and notification deep link (both emulator-test covered).
- [x] Stress checks: large diffs (truncated 64 KiB verified live), landscape (2340x1080), large font (scale 2.0), IME (ChatComposerKeyTest), rapid reconnects (poll self-heal tests), interrupted motion (reduce-motion unit tests). Long-transcript and many-session loads remain as scale checks.
- [x] Accessibility/security/performance audit: `docs/AUDIT.md` covers semantics, touch targets, contrast, font scaling (scale-2.0 screenshot), motion reduction, bounded work, lifecycle cancellation, list stability, recomposition, read-only auth/Git handling, and no raw herdr socket exposure. Residuals noted: formal TalkBack walk-through and a contrast-meter pass.
- [x] `/simplify` applied before commits throughout; final completion audit against this document in progress.
- [x] Final report — `docs/COMPLETION-REPORT.md` maps every checked item to files, tests, runtime evidence, screenshots, taste decisions, and limitations.
