# Adopt the Scoutr design system v2

Scoutr Android adopts the v2 design contract documented in `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md` and the locked design reference in `docs/design/`.

## Context

The app had accumulated blue-era theme tokens, inconsistent corner radii, default typography, loading spinners/skeletons, and status treatments that differed between Board, Chat, Sessions, Usage, Review, and Terminal. The Cursor iOS brief remains useful as a product-pattern reference, but its visual recommendations are not an Android implementation contract.

## Decision

- Keep the app always dark with near-black OLED surfaces and compact 4/6/8dp geometry.
- Use green `#8DF08D` only for live or AI-owned state, gray for done, red for user attention, and teal `#2C6F72` only for Usage charts/data.
- Use Space Grotesk for UI, Martian Mono for small machine facts and code, and JetBrains Mono for full-screen terminal content.
- Render agent status as unfilled 9dp rings with 2.5dp strokes. Under reduce motion, animated indicators become static rings.
- Replace loading spinners and skeletons with observable text or static feedback. Preserve the intentional live-state ripple in `WorkingIndicator`.
- Use compact structural patterns: the Board lockup and mono-caps count headers, the Chat assistant spine, inline tool detail tiles, and a filled 6dp square send action.
- Use a 90ms press tint without scale, 140ms fade-only arrivals without placement motion, and a workspace › tab › pane breadcrumb selector for Terminal.
- Group Sessions by dynamically derived repository tabs while keeping Active, Completed, Pinned, and Archived scopes reachable through the scope filter.
- Bundle the approved fonts and use the supplied Scoutr icon asset for launcher and splash surfaces.

## Consequences

The Android UI has one semantic status language and a smaller, more discoverable token surface. The redesign is intentionally not backward-compatible with blue-era guidance or oversized controls. Existing tests and documentation that assert the retired visual contract must be updated rather than preserved. The deferred home-screen widget remains out of scope.
