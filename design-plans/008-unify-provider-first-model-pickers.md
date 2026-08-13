# Plan 008: Make model selection provider-first and gesture-stable

> **Executor instructions**: Follow this plan step by step. After each step, run the stated verification and inspect the screenshots/recording before continuing. If a STOP condition occurs, stop and report rather than improvising. When done, update this plan's row in `design-plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/scoutr/app/ui/screens/ConversationConfigSheet.kt android/app/src/main/java/dev/scoutr/app/ui/screens/SessionPickers.kt android/app/src/main/java/dev/scoutr/app/state/ModelPickerSearch.kt android/app/src/main/java/dev/scoutr/app/state/NewSessionViewModel.kt android/app/src/test/java/dev/scoutr/app/state/ModelPickerSearchTest.kt android/app/src/test/java/dev/scoutr/app/state/NewSessionViewModelTest.kt android/app/src/androidTest/java/dev/scoutr/app/ui/NewSessionSheetTest.kt android/app/src/androidTest/java/dev/scoutr/app/ui/ChatControlsTest.kt`
> If either picker, its search state, or its tests changed materially, compare the live screens with this plan before editing.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: `design-plans/001-keep-folder-confirmation-visible.md`
- **Category**: hierarchy, friction, mobile, a11y
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

Scoutr currently asks users to learn two different interfaces for the same decision. New Session uses a full-screen, provider-grouped catalog crowded by reasoning/context/thinking filter chips, while Conversation Setup uses a flat list inside a draggable bottom sheet. In the latter, scrolling back to the top can transfer the gesture to the sheet and make the entire surface collapse or snap. Model selection should have one hierarchy—provider—one predictable search behavior, and one gesture owner.

## Current state

**New Session picker**: Board → `+` → Model. `SessionPickers.kt:64-127` renders a full-screen dialog, search, two rows of capability filters, and provider groups. `ModelPickerRow` at lines 285-355 shows name, key, compact capabilities, default/recent state, selection, and favorite.

**Conversation Setup picker**: Chat → tap the model/thinking configuration chip. `ConversationConfigSheet.kt:72-198` renders a default `ModalBottomSheet` with sheet dragging enabled and partial expansion allowed, then caps its inner model `LazyColumn` at 380dp. After scrolling models down and back to the top, the same vertical drag can move the sheet toward its partial/dismiss anchors. This matches the reported snap/flicker sequence.

The creation picker already proves the intended section structure:

```kotlin
val providerGroups = ui.modelMatches.groupBy { it.provider }
providerGroups.forEach { (provider, matches) ->
    item(key = "provider:$provider") { ProviderHeader(provider, matches.size) }
    items(matches, key = { it.key }) { ... }
}
```

`NewSessionSheet.kt:83-103` is the safe sheet precedent: `skipPartiallyExpanded = true`, no drag handle, and `sheetGesturesEnabled = false` because a close button provides dismissal.

## Intended result

```text
┌ Choose a model                         × ┐
│ [ Search provider, model, or ID       ] │
├─────────────────────────────────────────┤
│ OPENAI-CODEX                        8   │
│ GPT-5.4                    default  ☆   │
│ openai-codex/gpt-5.4                   │
│ 200K context • reasoning               │
│ GPT-4.1 Mini                       ★   │
│ ...                                     │
│                                         │
│ ANTHROPIC                            3  │
│ Claude Sonnet 4.6                       │
└─────────────────────────────────────────┘
```

- Provider headers are the **only catalog grouping** in both flows.
- Remove reasoning, context-window, and thinking-level filter chips entirely. Do not replace them with another filter dialog or provider chips.
- Search matches provider, model name, and exact model ID. A query resets results to the top (implemented in Plan 009); provider headings remain visible around matches.
- Context window and reasoning may remain one compact, muted metadata line per row. Thinking-level values are omitted from model rows: the separate Conversation Setup “Thinking level” control remains unchanged because it configures the next turn rather than filtering the catalog.
- Both pickers share the same provider-header and model-row presentation and stable keys. Flow-specific actions may differ: New Session keeps favorite/default/recent affordances; Conversation Setup needs selection/current-model state but must not show a dead favorite action.
- Conversation Setup opens fully expanded at no more than 94% screen height, has no drag handle, and does not respond to vertical sheet dragging. Its explicit Close button and outside/back dismissal remain available.
- The model list owns vertical scrolling and receives remaining height via `weight(1f)`; fixed header, thinking controls, model label, and search stay reachable without combining a capped list with a moving sheet.
- At list top, repeated downward swipes move list content only as far as its boundary; the sheet does not collapse, snap, or flicker.

Use existing Material 3 surfaces, typography, `onSurface`/`onSurfaceVariant`, mono for provider/model identifiers, and primary blue only for selected/current/default/favorite state already defined by the app. Preserve 48dp targets and the no-bounce/no-spin contract.

## Commands

All device work targets the emulator explicitly; never use a physical phone. Bound every Gradle/device command:

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
timeout 30 adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.scoutr.app.ui.NewSessionSheetTest,dev.scoutr.app.ui.ChatControlsTest
```

Add deterministic screenshot fixtures to the two existing instrumentation classes. Capture the New Session picker and Conversation Setup with the same multi-provider catalog, including a provider with enough models to overflow. Print each absolute PNG path, then pull using that exact path. Record the down-list → back-to-top → extra downward-swipe sequence:

```bash
# Run this bounded recorder in a second terminal while driving the gestures.
timeout 30 adb -s emulator-5554 shell screenrecord --time-limit 20 /sdcard/model-picker-scroll.mp4
# After the recorder exits:
timeout 30 adb -s emulator-5554 pull /sdcard/model-picker-scroll.mp4 /tmp/model-picker-scroll.mp4
```

## Scope

**In scope**:
- `android/app/src/main/java/dev/scoutr/app/ui/screens/ConversationConfigSheet.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/SessionPickers.kt`
- `android/app/src/main/java/dev/scoutr/app/state/ModelPickerSearch.kt`
- `android/app/src/main/java/dev/scoutr/app/state/NewSessionViewModel.kt`
- `android/app/src/test/java/dev/scoutr/app/state/ModelPickerSearchTest.kt`
- `android/app/src/test/java/dev/scoutr/app/state/NewSessionViewModelTest.kt`
- `android/app/src/androidTest/java/dev/scoutr/app/ui/NewSessionSheetTest.kt`
- `android/app/src/androidTest/java/dev/scoutr/app/ui/ChatControlsTest.kt`

**Out of scope**:
- Provider/model catalog API and bridge behavior
- Favorites, recents, default-model persistence, or ranking semantics
- The separate next-turn Thinking level control
- New Session launcher sheet structure outside the model dialog
- Adding advanced filters, model detail screens, or a new design system

## Steps

### Step 1: Remove capability-filter state and controls

Delete `ModelFilters` and its reasoning/context/thinking chip rows from `SessionPickers.kt`. Simplify `ModelPickerFilters` and `searchModelCatalog` so query is the only user filter while retaining existing typo-tolerant search and selected/default/favorite/recent ranking. Remove obsolete ViewModel mutation methods and tests for capability-filter composition rather than retaining unused compatibility paths.

Update empty-state behavior: an empty query with an empty catalog keeps the existing load/error/empty semantics; a non-empty query says “No models match” and clearing search returns to the full provider catalog.

**Verify**: pure search and ViewModel tests pass. New Session screenshot contains search immediately followed by the divider/provider catalog, with no Reasoning, context threshold, or thinking filter chips.

### Step 2: Share provider-first presentation

Make provider headers and the core model-row presentation reusable by both picker entry points. Both must group `ModelPickerMatch` by provider, preserve ranked encounter order, use stable `provider:<name>` and model-key identities, and show the same name/key/compact context+reasoning hierarchy. Keep only the flow-specific trailing actions and badges.

Do not duplicate a second near-identical provider header or metadata formatter in `ConversationConfigSheet.kt`.

**Verify visually**: screenshots of both pickers with the same fixture show the same provider order, provider counts, row typography, metadata wording, and selection treatment. Every match appears under exactly one provider.

### Step 3: Give Conversation Setup one vertical gesture owner

Create an explicit sheet state with `skipPartiallyExpanded = true`; remove the drag handle and disable sheet gestures, matching `NewSessionSheet`. Bound the surface to the existing 94%-height pattern and allocate remaining height to the model list with `weight(1f)` rather than `heightIn(max = 380.dp)`. Preserve Close, back/outside dismissal, loading, error, no-catalog, no-match, busy, and thinking-capability states.

Do not make the fixed outer `Column` vertically scrollable around the model `LazyColumn`; there must be one same-axis scroll owner.

**Verify visually**: on the phone, the title, current thinking controls when supported, current model, and search are visible; the catalog fills remaining height. Scroll far down, return to top, and continue dragging downward. The recording shows no sheet movement, anchor snap, or flicker. Close and system Back still dismiss.

### Step 4: Pin interaction and accessibility coverage

Extend tests to assert provider headers/counts in Conversation Setup, absence of capability filter chips in New Session, exact-model selection in both flows, stable current-model semantics, catalog-less backend behavior, and full-height scrolling to the last model. Add a semantics assertion for the selected/current row and accessible close/search controls.

**Verify**: focused classes and full Android gates pass. Inspect phone screenshots at default font and 1.5× font; header controls and at least one model row remain reachable, metadata ellipsizes, and provider identity is not conveyed by color alone.

## Done criteria

- [ ] Both model pickers use provider as their only section hierarchy.
- [ ] Reasoning, context, and thinking capability filter controls and obsolete state are removed.
- [ ] Context/reasoning remain compact row metadata; thinking-level row metadata is removed.
- [ ] Conversation Setup has one vertical scroll owner and cannot partially collapse from list gestures.
- [ ] Both flows preserve their existing selection/action behavior and catalog states.
- [ ] Phone/default-font and 1.5×-font screenshots match the intended hierarchy.
- [ ] The top-boundary gesture recording contains no snap, collapse, or flicker.
- [ ] Full Android gates pass; only in-scope files changed.

## STOP conditions

- Capability filters are required by a documented product contract not present at `0e67682`.
- Provider grouping would alter the model key sent to the bridge or the ranking semantics.
- Disabling sheet gestures removes all dismissal paths; preserve Close and back/outside dismissal before proceeding.
- Fixed controls plus a weighted list cannot fit at 1.5× font without clipping; report measured evidence rather than restoring nested vertical scrolling.
- Either picker no longer matches the Current state after drift check.

## Maintenance notes

There is one model catalog concept. Future row metadata or ranking changes should appear consistently in both entry points. Do not reintroduce capability taxonomies as always-visible grouping/filter chrome; provider remains the only hierarchy unless product requirements explicitly change.