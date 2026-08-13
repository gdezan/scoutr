# Plan 006: Give tablet layouts readable content bounds

> **Executor instructions**: Apply one shared responsive rule to the named tab surfaces, then verify phone and tablet screenshots after every step. Do not invent a full tablet navigation redesign. Update `design-plans/README.md` when done.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/scoutr/app/ui/screens/BoardScreen.kt android/app/src/main/java/dev/scoutr/app/ui/screens/HistoryScreen.kt android/app/src/main/java/dev/scoutr/app/ui/screens/ReviewScreen.kt android/app/src/main/java/dev/scoutr/app/ui/components/ android/app/src/androidTest/java/dev/scoutr/app/ui/BoardScreenTest.kt android/app/src/androidTest/java/dev/scoutr/app/ui/HistoryScreenTest.kt android/app/src/androidTest/java/dev/scoutr/app/ui/ReviewScreenTest.kt`

## Status

- **Priority**: P3
- **Effort**: M
- **Risk**: MED
- **Depends on**: Plans 003 and 005 (verify their new controls within the final width behavior)
- **Category**: mobile, hierarchy, polish
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

At 1600×2560 / 320dpi, Board cards, Sessions search/results, and Review picker rows span almost the full tablet width. Preview text becomes excessively long and related controls sit far apart, slowing the glance-and-act loop. The phone layouts are already effective and must not regress.

## Current state

- `BoardScreen.kt:122-127`: `LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp))`
- `HistoryScreen.kt:115-155` and list at `284-305`: full-width column/search/tabs/list
- `ReviewScreen.kt:101-210`: full-width picker column and directory rows

Tablet screenshots showed roughly 1500px-wide cards/fields with large empty regions below sparse data. Review's path field and “Review this folder” action were separated across the width. There is no shared maximum-content-width primitive in these screens.

Reuse near-black background and current Material 3 surfaces. Add a small reusable screen-content container only if at least two screens use identical centering/inset behavior; keep it in `ui/components/` and name it by behavior (for example `ReadableContentColumn`), not device type.

## Intended result

- Phone/compact width: layouts are pixel-equivalent to current 16dp horizontal insets.
- Tablet/expanded width: primary list/form content is centered with a maximum width of **960dp** and at least 24dp side gutters. Board cards, Sessions search/tabs/list, and Review picker/path/list share this bound.
- Tab app bar and bottom navigation remain full-width.
- Raw diff and the future full-screen Interactive Terminal remain full-width because horizontal space directly benefits code/terminal content. Plan 005's diff file navigator follows the diff width, not the 960dp form bound.
- Sparse data stays top-aligned; do not fill empty space with decorative content.
- Large fonts and landscape retain reachable controls with no clipping.

## Commands

This plan is self-contained. Bound every command and target only the emulator:

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
timeout 30 adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

Use a **2560×1600 at 240dpi** landscape viewport (about 1707dp wide), wider than the 960dp bound plus gutters. Run the three deterministic instrumentation classes while that geometry is active, then capture the live Board and restore geometry in the same bounded shell. Every inner device call is serial-targeted and bounded; the cleanup trap is also bounded:

```bash
timeout 720 bash <<'TABLET'
cleanup() {
  timeout 30 adb -s emulator-5554 shell wm size reset
  timeout 30 adb -s emulator-5554 shell wm density reset
}
trap cleanup EXIT
timeout 30 adb -s emulator-5554 shell wm size 2560x1600
timeout 30 adb -s emulator-5554 shell wm density 240
for class in BoardScreenTest HistoryScreenTest ReviewScreenTest; do
  ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
    -Pandroid.testInstrumentationRunnerArguments.class="dev.scoutr.app.ui.$class" || exit 1
done
timeout 30 adb -s emulator-5554 shell am force-stop dev.scoutr.app
timeout 30 adb -s emulator-5554 shell am start -n dev.scoutr.app/.MainActivity
sleep 2
timeout 30 adb -s emulator-5554 exec-out screencap -p > /tmp/board-wide-tablet.png
TABLET
# Pull every exact artifact path printed by the tests; for example:
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.scoutr.app/files/board-wide.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.scoutr.app/files/sessions-wide.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.scoutr.app/files/review-wide.png /tmp/
timeout 30 adb -s emulator-5554 shell wm size
timeout 30 adb -s emulator-5554 shell wm density
# Must report physical defaults, 1080x2340 and 440 on the scoutr AVD.
```

Each in-scope Compose test class owns deterministic populated fixture data and saves `captureToImage()` evidence under `targetContext.getExternalFilesDir(null)`. Assert bounds against the real widened root constraints; do not simulate a >1008dp child inside the normal phone-width device.
## Scope

**In scope**:
- `android/app/src/main/java/dev/scoutr/app/ui/screens/BoardScreen.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/HistoryScreen.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/ReviewScreen.kt`
- One responsive container under `android/app/src/main/java/dev/scoutr/app/ui/components/` if shared
- Relevant `BoardScreenTest.kt`, `HistoryScreenTest.kt`, `ReviewScreenTest.kt`

**Out of scope**:
- Bottom navigation/app-bar redesign
- Chat, the future Interactive Terminal, raw diff width, Settings, Usage, Connect
- Master-detail navigation (future direction, not this plan)
- Global theme/token changes

## Steps

### Step 1: Establish one centered-width rule

Implement a container that fills available width, centers its child, applies compact 16dp gutters, expanded 24dp gutters, and `widthIn(max = 960.dp)`. Use Compose constraints/window width, not physical device-name checks. Avoid adding a dependency solely for width classes if existing Compose APIs suffice.

**Verify visually**: render a colored debug outline temporarily or use layout bounds to prove phone content remains 16dp inset and tablet content is centered at no more than 960dp; remove debug treatment before proceeding.

### Step 2: Apply it to Board and Sessions

Move Board's list and Sessions' search, tabs, banners, and list into the bound while keeping app chrome full-width. Ensure Plan 003 overflow menus anchor correctly. Do not independently constrain each card; constrain the common content column.

**Verify visually**: phone screenshots match existing density. Tablet Board/Sessions screenshots show readable centered cards and preview lines, with consistent left edges across search/tabs/cards. At 1.3× font scale, no action or status clips.

### Step 3: Apply it only to Review's picker and overview forms

Constrain repository picker/path/list and overview list content. Keep `DiffMode` full-width. Ensure “Review this folder” remains adjacent enough to the path to read as one control group; at narrow width, preserve existing phone behavior.

**Verify visually**: tablet picker screenshot shows centered path/action/list; phone picker remains usable. Open a diff and confirm it still reaches both viewport edges and pans horizontally.

### Step 4: Pin compact/expanded behavior in tests

Add constrained-width Compose tests for phone and tablet containers. Assert primary content bounds do not exceed 960dp on expanded width and do not introduce excess gutters on compact width. Test large font scale where feasible.

**Verify**: relevant classes and all Android gates pass. Restore emulator geometry and confirm `adb -s emulator-5554 shell wm size`/`wm density` report physical defaults.

## Done criteria

- [ ] Board, Sessions, and Review picker/overview use a shared centered 960dp maximum on expanded widths.
- [ ] Compact phone layout retains 16dp gutters and current information density.
- [ ] Raw diff remains full-width.
- [ ] Plan 003 menu and Plan 005 navigator remain correctly positioned.
- [ ] Tablet and phone screenshots inspected at normal and large font.
- [ ] Android gates pass; only in-scope files changed; emulator geometry restored.

## STOP conditions

- Existing project guidance specifies a different width breakpoint or maximum.
- A shared component would require global scaffold/navigation changes.
- Compact layout changes materially while applying the bound.
- Tests or screenshots cannot verify both geometries.

## Maintenance notes

The 960dp bound is for scan-oriented lists and forms, not terminal/code surfaces. Revisit master-detail only as a separate product-direction plan with navigation/state design.