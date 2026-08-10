# Cockpit — accessibility, security, and performance audit

Date: 2026-08-10. Covers the surfaces shipped through the production-goal
milestone (`37b9de0`…`49bbf02`). Each finding states the evidence and any
residual risk.

## Security

- **No raw herdr socket exposure.** The bridge is the only process touching
  `~/.config/herdr/herdr.sock`; every API surface goes through
  `isAuthorized` (constant-time `timingSafeEqual`, server.ts:135) or the WS
  handshake (server.ts:175). The Android app only ever talks HTTP/WS to the
  bridge.
- **Token handling.** `cockpit_<18 random bytes>` lives in
  `~/.config/cockpit/config.json` (mode 0600) and on-device SharedPreferences
  (`cockpit_connection`). Never written to logs.
- **Read-only review center.** `resolveAllowedRepoPath` realpaths the request
  and requires it under `COCKPIT_REPO_ROOTS` (default `~/.herdr/worktrees`);
  refs match a conservative regex; git runs via `execFile` (no shell) with a
  per-command timeout; outputs capped at 2 MiB internal / 64 KiB response;
  artifacts walk is bounded (depth 8, 2000 dirs, 100 files) and never follows
  symlinks.
- **Attachments.** `POST /api/attachments` accepts only `image/*` content
  types with an allow-listed extension, caps the body at 10 MiB while
  streaming, stores in a dedicated uploads dir, and prunes to 200 files /
  200 MiB. No executable paths can be produced (random prefix + sanitized
  name).
- **Commands fail closed.** The slash-command catalog rejects invalid trust
  data and delivers each command through one atomic `pane.send_input`
  (documented in the checklist).
- Residual: the bridge binds `127.0.0.1` and is fronted by tailscale serve
  TLS; anyone with the token can steer panes — token rotation is manual.

## Performance

- **Bounded reads everywhere.** Board detail reads a 64 KiB tail per session
  (memoized by path+mtime); live output caps at 48 KiB with a 3 s timeout;
  review diffs cap at 64 KiB; session read uses a since-cursor; catalog reads
  are bounded by limit.
- **Lifecycle cancellation.** Poll loops (board, session history, chat,
  live output, monitoring service) hold a `Job` cancelled in `onCleared()` /
  `onDestroy()`; the live-output poll runs only while the drawer is expanded
  and the lifecycle is STARTED.
- **List stability.** LazyColumn keys are stable ids everywhere (entry ids,
  pane ids, question ids, session paths, artifact paths); question cards
  upsert by id so cursor resets never duplicate.
- **Recomposition.** State flows are `StateFlow`; per-row busy flags avoid
  whole-list invalidation on mutations.
- Residual: the review artifacts walk is synchronous on the bridge event
  loop; with the dir cap (2000) it stays bounded but is not offloaded to a
  worker.

## Accessibility

- **Touch targets.** Interactive IconButtons use M3's 48 dp minimum;
  header chips were aligned to a common height so the 48 dp clickable chips
  no longer misalign with the status chip (S24 Ultra feedback).
- **TalkBack semantics.** Interactive icons carry `contentDescription`
  (Search, Settings, Back, Attach image, Send, Session actions, visibility
  toggle); decorative icons pass `contentDescription = null`. Usage rows
  include locale-safe amounts and provider/window names; review status codes
  are text, not color-only (colors are the diff palette but the code letters
  remain readable).
- **Contrast.** Always-dark palette uses off-white type on near-black
  surfaces; dim gray secondary for metadata at alpha ≥ 0.55. Diff colors are
  high-chroma on tinted rows. Residual: `onSurfaceVariant.copy(alpha = 0.55f)`
  on board card paths is borderline for small mono text.
- **Font scaling.** Verified at scale 2.0 (`/tmp/cockpit-board-largefont.png`);
  rows use `maxLines` + ellipsis and the composer grows (`maxLines 6`).
- **Motion reduction.** `ReduceMotionStore` mirrors the system animator scale;
  `CockpitMotion` specs and NavHost transitions collapse to `tween(0)`;
  verified stills at scale 0 (`/tmp/cockpit-board-reducemotion.png`).
- **IME.** The composer is multiline (`imeAction None`, no-op
  `KeyboardActions`), so the soft keyboard's enter key inserts a newline and
  can never submit; pinned by `ChatComposerKeyTest`.
- Residual: a formal TalkBack walk-through and a contrast-meter pass on every
  surface are still outstanding; the history "Clear" affordance moved into
  the shared text field's trailing icon.
