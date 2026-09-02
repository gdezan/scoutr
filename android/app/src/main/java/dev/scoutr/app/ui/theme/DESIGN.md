# Scoutr — design system

Scoutr is a dark-first, glanceable supervision console for agents running on a
host. The phone is the pilot seat: surfaces answer what is live, what needs the
operator, and what is safe to touch. The board is the visual anchor; transcripts
stay quiet and readable.

## World

- **Always dark.** The physical scene is a phone in a dim room; never follow
  system light mode.
- **OLED canvas.** `background` is true near-black. Boards and transcripts do
  not get shadows, gradients, or glow.
- **Tonal hierarchy.** `surface` is the calm panel; `surfaceContainer` is the
  tile; `surfaceContainerHighest` is reserved for the strongest neutral bubble
  or thinking block.
- **Status is the color language.** Green means live/working, gray means done
  or settled, red means the operator is needed or something failed, and amber
  means a warning or threshold. Teal belongs to Usage charts only.

## Tokens (`ui/theme/Theme.kt`)

| Role | Value | Use |
|---|---|---|
| `background` | `#0B0C0E` | canvas, board, transcript |
| `surface` | `#121316` | cards and panels |
| `surfaceContainer` | `#16171B` | tiles and controls |
| `surfaceContainerHighest` | `#1A1C20` | user bubbles, thinking blocks |
| `surfaceVariant` | `#1B1D21` | quiet selected/secondary surfaces |
| `onSurface` | `#ECEDF0` | primary text |
| `onSurfaceVariant` | `#A8AEB9` | secondary text and settled states |
| `primary` | `#8DF08D` | live agent, AI-owned action, active send |
| `primaryContainer` | `#12301A` | live/AI-owned container only |
| `secondary` | `#2C6F72` | Usage data and charts only |
| `error` | `#E5484D` | needs-you, failures, destructive actions |
| `tertiary` | `#E8B84B` | warnings and high usage thresholds |
| `outline` | `#363B43` | dividers and field borders |
| `outlineVariant` | `#26292E` | quiet separators |

Shapes are compact: 4dp extra-small, 6dp small, and 8dp medium/large. A
status dot is a 9dp outlined ring with a 2.5dp stroke; it is never a filled
circle. Pills are not a general-purpose component: use a bounded 6dp shape for
status metadata and controls.

## Typography

- **Space Grotesk** is the UI family: headings, labels, buttons, and body.
- **Martian Mono** is bundled for machine facts: paths, commands, hashes, tool
  output, model/provider identifiers, and measurements.
- **JetBrains Mono** is bundled and used only by the full-screen terminal grid.
- Mono is never used as decoration or for ordinary labels.

## Components

- **Board:** section header plus compact count; cards carry a 9dp status ring, title,
  latest activity, mono workspace path, and quiet time-in-state. Nested PI-workflow
  subagents sit as compact role/label rows under the parent machine-facts line, each
  with its own status ring; tapping a row or an orphan card opens progress, not Chat.
  Needs-you is the only loud card treatment: red ring/border and red metadata. Done is gray.
  Idle collapses to a count row until tapped.
- **Chat:** assistant rows use a quiet 1px outline spine with readable text; tool
  calls are one-line mono facts with a right-side details icon and expand into
  4dp-radius inline result tiles. A skill or slash-command invocation is a
  `surfaceContainer` chip above the user bubble: the command in Martian Mono,
  tap to expand the injected body. The leftover prompt stays in the user
  bubble; the harness markup never does. The composer is a 6dp outlined
  field, multiline, and Enter always inserts a newline.
- **Sessions:** reuse the board tile geometry with date-group headers. Search
  and picker fields use the shared 6dp field.
- **Usage:** teal for usage data, amber/red only for threshold states. It must
  not look like an agent status screen.
- **Review:** one header carrying the title plus a mono `~/repo · ref · N files`
  line, a three-tile stat strip (added / removed / files), and file tiles that
  expand in place into their hunks. Commits live behind the header's history
  glyph; switch-repo, refresh, and wrap sit in its overflow. Additions use
  `#3FC9E8`, not the live green. Paths, hashes, and diff lines use Martian Mono.
  The reference's Commit / Stage / Revert bar is deliberately absent — the
  bridge's repo surface is read-only, so Review has nothing to write.
- **Subagent progress:** read-only run-store payload — role/label, status ring,
  model · thinking line, context · turns · cost · elapsed facts line, task,
  one-line chat-spine `monoTool` tool lines (name emphasised, args dimmed, error lines red), markdown last message and result, error,
  truncated notice. No composer, no asks, no steer. Back is the only exit.
  Orphan cards and nested rows land here, not Chat.
- **Terminal:** edge-to-edge true mono output, JetBrains Mono, with a compact
  workspace › tab › pane breadcrumb selector above the grid and hierarchy and
  modifier rows kept out of the transcript.

## Expanded windows

Below 840dp the app is single-pane and behaves exactly as it always has. At or
above 840dp — an unfolded Fold, a tablet, a large freeform window — the shell
splits:

- **Breakpoint 840dp**, read from `BoxWithConstraints` in `ScoutrAppNav`. No
  window-size-class dependency; `ReadableContentColumn` already reads width the
  same way.
- **Session panel, 320dp fixed**, on the left; the detail pane takes the
  remainder. The panel shows on the four tab destinations and on Chat, and is
  absent on Terminal, Files, File viewer, Settings, Connect, and Subagent progress.
- **Navigation lives in the session panel.** The four destinations render as
  an icon row at the panel's foot, below a hairline divider (shared
  `DestinationNavRow`); the row stays visible on Chat when wide and keeps
  the needs-you badge on Board. Nothing sits beneath the panes, so the detail
  pane keeps the window's full height.
- **The list is always the live board list.** It does not follow the
  destination — Sessions' history stays in the detail pane. It carries its own
  header (lockup, terminal, settings), the new-session FAB, pull-to-refresh and
  the disconnected / version-mismatch banners, so a mismatch is visible even
  mid-chat. The detail pane keeps each route's own top bar.
- **Board means "no session selected."** It stays a navigation destination and
  keeps its needs-you badge; its detail pane is a bare centered placeholder
  with no top bar, because the panel header already names the surface.
- **Selection is derived, never stored.** The highlighted row comes from the
  current back-stack entry (`sessionKey`, or `bootstrapPaneId` until a fresh
  session's route converges). Selection is a surface step only; the needs-you
  border stays status-owned.
- **The compact card** is the same anatomy with one line of latest activity and
  no swipe-to-reveal: 156dp of reveal does not fit a 320dp column, so Review /
  Copy path / Close live in the overflow menu there. The full-window Board keeps
  its swipe.
- **Chat reads at a 600dp prose measure** (`ChatProseMeasure`), centered, at
  every width — tighter than the 960dp scan measure that Board, Sessions and
  Review use, because Chat is prose. The chat header stays full-bleed.
- **One inset owner at the bottom.** The shell `Scaffold` consumes only the
  horizontal and top system bars. Compact tabs leave the bottom to
  ScoutrBottomBar; wide tabs own it via `TabScaffold(ownsBottomInset)`; Chat
  and every non-shell screen apply `imeOrNavigationBarsPadding()` themselves,
  On wide windows the panel's destination row clears the nav bar and the FAB
  keeps only its 16dp stand-off above it. A second consumer stacks
  into a nav-bar-tall dead band (fix 25df24f).

Folding is a plain config change: the activity recreates and Navigation restores
the back stack, so an open chat survives fold and unfold with no persistence
mechanism of its own. Checked on the emulator at 932x704dp: with a chat open,
resizing to a compact window kept the chat (full window, no panel, no bottom
bar) and resizing back returned it to the detail pane beside the panel, still
highlighted.

## Behavior and motion

Agent-busy motion uses `WorkingIndicator`; no unrelated screen animation is
allowed. Press tint uses a 90ms fade with no scale, new content arrives with
a 140ms fade and zero placement motion, and overlays fade without translation.
Motion yields to `LocalReduceMotion`, which makes status glyphs static.
Loading indicators are reserved for actual loading. Errors are inline and name
the problem. Empty states explain the next action.
