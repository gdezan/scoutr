# 7. Navigation registry

**Strength: Worth exploring.** Standalone, small, and independent of every other plan.

## Files

`android/…/MainActivity.kt:103-124, :240, :250-422, :465-490`.

## Problem

Adding a tab requires four edits in one file, to four lists that must agree:

1. `private object Routes` (`:103-116`) — a route string constant.
2. `private enum class Destination` (`:119-124`) — `(route, label, ImageVector)`.
3. The bottom-bar visibility check (`:240`) — a **hardcoded set literal**:

```kotlin
if (currentRoute in setOf(Routes.BOARD, Routes.SESSIONS, Routes.USAGE, Routes.REVIEW))
```

4. A `composable(Routes.X) { … }` block in the `NavHost` (`:250-422`), repeating the
   scaffold boilerplate — compare `:393-406` and `:407-418`, which are the same 12 lines
   with two words changed:

```kotlin
Scaffold(
    contentWindowInsets = WindowInsets(0.dp),
    topBar = { AppTopBar("X", onSearch = openPalette, onSettings = openSettings) },
) { … viewModel(factory = …) … }
```

Item 3 is the trap: it duplicates information that `Destination.entries` already holds. Miss
it and the new tab renders without a bottom bar — a silent, visual-only failure that no test
catches, and one the recent history shows this codebase has already paid for once
(`25df24f fix(sessions): … tab scaffolds stop double nav inset`).

`MainActivity.kt` is also where the activity-scoped ViewModels are hoisted (`:193`, `:223`,
`:229`) with comments explaining why (single poller, cross-tab steering). That part is
deliberate and correct — the plan must not disturb it.

## Solution

Make `Destination` the single source of truth and derive everything from it.

```kotlin
// ui/nav/Destination.kt
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Board("board", "Board", Icons.Outlined.Dashboard),
    Sessions("sessions", "Sessions", Icons.Outlined.History),
    Usage("usage", "Usage", Icons.Outlined.DataUsage),
    Review("review", "Review", Icons.Outlined.Difference);

    companion object {
        val routes: Set<String> = entries.map { it.route }.toSet()
        fun forRoute(route: String?): Destination? = entries.find { it.route == route }
    }
}
```

The visibility check at `:240` becomes `currentRoute in Destination.routes` — items 2 and 3
can no longer disagree. `Routes` keeps only the non-tab routes (`CHAT` with its
URL-encoding builder, `SETTINGS`, `CONNECT`, `NEW_SESSION`), which is what it is actually
for.

Then remove the repeated scaffold with one composable:

```kotlin
// ui/nav/TabScaffold.kt
@Composable
fun TabScaffold(
    title: String,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
)
```

Each tab's `composable {}` block shrinks to `TabScaffold("Usage", …) { UsageScreen(…) }`.
That also gives the `contentWindowInsets = WindowInsets(0.dp)` inset contract — the subject
of a recent fix — exactly one home instead of four.

Adding a tab then means: one `Destination` entry, one `composable` block, and a ViewModel.
Two lists instead of four, and the two cannot drift.

**Deliberately not done:** a fully registry-driven `NavHost` where each `Destination`
carries its own content lambda. It sounds tidier but forces every destination's dependencies
through a uniform signature, and the hoisted activity-scoped ViewModels do not fit one. The
`NavHost` stays explicit; only the *lists* are derived.

## Benefits

**Locality.** Tab identity lives in one enum. The bottom bar, the visibility rule, and the
label all read from it.

**Leverage.** Small but real — this is per-feature friction, paid every time a screen is
added, and the failure mode is silent.

**Tests.** `Destination.routes` and `forRoute` are pure and unit-testable with no
Robolectric. A test that every `Destination` has a `composable` registered in the `NavHost`
is also possible via `navController.graph`, which would close the remaining gap entirely.

## Steps

1. Move `Destination` into `ui/nav/Destination.kt` with `routes` and `forRoute`.
2. Replace the `setOf(...)` literal at `:240` with `Destination.routes`.
3. Trim `Routes` to non-tab routes only.
4. Extract `TabScaffold` and apply it to the four tab blocks.
5. Add unit tests for `routes` / `forRoute`, and a graph test asserting every `Destination`
   resolves to a registered destination.

## Risks

- **The inset contract.** `contentWindowInsets = WindowInsets(0.dp)` and the padding each tab
  passes down were the subject of commit `25df24f`. Centralising them is the point, but
  verify all four tabs on the emulator afterwards — this is a visual regression that unit
  tests will not catch.
- **Icon imports.** Moving `Destination` out of `MainActivity` moves the Material icon
  imports with it; keep them in the new file rather than leaving both.
