# Cockpit goal handoff — machine turned off mid-work

## Where things stand (goal: ten-fix round, pi-goal --file /tmp/cockpit-goal-ten-fixes.txt)

### Committed through: 7ddb48b (deploy gate), e93b15a (fix 9 docs), fed4737 (fix 9 copy), c348514 (fix 15 docs), f4f0cc7 (fix 15 board swipe), 34b1cb2 (fix 13), add5760 (fix 14), 9b81d46 (fix 8), 9215d40 (fix 7), ca601aa+597e8ff (fix 6), 802d9da (fix 5 docs), 8c2c5ea (fix 5), d95d4c1 (fix 4), fa9bd9d (fix 3), 28c71ea+ef99656 (fix 2 + catalog DTO), 61d9403 (fix 1), c2e6151 (startup crash).
- Fixes 1–9, 13, 14, 15 DONE (tested + taste-reviewed + committed).
- The production-bridge stale-dist incident is FIXED and gated: `bridge/scripts/check-deployed.mjs` + `npm run deploy` (7ddb48b). Real bridge rebuilt + restarted + verified on the user's phone.

### The two commits made at shutdown (25df24f, 3e11520 — pushed to git)

1. **Fix 16 (user-reported): Sessions Archived chip smushed + empty band above bottom nav.**
   - `HistoryScreen.kt` ViewTabs: chip row is now horizontally scrollable (`.horizontalScroll`) + `maxLines=1` so "Archived" never wraps to two lines on narrow screens.
   - `MainActivity.kt`: `contentWindowInsets = WindowInsets(0.dp)` on the four inner tab Scaffolds — the outer Scaffold's bottom bar already consumes the nav-bar inset; the inner default re-added it (~48dp empty band clipping the list above the bottom bar).
   - Regression: `HistoryScreenTest.filterChipsStaySingleLineAtNarrowWidth` (320dp box; asserts equal chip heights). NOTE: test calls `viewModel()` BEFORE `setContent` (MockWebServer reverse-DNS gotcha).
2. **Fix 10: inline live-output streaming in chat.**
   - `ChatViewModel.kt`: polling owned by screen + work state; `LIVE_OUTPUT_POLL_MS` 1500 → 900.
   - `ChatScreen.kt`: `LifecycleStartEffect(agentStatus, liveOutputExpanded)` — polls while working OR drawer expanded (drawer must fetch for blocked/idle/done too); inline card in ChatList at the bottom while working (hidden when the expanded drawer owns the surface); strip hidden only when the inline card actually renders (loading + empty-transcript branches keep the strip); `lastIndex` parenthesized (was an operator-precedence bug); `questions.size` replaces the no-op groupedQuestionCount.
   - `LiveOutputPanel.kt`: new `InlineLiveOutput` composable (tail-5 mono card, LIVE dot/label in primary; `STALE · RECONNECTING` in error when polling fails — "state is the color").
   - Tests updated/added: `ChatControlsTest.liveOutputStreamsInlineWhileWorkingAndKeepsPolling`, `drawerStillFetchesWhenAgentIsNotWorking`, `ChatListTest.inlineLiveOutputRendersTailWithTrimmedMarker`, `inlineLiveOutputAbsentWhenNotVisible`, `inlineLiveOutputShowsStaleStateOnError`, `ChatLiveOutputViewModelTest.pollingContinuesWhileWorkingAfterPanelCollapses`.

### Verified BEFORE shutdown
- `testDebugUnitTest --rerun-tasks`: BUILD SUCCESSFUL (green).
- `compileDebugKotlin` + `compileDebugAndroidTestKotlin`: green.
- AGENTS.md **Code review gate: PASSED** via Claude helper (herdr agent "review", pane w4Q:p4Z) — VERDICT was CHANGES REQUIRED; all findings applied (blocking: strip unreachable on non-list branches, dead drawer for non-working agents; should-fix: lastIndex precedence, stale-LIVE error state; minor: dup import, double liveLines computation, no-op count).

### NOT yet done (remaining work)
- **`pixel2api36DebugAndroidTest` full emulator suite was INTERRUPTED (machine shutdown) — the run was in progress and did not finish green.** Must re-run: `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest` (~9 min). Expected ~73 tests incl. the 4 new/updated ones; earlier partial run had all failures fixed.
- **Taste review (goal gate: non-negotiable before commit)** for fix 10 + fix 16 was NOT run — the two commits above were forced through the shutdown. Run the /taste-review claude workflow (drives the emulator itself; skill at ~/.agents/skills/taste-review, symlinked to ~/.claude/skills) over the emulator screenshots + changed UI, apply findings, and follow up with a docs commit + any fix commits. Fix 16 also wants a before/after screenshot pair (user's screenshot is at /home/gdezan/.config/cockpit/uploads/1786418730711_dr5ueq_image.jpg — shows the smushed "Archiv/ed" chip and the ~115px empty band; the vision description is in the session).
- **Live streaming walk for fix 10**: start a real run via the app, capture 2-3 screenshots showing the inline card's output growing at the bottom of the transcript, save to /tmp/cockpit-10.png.
- **Fix 16 screenshots**: /tmp/cockpit-16.png (after).
- **docs/production-goal-checklist.md**: add fix 10 and fix 16 entries (fix 16 is user-added fix #16 in /tmp/cockpit-goal-ten-fixes.txt).
- **Remaining goal todos**: #12 (question-card dismissal — user-added fix 11: selecting an ask_user_question option must dismiss the card and land in the transcript; emulator-verify) and #11 (final audit: all four gates green, checklist complete, bridge npm run typecheck && npm test).

### Env notes for resuming
- Two adb devices: phone `adb-RQCX308702X-uE5DUo._adb-tls-connect._tcp` (WiFi debugging ON, points at the real bridge via tailscale serve → 127.0.0.1:8737, token in ~/.config/cockpit/config.json) and emulator-5554. ALWAYS use `-s emulator-5554` (or the phone id).
- Scratch bridge recipe: docs/dev-workflow.md (config /tmp/cockpit-scratch, port 8791, token testtoken1234567890). Real bridge: `npm run deploy` from bridge/ (build dist + restart cockpit-bridge.service + check:deployed gate).
- The review pane w4Q:p4Z may still be open (claude agent "review" — close it: `herdr agent close review` or `herdr pane close w4Q:p4Z`).
- AGENTS.md now has a mandatory **Code review** gate before every commit (fresh pi pane, openai-codex/gpt-5.6-sol — that provider was hanging; use the Claude helper or opencode-go/gpt-5.6-luna as fallback). AGENTS.md itself is a user edit, still uncommitted.
- openai-codex and opencode-go providers were flaky/hanging at shutdown; opencode-go/gpt-5.6-luna recovered intermittently.
