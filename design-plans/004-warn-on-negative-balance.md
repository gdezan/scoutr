# Plan 004: Make negative provider balances unmistakable

> **Executor instructions**: Follow and visually verify each step. Stop on any mismatch or unclear provider semantics. Update `design-plans/README.md` when complete.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/cockpit/app/ui/screens/UsageScreen.kt android/app/src/androidTest/java/dev/cockpit/app/ui/UsageScreenTest.kt`

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: feedback, states, a11y
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

A negative balance can prevent further agent work, but the current Usage card presents `-$0.01` with the same neutral styling as a healthy balance. A remote supervisor scanning operational health can easily miss it until a request fails.

## Current state

Usage → provider card. The audited DeepSeek card showed “Available balance / USD / -$0.01” in neutral off-white. `UsageScreen.kt:279-310` always renders identical label, semantics, and title color:

```kotlin
Text("Available balance", style = MaterialTheme.typography.bodyMedium)
...
Text(
    text = formatAmount(window.amount, window.currency),
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold,
)
```

Reuse `MaterialTheme.colorScheme.error` and existing typography. Error red already denotes blocked/error state; do not introduce orange or another accent. `UsageWindow.amount` must be checked using its actual numeric type, not by parsing formatted text.

## Intended result

- Zero or positive balance: unchanged neutral presentation.
- Negative balance: label changes to “Balance below zero”; amount uses `colorScheme.error`; a concise line below says “Add credit before starting more work.”
- Merged accessibility description announces the state first: “Balance below zero, USD, negative 1 cent” (using existing formatting where practical).
- Unknown/unavailable amount stays in its existing unavailable state and is never treated as negative.
- No provider-specific external link is added in this plan.

## Commands

This plan is self-contained; target only the emulator and bound all commands:

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.app.ui.UsageScreenTest
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
```

Use `UsageScreenTest`'s fake usage response to render three provider cards together: amount below zero, zero, and positive. Add a test-only `captureToImage()` helper, save `usage-balances.png` under `targetContext.getExternalFilesDir(null)`, print the path, then pull and inspect it:

```bash
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.cockpit.app/files/usage-balances.png /tmp/
```

Add a separate unavailable-amount fixture if the DTO supports it. Use the exact printed path if the external-files path differs.
## Scope

**In scope**:
- `android/app/src/main/java/dev/cockpit/app/ui/screens/UsageScreen.kt`
- `android/app/src/androidTest/java/dev/cockpit/app/ui/UsageScreenTest.kt`

**Out of scope**:
- Usage API/data contracts and provider authentication
- Billing links, payment flows, or provider-specific instructions
- Limit-bar color semantics

## Steps

### Step 1: Derive a semantic negative state

Inside `BalanceRow`, derive `isNegative` from `window.amount`. Branch only presentation: heading, amount color, helper line, and merged semantics. Preserve alignment, formatting, and test tag.

**Verify visually**: fixture one negative, zero, and positive balance. Screenshot all three. Negative is immediately distinct in error red with recovery hint; zero/positive remain calm and neutral.

### Step 2: Add accessible state coverage

Extend `UsageScreenTest.kt` with negative/zero/positive fixtures. Assert visible warning copy and semantics for negative; assert warning is absent for zero and positive. Confirm long currency labels and large amounts still ellipsize without hiding the warning.

**Verify**: use TalkBack semantics tree or Compose semantics assertions; run single class and all Android gates.

## Done criteria

- [ ] Negative balance has error-colored amount, explicit status label, and recovery hint.
- [ ] Zero, positive, and unavailable states are unchanged.
- [ ] Semantics convey negative state without color dependence.
- [ ] Phone screenshot shows no wrapping/collision at 1.3× font scale.
- [ ] Android gates pass; only in-scope files changed.

## STOP conditions

- `amount` cannot reliably distinguish numeric negative/zero/unavailable values.
- Provider semantics show that a negative value is normal/non-actionable.
- The warning requires a billing URL or backend contract change.

## Maintenance notes

If provider-specific recovery links are added later, keep the semantic negative-state logic provider-neutral and make links secondary actions, not replacements for status copy.