# 6. A `Poller` and a `Loadable` for the ViewModels

**Strength: Worth exploring.** Follows plan 4; needs its `BridgeException` to be worthwhile.

## Files

`android/…/state/` — `ChatViewModel.kt` (546), `NewSessionViewModel.kt` (395),
`SessionHistoryViewModel.kt` (252), `CommandPaletteViewModel.kt` (189),
`BoardViewModel.kt` (180), `ReviewViewModel.kt` (173), `ConnectViewModel.kt` (61),
`UsageViewModel.kt` (58).

## Problem

Three idioms are copy-pasted across all eight ViewModels, each with small unexplained
divergences.

**The load block**, roughly 20 occurrences (`ReviewViewModel` has 5 at `:71-89, :102-120,
:122-140, :142-156`; `ChatViewModel` has 6):

```kotlin
_ui.update { it.copy(loading = true, error = null) }
try {
    val result = bridge.something()
    _ui.update { it.copy(loading = false, data = result) }
} catch (e: Exception) {
    _ui.update { it.copy(loading = false, error = e.message ?: "failed") }
}
```

**The poll loop**, 4 occurrences (`BoardViewModel:87`, `SessionHistoryViewModel:80`,
`ChatViewModel:195` and `:216`), each with a different ordering of `refresh` and `delay` and
a different interval:

```kotlin
job?.cancel()
job = viewModelScope.launch { while (isActive) { refresh(); delay(N) } }
```

**The state scaffold**: `data class XUiState(loading, error, …)` plus
`private val _ui = MutableStateFlow(…)` / `val ui = _ui.asStateFlow()`, eight times.

The divergences are the real cost. `BoardViewModel:137` and `SessionHistoryViewModel:108`
distinguish `IOException` (flip `connected = false`) from generic `Exception` (swallow, so
the list does not flap); the other six do not. Nothing records whether that is a considered
difference or an omission — which is precisely what duplicated code cannot tell you. A ninth
ViewModel will copy whichever neighbour its author opened.

Eight near-identical `companion object { fun factory(…) }` blocks with
`@Suppress("UNCHECKED_CAST")` are a fourth copy-paste (`UsageViewModel.kt:51-56`,
`BoardViewModel.kt:167-178`), and `ReviewViewModel.kt:164-171` diverges again to reach
`APPLICATION_KEY` for a `Context`.

## Solution

Two small modules. Deliberately small — the goal is to remove the ambiguity, not to build a
framework.

### `Loadable<T>`

```kotlin
// state/Loadable.kt
sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val reason: String, val kind: FailureKind) : Loadable<Nothing>
}

enum class FailureKind { Offline, Unauthorized, Rejected, Server }
```

```kotlin
// state/LoadInto.kt
suspend fun <T> MutableStateFlow<T>.load(
    setLoading: T.() -> T,
    onSuccess: T.(R) -> T,
    onFailure: T.(String, FailureKind) -> T,
    block: suspend () -> R,
)
```

`FailureKind` is derived once from plan 4's `BridgeException`, which makes the
IOException-vs-Exception divergence an explicit choice at each call site instead of an
accident: `Offline` flips `connected`, `Rejected` shows a message, and the caller decides.

### `Poller`

```kotlin
// state/Poller.kt
class Poller(private val scope: CoroutineScope) {
    /** Cancels any previous loop. Runs [tick] immediately, then every [interval]. */
    fun start(interval: Duration, tick: suspend () -> Unit)
    fun stop()
}
```

One definition of "refresh first, then wait", one cancellation rule, one place to add
backoff-on-failure later. The four current loops become four `poller.start(3.seconds) { … }`
calls, and the interval differences stay visible as arguments rather than as buried
constants.

### A factory helper

```kotlin
inline fun <reified VM : ViewModel> viewModelFactory(crossinline create: (CockpitApp) -> VM):
    ViewModelProvider.Factory
```

Collapses eight boilerplate blocks including `ReviewViewModel`'s `CreationExtras` variant,
and unifies the two DI entry styles that coexist today (`MainActivity` passing
`viewModel(factory = …)` vs. `BoardScreen.kt:532` / `ConnectScreen.kt:189` casting
`applicationContext as CockpitApp` themselves).

## Benefits

**Locality.** Retry policy, cancellation, and the offline rule live in one file each. Today
each is eight partial re-derivations.

**Depth — modestly.** `Poller` is a two-method interface over cancellation, immediate-first
semantics, and (soon) backoff. `Loadable` is a type, not a module, but it removes the
`loading`/`error` field pair from eight state classes and makes the impossible state
(`loading = true, error != null`) unrepresentable.

**Deletion test.** Deleting `Poller` would push the loop back into four ViewModels — moving
complexity, not concentrating it, which is a weak result and the honest reason this plan is
*Worth exploring* rather than *Strong*. `Loadable` scores better: deleting it re-scatters the
failure taxonomy across twenty catch blocks, and the taxonomy is genuine knowledge.

**Tests.** With plan 4's fake and virtual time, `Poller` gets its own tests (immediate first
tick, restart cancels the previous loop, cancellation on scope death). Today no test covers
poll behaviour at all — tests specifically arrange for the loops *not* to run
(`BoardViewModelTest.kt:39-44`).

## Steps

1. Land plan 4 (`CockpitApi`, `BridgeException`) first — `FailureKind` depends on it.
2. Add `Poller` with tests; migrate the four loops one at a time.
3. Add `Loadable` and `FailureKind`; migrate `UsageViewModel` and `ConnectViewModel` first
   (smallest, single-load each) to validate the shape.
4. Migrate `ReviewViewModel` (5 load blocks — the best return) and `ChatViewModel` (6).
5. Decide the offline rule explicitly for every migrated VM and note it where it differs.
6. Add `viewModelFactory` and collapse the eight factories.

## Risks

- **Over-abstracting.** Do not build a generic repository layer or a `BaseViewModel`. Eight
  ViewModels talking directly to `CockpitApi` is fine; the duplication that matters is the
  three idioms, not the direct calls.
- **`Loadable` fighting composite state.** `ChatUiState` holds several independently-loading
  things (transcript, live output, pending sends). Use `Loadable` per field, not per screen,
  or it will be worse than the flat booleans.
- **Migration churn against plan 5.** Both touch the same ViewModels. Sequence them; do not
  run both at once.
