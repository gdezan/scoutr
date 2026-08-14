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
  latest activity, mono workspace path, and quiet time-in-state. Needs-you is
  the only loud card treatment: red ring/border and red metadata. Done is gray.
  Idle collapses to a count row until tapped.
- **Chat:** assistant rows use a quiet 1px outline spine with readable text; tool
  calls are one-line mono facts with a right-side details icon and expand into
  4dp-radius inline result tiles. The composer is a 6dp outlined field,
  multiline, and Enter always inserts a newline.
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
- **Terminal:** edge-to-edge true mono output, JetBrains Mono, with a compact
  workspace › tab › pane breadcrumb selector above the grid and hierarchy and
  modifier rows kept out of the transcript.

## Behavior and motion

Agent-busy motion uses `WorkingIndicator`; no unrelated screen animation is
allowed. Press tint uses a 90ms fade with no scale, new content arrives with
a 140ms fade and zero placement motion, and overlays fade without translation.
Motion yields to `LocalReduceMotion`, which makes status glyphs static.
Loading indicators are reserved for actual loading. Errors are inline and name
the problem. Empty states explain the next action.
