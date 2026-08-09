# Cockpit — design system

_Written from the built world after the layer-2 design pass (impeccable
finish step). North star: the pinned Cursor-iOS brief in
`docs/cursor-ios-design-brief.md`; direction contract lives in
`Theme.kt`'s KDoc._

## World

Near-black canvas, elevated charcoal surfaces, off-white type, dim gray
secondary, one electric-blue accent. The running agent's state is the visual
anchor; the interface disappears into the task (Operate mode). Blue is
reserved for AI-owned states: active run, "needs you", the composer's send,
the scroll-to-end button. Status is never encoded by blue alone — success
(green), error (red), and warning (amber) stay on their native semantic
colors.

## Tokens (`ui/theme/Theme.kt`)

| Role | Value | Use |
|---|---|---|
| `background` | `#0B0C0E` | canvas |
| `surface` | `#121316` | cards, panels |
| `surfaceVariant` | `#1B1D21` | chips, inputs, FABs |
| `surfaceContainerHigh/Highest` | `#16171B` / `#1A1C20` | user bubbles, thinking blocks |
| `onBackground`/`onSurface` | `#ECEDF0` | primary text |
| `onSurfaceVariant` | `#A8AEB9` | secondary text (≥4.5:1 on surfaces) |
| `primary` | `#5B8CFF` | AI accent (active state, send, FAB) |
| `outline` | `#363B43` | 1px dividers, input borders |
| `error` | `#E5484D` | tool errors, failures |

Always dark — the physical scene is a phone in a dim room; the app never
follows system light.

## Typography

- One family: the Material default sans for everything (headings, labels,
  body) — no display/body pairing in product UI.
- **Monospace only for code, data, and measurement**: pane ids, workspace
  paths, tool names, tool output, model labels, the header's model·status
  line. Never as a costume.
- Weights: `SemiBold` on tool names and section headers; medium body;
  label sizes for captions. Muted labels at `onSurfaceVariant`.

## Components

- **Board card**: 6dp status dot (semantic color), title + mono path at 60%
  opacity, muted time-in-state pill (`now`/`12m`/`2h`/`3d`), filled accent
  pill reserved for "needs you", header count pills (`Working · 1`), hidden
  empty sections, one centered muted line for the zero state.
- **Chat header**: back arrow, title + pane id, mono `model · status` label
  (accent when "needs you"), details toggle (eye icon, accent when on),
  1px divider.
- **Message rows**: user = right-aligned neutral bubble (`surfaceContainerHighest`,
  18dp radius, max 288dp, 4dp right margin); assistant = plain full-width
  text (no bubble) with tool-call chips inline when details are on;
  model caption lives in the header only.
- **Tool chips** (call + result): `surfaceVariant` tonal chip, 12dp radius,
  mono `▸ name` header + command/output in mono. Collapsed: command on one
  line / output clamped to 3 lines with ellipsis. Tap toggles `▾` expanded.
  Errors tint `error`. Tool output text at `onSurface` 0.85 for contrast.
- **Thinking blocks**: `surfaceContainerHighest` card, "thinking" label +
  italic body, only visible with details on.
- **Composer**: rounded 22dp field, `surfaceContainerHigh` fill, outline
  border, blue border + cursor on focus, send icon blue when there is text.
- **Scroll-to-end FAB**: 40dp `surfaceVariant` circle with blue down arrow,
  fades/slides in only when the transcript is scrolled up; tap returns to
  the true bottom and resumes following.

## Behavior

- Chat opens at the true bottom of the last message and follows appends
  while the user is at the bottom; any scroll up stops following and
  surfaces the FAB. Auto-scroll is index-bounded and exception-guarded —
  concurrent transcript growth can never crash it.
- Board polls `/api/agents` every 3s; ntfy push polls separately; no
  long-lived WebSockets (the EOF crash class).
- Details toggle (eye) shows thinking blocks and expands all tool calls.

## Motion

Restrained, state-conveying only: FAB fade/slide in-out (150–250ms),
LazyColumn `animateItem()` placement on new messages, animated tool-chip
expand via maxLines. No orchestrated load sequences.

## States

Loading = centered spinner (skeletons deferred); error = inline `error`
text naming the problem; empty transcript = centered guidance line; every
interactive control has an enabled/disabled pair (composer send dims when
empty or sending).
