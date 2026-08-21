# Wide-Window Two-Pane Layout Blueprint

## Current situation

The app is single-pane at every width. `ui/components/ReadableContentColumn.kt`
is the only wide-window accommodation: a 960dp cap with a 24dp gutter at
≥1008dp, used by `BoardScreen` (line 169), `HistoryScreen` (187) and
`ReviewScreen` (252, 399). `ChatScreen` has no width cap at all, so its
transcript stretches to the full window.

`MainActivity.kt` (`ScoutrAppNav`) is the composition root: one `Scaffold`
whose `contentWindowInsets` is `systemBars.only(Horizontal + Top)`, a
`bottomBar` gated on `currentRoute in Destination.routes &&
boardUi.apiCompatibility == Compatible`, and one `NavHost` padded by the
Scaffold's inner padding. Route strings live in a private `object Routes`.
The inset split is load-bearing: the outer Scaffold owns the system bars and
every inner `Scaffold`/`TopAppBar` passes `WindowInsets(0.dp)`, because a
second consumer stacks into a dead band (fix 25df24f, documented in
`ui/nav/TabScaffold.kt` and `ui/components/AppTopBar.kt`).

One activity-scoped `BoardViewModel` (key `"activity_board"`) polls
`/api/agents` every 3s. `BoardScreen` starts and stops that poll through
`LifecycleStartEffect`; `BoardViewModel.startPolling`/`stopPolling` are guarded
by `lifecycleActive`, so repeated calls are idempotent.

`BoardScreen.kt` (788 lines) holds the list body (`boardSection` for Needs you
/ Working / Done / Idle / Other, keyed by `live.paneId`), `AgentCardRow`,
`CompatibilityBanner` and `DisconnectedBanner` — all `private`. `AgentCardRow`
carries a 156dp swipe-to-reveal bar (Review / Copy path / Close) driven by
`AnchoredDraggableState` + the `BoardReveal` enum, plus a `PressTintSurface`
card containing `StatusRing`, `AgentMark` + `cardTitle()`, up to two lines of
latest activity, the needs-you `AttentionBlock`, a `project · model` mono line,
`TimeInState` and a MoreVert overflow `DropdownMenu`. Board's FAB is supplied
from `MainActivity` through `TabScaffold(floatingActionButton = …)`.

`ChatScreen.kt` (2028 lines) is a root `Column(modifier.fillMaxSize())` of
`ChatHeader` (line 500) → `PullToRefreshBox(weight(1f))` wrapping `ChatList`
(its `LazyColumn` at line 930) → a composer `Column` at line 348 modified with
`imeOrNavigationBarsPadding()`. Among shell routes only `ChatScreen` uses that
helper; Terminal, FileBrowser, Connect, the command palette and the session
pickers use it outside or above the shell.

Measured on the user's Galaxy Z Fold 8 (`SM-F971B`) via adb this session:

| State | px | dp @420dpi |
|---|---|---|
| Folded portrait | 1248×1972 | ~475×751 |
| Folded landscape | 1972×1248 | ~751×475 |
| Unfolded portrait | 1848×2448 | ~704×932 |
| Unfolded landscape | 2448×1848 | ~932×704 |

`AndroidManifest.xml` declares no `resizeableActivity` or `configChanges`
override, so folding is a plain config change and the activity recreates.

No `material3-window-size-class` or `androidx.compose.material3.adaptive`
dependency exists in `android/app/build.gradle.kts`.

`ui/theme/DESIGN.md` has sections World / Tokens / Typography / Components /
Behavior and motion, and no expanded-window contract.

Related plan: `.plans/p2-android-navigation-orchestration.md` intends to thin
`MainActivity` by moving route contracts and the nav graph into `ui/nav`. This
work adds to `MainActivity`, so it puts its new pieces in `ui/nav/` and
`ui/screens/` rather than growing the activity — see Scope.

## Objective and why

Unfolded, the app stretches to the full 932dp and the Chat transcript reads as
very long lines. Use the width for a persistent session list on the left, so
the unfolded device becomes a supervision console rather than a stretched
phone.

Done means, on a window ≥840dp wide:

- a 320dp left panel shows the live board session list on all four tab
  destinations and on Chat, and is absent on Terminal, Files, File viewer,
  Settings and Connect;
- selecting a session in the panel opens it in the detail pane without the
  panel, the bottom bar, or any other chrome appearing or disappearing;
- the Board destination's detail pane is a quiet empty placeholder;
- below 840dp every screen behaves exactly as it does today;
- Chat's transcript and composer are capped at a 600dp prose measure at every
  width.

## Scope

Included: the wide shell (breakpoint, panel, bottom-bar rule, detail
placeholder), a compact row variant of the board card, moving Board's FAB /
pull-to-refresh / banners into the panel on wide, Chat's inset contract under a
visible bottom bar, Chat's prose measure, the DESIGN.md expanded-window
section, and test coverage for the above.

Non-goals:

- No navigation-model change. The panel drives the existing `chat?…` route;
  `ChatViewModel` keying, deep-link resolution, notification navigation and
  bootstrap convergence are untouched.
- No `WindowSizeClass` or `material3-adaptive` dependency.
- No selection persistence mechanism (see the accepted assumption below).
- No auto-selection or last-session restore.
- Not the `p2-android-navigation-orchestration` refactor. New code lands in
  `ui/nav/` and `ui/screens/` so that refactor inherits it cleanly, but
  `MainActivity`'s existing structure is not reorganized here.
- Terminal, Files, File viewer, Settings and Connect keep the whole window.

Compatibility: no bridge or API change; no persisted-state change; nothing
below 840dp changes except Chat's width cap, which is deliberate and applies at
every width.

## Resolved decisions

Settled with the user; do not reopen.

1. **Breakpoint ≥840dp**, read from `BoxWithConstraints`, not a
   `WindowSizeClass` dependency. Precedent: `ReadableContentColumn` already
   reads width this way, and the project has no window-size-class artifact. A
   plain `Boolean` threaded from the shell is also directly testable without
   resizing a test window.
2. **Panel 320dp fixed**, detail pane takes the remainder (~612dp at 932dp).
3. **Bottom bar retained**, full width beneath both panes. No navigation rail —
   unfolded landscape is only 704dp tall, but the user chose the bar.
4. **Bottom bar stays visible on Chat when wide.** Compact behavior is
   unchanged: the bar still hides on Chat below 840dp.
5. **Panel owns its own header** (lockup + terminal + settings, the actions
   Board's top bar carries today). The detail pane keeps each route's own top
   bar.
6. **Panel lives outside the `NavHost`** and navigates the existing chat route.
   Selection highlight is derived from the current back-stack entry, never
   stored.
7. **Chat keeps its back arrow**; back pops the chat route and the detail pane
   returns to the placeholder.
8. **Board stays a bottom-bar destination on wide** and means "no session
   selected". It keeps the needs-you badge.
9. **Panel visible on the four tabs + Chat**; hidden on Terminal, Files, File
   viewer, Settings, Connect.
10. **Panel content is always the live board list**, on every destination. It
    does not follow the destination; Sessions' history stays in the detail pane.
11. **Compact row is a flag on `AgentCardRow`, not a fork.** It keeps the ring,
    mark + title, one line of activity, `project · model`, time-in-state, the
    overflow menu, the needs-you border and the quick-answer buttons, and drops
    the swipe-to-reveal bar (156dp of reveal inside a 320dp column). Review /
    Copy path / Close stay reachable through the existing overflow menu.
12. **Board's FAB, pull-to-refresh and banners move into the panel on wide**, so
    a version-mismatch banner is visible even mid-chat.
13. **Empty placeholder only** when nothing is selected. No auto-selection.
14. **Chat prose measure is 600dp**, centered, applied at every width. Tighter
    than the 960dp scan measure because Chat is prose while Board / Sessions /
    Review are scannable lists. At 704dp unfolded portrait this gives ~52dp
    gutters, at 751dp folded landscape ~75dp, and it also caps the 612dp detail
    pane. Reusing `ReadableContentColumn`'s 960dp was rejected: it never binds
    in any window state this device has, so it would be a no-op.
15. **On wide, the Board detail pane is a bare centered placeholder with no top
    bar.** The panel header already names the surface; a second "Board" bar
    beside it is duplicated chrome.
16. **This plan lives in `.plans/`, not `design-plans/`.** `design-plans/` is
    the numbered execution set from the 2026-08-12 UX audit (007 DONE, 010 and
    011 TODO); this is a blueprint, and `.plans/` is where blueprints live.

**Accepted assumption, carried not designed around:** folding is a plain config
change and Navigation restores the back stack, so an open chat survives
fold/unfold on its own. Change 7 includes a cheap check that this holds. If it
does not, that is an escalation, not a licence to add a persistence mechanism.

## Approach

`ScoutrAppNav` wraps its `Scaffold` in a `BoxWithConstraints` and computes
`isWide = maxWidth >= 840.dp`. The Scaffold's content becomes a `Row`: the
panel (when shown) then the `NavHost` at `weight(1f)`. The Scaffold's inner
padding moves from the `NavHost` to the `Row`, which is equivalent and lets the
panel sit inside the same inset contract as every screen.

Flow when the user taps a panel row:

`SessionPanel` row click → `onOpenSession(agent)` → `navigateToChatFromPanel`
in `MainActivity` → `navController.navigate(Routes.chat(key, status))` with
`launchSingleTop` and `popUpTo(Destination.Board.route)` → the NavHost swaps
the detail pane to `ChatScreen` → the panel recomposes, reads the new back-stack
entry, and derives its own highlight. Nothing else in the window moves.

Board's list body is extracted once into an `internal` `LazyListScope`
extension used by both `BoardScreen` (compact, full window) and `SessionPanel`
(wide, 320dp). Exactly one of the two is composed at a time, so exactly one
owns the `LifecycleStartEffect` that drives the board poll.

## Contracts and interfaces

**Shell route predicate** — new `ui/nav/ShellRoute.kt`:

```kotlin
/** The Chat route pattern. MainActivity's Routes.CHAT delegates here so the
 *  shell predicate and the NavHost declaration cannot drift. */
const val CHAT_ROUTE = "chat?sessionKey={sessionKey}&bootstrapPaneId={bootstrapPaneId}&status={status}"

/** Routes that keep the wide shell (session panel + bottom bar): the four
 *  tabs plus Chat. Everything else owns the whole window. */
fun isShellRoute(route: String?): Boolean =
    route in Destination.routes || route == CHAT_ROUTE
```

`NavHost` reports the route *pattern*, not the filled URL, so the comparison
must be against `CHAT_ROUTE` — matching on a `"chat?"` prefix of a filled URL
would never fire.

**Shell state derived in `ScoutrAppNav`:**

| Value | Expression | Consumers |
|---|---|---|
| `isWide` | `maxWidth >= 840.dp` from `BoxWithConstraints` | panel visibility, bottom bar, Board route, Chat inset flag |
| `showPanel` | `isWide && isShellRoute(currentRoute) && compatible` | the `Row` |
| `showBottomBar` | `compatible && (currentRoute in Destination.routes \|\| (isWide && currentRoute == CHAT_ROUTE))` | `Scaffold.bottomBar` |
| `selection` | see below | `SessionPanel` |

`compatible` is today's `boardUi.apiCompatibility == ScoutrApiCompatibility.Compatible`.

**Selection, derived not stored.** A chat entered by bootstrap has no
`sessionKey` argument until convergence, so both arguments must be read or a
freshly created session shows no highlight until the route rewrites:

```kotlin
data class PanelSelection(val sessionKey: String?, val paneId: String?)

// from backStack (currentBackStackEntryAsState) when destination.route == CHAT_ROUTE
PanelSelection(
    sessionKey = args?.getString("sessionKey")?.takeIf(String::isNotBlank),
    paneId = args?.getString("bootstrapPaneId")?.takeIf(String::isNotBlank),
)
```

A row is selected when `selection.sessionKey == agent.key.encode()` or
`selection.paneId == agent.live?.paneId`. Off Chat, selection is `null` on both
fields and no row is highlighted.

**Shared board list body** — in `ui/screens/BoardScreen.kt`, visibility widened
from `private` to `internal`:

```kotlin
internal fun LazyListScope.boardListContent(
    ui: BoardUiState,
    compact: Boolean,
    reduceMotion: Boolean,
    selectedPaneId: String?,        // null in the compact Board screen
    onOpenAgent: (SessionDescriptor) -> Unit,
    onReviewAgent: (SessionDescriptor) -> Unit,
    onCloseAgent: (SessionDescriptor) -> Unit,
    onQuickAnswer: (SessionDescriptor, String) -> Unit,
    onRetry: () -> Unit,
    onResolveCompatibility: () -> Unit,
)
```

It owns the banners, the empty state, and the five `boardSection` calls, and
forwards `compact` and the selected-row flag to `AgentCardRow`. Both callers
supply their own `PullToRefreshBox` + `LazyColumn`, because their padding
differs (Board clears its FAB with 96dp; the panel clears its own).

`AgentCardRow` gains `compact: Boolean = false` and `selected: Boolean = false`.
When `compact` is true it skips the reveal `Row` and the `anchoredDraggable`
modifier entirely — the `AnchoredDraggableState` must not be constructed, since
a drag anchor that can never be reached is dead state — and caps the activity
`Text` to `maxLines = 1`. `selected` adds the selected surface treatment; it
never changes the needs-you border, which stays status-owned.

**Panel** — new `ui/screens/SessionPanel.kt`:

```kotlin
@Composable
fun SessionPanel(
    viewModel: BoardViewModel,
    selection: PanelSelection?,
    onOpenSession: (SessionDescriptor) -> Unit,
    onReviewAgent: (SessionDescriptor) -> Unit,
    onCloseAgent: (SessionDescriptor) -> Unit,
    onQuickAnswer: (SessionDescriptor, String) -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
    onTerminal: () -> Unit,
    onResolveCompatibility: () -> Unit,
    modifier: Modifier = Modifier,
)
```

It renders `AppTopBar(title = "Board", showLockup = true, onTerminal =, onSettings =)`
— reused as-is, it already passes `WindowInsets(0.dp)` — then a
`PullToRefreshBox` over a `LazyColumn` of `boardListContent(compact = true)`,
with the New-session FAB as a `Box`-aligned overlay at bottom end. It owns the
`LifecycleStartEffect` that starts and stops the board poll.

**Chat inset contract** — `ChatScreen` gains:

```kotlin
/** True when the wide shell renders a bottom bar under this pane and has
 *  already consumed the IME and navigation-bar insets. */
bottomInsetOwnedByShell: Boolean = false,
```

When true the composer `Column` (line 348) drops `imeOrNavigationBarsPadding()`
and pads by nothing; when false it keeps today's modifier verbatim. On wide the
shell `Scaffold` carries `Modifier.imePadding()` so the panel, detail pane and
bottom bar ride above the keyboard together. This is the one place the
double-inset rule of fix 25df24f could regress, and change 5 exists to own it.

**Chat prose measure** — a documented token in
`ui/components/ReadableContentColumn.kt`, beside the scan measure it differs
from, and referenced from DESIGN.md:

```kotlin
/** Chat is prose, so it uses a tighter measure than the 960dp scan screens. */
val ChatProseMeasure = 600.dp
```

Applied by centering the `ChatList` and the composer block inside
`widthIn(max = ChatProseMeasure)`; `ChatHeader` stays full-bleed so the bar
still reads as chrome.

**Cross-change boundaries:**

| Produced by | Consumed by |
|---|---|
| `CHAT_ROUTE`, `isShellRoute` (change 1) | shell wiring (change 4) |
| `boardListContent`, `AgentCardRow(compact, selected)` (change 2) | `SessionPanel` (change 3) |
| `SessionPanel`, `PanelSelection` (change 3) | shell wiring (change 4) |
| `isWide` (change 4) | Chat inset flag (change 5) |

## Changes

- [ ] **1. The shell route set is one testable predicate**
  - Anchor: new `android/app/src/main/java/dev/scoutr/app/ui/nav/ShellRoute.kt`;
    `MainActivity.kt` → `private object Routes` → `CHAT` delegates to `CHAT_ROUTE`
  - Work: add `CHAT_ROUTE` and `isShellRoute` per the contract above. Point
    `Routes.CHAT` at the constant so the NavHost declaration and the predicate
    have one source, mirroring how `Destination.routes` already backs the bar
    check. No behavior change yet.
  - Proof: new `android/app/src/test/java/dev/scoutr/app/ui/nav/ShellRouteTest.kt`
    asserting the four tab routes and `CHAT_ROUTE` are shell routes and that
    `settings`, `terminal?paneId={paneId}`, `files?cwd={cwd}`,
    `file-viewer?cwd={cwd}&file={file}`, `connect` and `null` are not.
    `./gradlew :app:testDebugUnitTest --tests '*ShellRouteTest'`

- [ ] **2. The board list body is shared, and the card has a compact form**
  - Anchor: `ui/screens/BoardScreen.kt` → `boardSection`, `AgentCardRow`,
    `CompatibilityBanner`, `DisconnectedBanner`, `BoardScreen`
  - Work: extract `boardListContent` per the contract and rewrite `BoardScreen`
    to call it with `compact = false, selectedPaneId = null`, keeping its
    `PullToRefreshBox` → `ReadableContentColumn` → `LazyColumn` and its 96dp
    FAB clearance exactly as they are. Widen the extracted symbols to
    `internal`. Add `compact` and `selected` to `AgentCardRow`: under `compact`
    do not build the `AnchoredDraggableState`, do not render the reveal `Row`,
    do not attach `anchoredDraggable`, and cap latest activity to one line.
    Keep the overflow menu, the needs-you border and `AttentionBlock` in both
    forms. Phone behavior must be byte-for-byte equivalent.
  - Side effects: none. Swipe-to-reveal keeps working on the compact-window
    Board because that path passes `compact = false`.
  - Proof: `./gradlew :app:testDebugUnitTest --tests '*BoardFormatTest' --tests '*BoardAttentionTest'`
    green, and the existing `BoardScreenTest` instrumentation still passes in
    the final acceptance pass.

- [ ] **3. `SessionPanel` renders the live board in 320dp**
  - Anchor: new `ui/screens/SessionPanel.kt`; reuses `ui/components/AppTopBar.kt`
    → `AppTopBar` and `ui/screens/BoardScreen.kt` → `boardListContent`
  - Work: build the panel per the contract — `AppTopBar` header,
    `PullToRefreshBox` + `LazyColumn` over `boardListContent(compact = true)`,
    FAB overlay, `LifecycleStartEffect` owning `startPolling`/`stopPolling`,
    selection highlight from the `PanelSelection` parameter. Define
    `PanelSelection` here. Give the root a `board_session_panel` test tag and
    rows a stable `panel_agent_card_<paneId>` tag, following the existing
    `agent_card_<paneId>` convention.
  - Side effects: this composable becomes the board poll's owner whenever it is
    on screen. `startPolling`/`stopPolling` are `lifecycleActive`-guarded and
    therefore idempotent, so a brief overlap during a fold transition cannot
    double-start the poll.
  - Proof: new `androidTest` `SessionPanelTest` following `BoardScreenTest`'s
    precedent exactly — `createComposeRule`, a `BoardViewModel` over
    `FakeScoutrApi` + `ConnectionStore`, and a `Box(Modifier.width(320.dp))`
    container. It asserts section headers and rows render, that no reveal action
    node exists (compact form), that the overflow menu still opens, and that a
    row matching the supplied `PanelSelection` reports selected.

- [ ] **4. The wide shell composes panel + detail + bar**
  - Anchor: `MainActivity.kt` → `ScoutrAppNav`, its `Scaffold`, `bottomBar`
    lambda, `NavHost`, the `Destination.Board.route` `composable`, and
    `navigateToChat`
  - Work: wrap the `Scaffold` in `BoxWithConstraints` and derive `isWide`,
    `showPanel`, `showBottomBar` per the contract table. Replace
    `NavHost(modifier = Modifier.padding(inner))` with a `Row(Modifier.padding(inner))`
    holding `SessionPanel` at `Modifier.width(320.dp).fillMaxHeight()`, a
    `VerticalDivider`, and the `NavHost` at `weight(1f)`. Keep
    `contentWindowInsets = systemBars.only(Horizontal + Top)` unchanged — the
    panel is inside the same content area as every screen, so it inherits the
    existing contract and adds no second consumer. Add
    `navigateToChatFromPanel` using `launchSingleTop = true` and
    `popUpTo(Destination.Board.route) { inclusive = false }` so panel switching
    replaces rather than stacks chats; leave the existing `navigateToChat`
    untouched for Board cards, Sessions, the palette and deep links. In the
    Board `composable`, branch on `isWide`: wide renders a bare centered
    placeholder ("Select a session") with no `TabScaffold` and no FAB; compact
    renders today's `TabScaffold` + `BoardScreen` + FAB + `NewSessionSheet`
    unchanged. Hoist `showNewSession`/`NewSessionViewModel` so the panel's FAB
    can open the same sheet from any destination.
  - Side effects: on wide the Board route no longer composes `BoardScreen`, so
    the panel is the sole poll owner — verified by change 3's lifecycle
    ownership. The needs-you badge keeps working because it reads the same
    activity-scoped `BoardViewModel`.
  - Failure behavior: when `apiCompatibility` is `Incompatible` the panel is
    hidden along with the bar (existing `compatible` gate), so the
    compatibility banner keeps the full window as it does today.
  - Proof: `./gradlew :app:assembleDebug` green, plus final runtime acceptance
    on a wide emulator window (Validation).

- [ ] **5. Chat under a visible bottom bar keeps one inset owner**
  - Anchor: `ui/screens/ChatScreen.kt` line 348 composer `Column`, its
    signature at line 186; `MainActivity.kt` shell `Scaffold`
  - Work: add `bottomInsetOwnedByShell` per the contract and pass
    `isWide && showBottomBar` from the Chat `composable`. When wide, add
    `Modifier.imePadding()` to the shell `Scaffold` so panel, detail and bar
    move together with the keyboard; when compact, add nothing so today's
    behavior is untouched. Chat's composer keeps
    `imeOrNavigationBarsPadding()` exactly when the flag is false.
  - Failure behavior this change owns: a nav-bar-tall dead band above the
    composer, or a keyboard covering the bottom bar. Both are the failure mode
    of fix 25df24f and must be checked visually, not only compiled.
  - Proof: `./gradlew :app:testDebugUnitTest --tests '*ChatFormat*'` green for
    the compact path, and a wide-window screenshot with the keyboard open
    showing no gap between composer and bar and no bar hidden behind the
    keyboard (Validation).

- [ ] **6. Chat reads at a 600dp prose measure**
  - Anchor: `ui/screens/ChatScreen.kt` → `ChatScreen` root `Column` (line 225),
    the `PullToRefreshBox`/`ChatList` block, and the composer `Column`;
    `ui/components/ReadableContentColumn.kt` for the token
  - Work: define `ChatProseMeasure = 600.dp` in `ReadableContentColumn.kt`, next
    to the 960dp scan measure it deliberately differs from — `ui/theme/Theme.kt`
    holds colors, type and shapes only, no layout dimensions — and center both the transcript and the composer block inside
    `widthIn(max = ChatProseMeasure)`. Leave `ChatHeader` full-bleed. Keep
    `ChatList`'s `LazyColumn` at `fillMaxSize()` inside the constrained wrapper
    so its own `contentPadding` and scroll behavior (plan 007) are unchanged.
  - Proof: extend an existing `androidTest` `ChatListTest` case, or add one,
    asserting the `chat_list` node's width is ≤600dp when rendered in a
    900dp-wide container, and unchanged at 400dp.

- [ ] **7. The expanded-window contract is written down**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md` — new
    section after "Components"
  - Work: document the 840dp breakpoint, the 320dp panel, that the panel is
    always the live board list and never follows the destination, that the
    bottom bar is retained and stays visible on Chat when wide, that Board
    means "no session selected", the compact card's dropped swipe-to-reveal and
    one-line activity, the 600dp Chat prose measure against the 960dp scan
    measure, and the single-inset-owner rule under a visible bottom bar. Also
    record the fold assumption and how it was checked.
  - Proof: the fold check itself — with a chat open on the wide emulator
    window, toggle to a compact window size and back and confirm the chat is
    still on the back stack and reopens in the detail pane. Record the result
    in the section. Documentation-only otherwise, so no build check is needed
    (`scoutr-verification`: text-only changes take no broad verification).

## Failure handling

- **Incompatible bridge version:** the panel and the bottom bar are both gated
  on the existing `compatible` check, so an incompatible bridge falls back to
  the full-window compatibility banner exactly as today.
- **Disconnected:** the `DisconnectedBanner` renders inside `boardListContent`,
  so it appears at the top of the panel on wide and keeps its retry action
  reachable from any destination.
- **Empty board:** `boardListContent` keeps the existing "No agents running"
  empty state; in a 320dp panel it must not force horizontal overflow.
- **Fold mid-chat:** the activity recreates and Navigation restores the back
  stack. If the chat is lost, that is an escalation trigger, not a cue to add
  persistence.
- **Rapid panel switching:** `popUpTo(Board)` plus `launchSingleTop` bounds the
  back stack, so repeated selection cannot stack chats or leak per-session
  `ChatViewModel`s beyond the existing keying.
- **IME on wide Chat:** owned by change 5; the shell is the single inset owner
  and Chat pads by nothing when the shell says so.

## Validation

Cheap checks during implementation, per `skills/scoutr-verification/SKILL.md`:

- change 1 and the format tests: `cd android && ANDROID_HOME="$HOME/Android/sdk" ./gradlew :app:testDebugUnitTest --tests '*ShellRouteTest' --tests '*BoardFormatTest' --tests '*BoardAttentionTest'`
- compile confidence after the shell rewiring: `./gradlew :app:assembleDebug`
- no emulator, instrumentation or screenshot work in the inner loop.

Then `skills/scoutr-review/SKILL.md`, resolve or dismiss every finding, re-run
only invalidated cheap checks, and reach review-clean / code-frozen before any
runtime acceptance.

Final acceptance — one pass, smallest set covering the changed risk:

1. Boot `emulator-5554` (`cockpit` AVD) per `docs/dev-workflow.md`.
2. Reproduce the Fold's unfolded landscape exactly, since the AVD is a phone:
   `adb -s emulator-5554 shell wm size 2448x1848` and
   `adb -s emulator-5554 shell wm density 420` gives ~932×704dp. Revert with
   `wm size reset` and `wm density reset` when done, and do not leave the
   override in place across other runs.
3. Focused instrumentation covering the changed UI:
   `./gradlew pixel2api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.scoutr.app.ui.SessionPanelTest`
   plus the existing `BoardScreenTest`, `NavHostGraphTest` and `ChatListTest`.
   Instrumentation runs on the emulator only, never the physical Fold.
4. Visual evidence at 932×704dp: panel + Board placeholder; panel + open chat
   with the bottom bar present; panel + Sessions in the detail pane; keyboard
   open on Chat (change 5's failure mode); and the fold check from change 7.
   Use `skills/scoutr-vision/SKILL.md` if the active model cannot read images.
5. Confirm compact is untouched at the default emulator size: bottom bar still
   hides on Chat, board swipe-to-reveal still works.

The user's own Fold 8 is available over adb for a final look at the real
device (`adb -s adb-RQGL803LWLX-rSwS4M._adb-tls-connect._tcp shell screencap`),
but it is evidence, not a test target.

## Local discretion

Divider treatment between the panes; the exact placeholder wording and its
typography; panel row vertical rhythm within the compact contract; the selected
row's surface token (subject to DESIGN.md's palette); FAB placement within the
panel; test tag spellings that follow existing conventions; whether
`PanelSelection` lives in `SessionPanel.kt` or `ui/nav/`; import organization.

None of these may change the breakpoint, the panel width, what the panel shows,
which routes show it, the navigation model, the inset ownership rule, or the
600dp measure.

## Escalation triggers

Return to `gd` rather than improvising if:

- an open chat does **not** survive fold/unfold on the back stack — the
  accepted assumption is wrong and a persistence decision is needed;
- the shell `imePadding()` approach cannot keep the bar and composer correct
  together, so keeping the bar visible on wide Chat (decision 4) conflicts with
  the inset contract;
- 320dp proves unworkable for the compact row's quick-answer buttons, so
  decision 11's kept affordances and decision 2's width conflict;
- `boardListContent` cannot be extracted without changing compact Board
  behavior;
- 600dp visibly breaks wide code blocks, diffs or tool output in the transcript;
- the same layout defect survives three fixes under a stated cause — reset the
  causal model instead of a fourth attempt.

## Completion checklist

1. Changes 1–7 implemented in dependency order.
2. Each change's cheap proof run and green.
3. `scoutr-review` run; every finding resolved or dismissed.
4. Invalidated cheap checks re-run; code frozen.
5. One final acceptance pass per Validation, including the wide-window
   screenshots and the fold check.
6. Emulator size and density overrides reverted; `emulator-5554` killed.
7. DESIGN.md section reflects what actually shipped, including the fold-check
   result.
8. Commit on `main`, no `Co-authored-by`, `git push --follow-tags`.

## References

- `android/app/src/main/java/dev/scoutr/app/MainActivity.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/nav/Destination.kt`, `ui/nav/TabScaffold.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/BoardScreen.kt`, `ui/screens/ChatScreen.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/components/ReadableContentColumn.kt`, `ui/components/AppTopBar.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/ImeOrNavigationBarsPadding.kt`
- `android/app/src/main/java/dev/scoutr/app/state/BoardViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`
- `AGENTS.md`, `skills/scoutr-verification/SKILL.md`, `skills/scoutr-review/SKILL.md`, `docs/dev-workflow.md`
- `.plans/p2-android-navigation-orchestration.md` (later refactor this work must not fight)
- `design-plans/README.md` (007 DONE, 010/011 TODO — plan 007 owns Chat scroll behavior change 6 must not disturb)
