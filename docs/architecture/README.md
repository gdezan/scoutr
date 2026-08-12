# Architecture plans — extendability

Written 2026-08-11 from an architecture review focused on one question: **what does it
cost to extend Cockpit?** Concretely — add a second agent backend (Claude Code next to
`pi`), add a feature, add a screen, add an endpoint.

The review used the `/codebase-design` vocabulary: a **module** is deep when a small
interface hides a lot of implementation; a **seam** is where one module's interface meets
another's; an **adapter** is an implementation behind a seam; **locality** is how much of a
change lands in one place; **leverage** is how much future work one change unlocks. The
**deletion test**: if deleting a module would *concentrate* complexity, it earns its
place — if it would just *move* complexity, it is shallow.

## Headline finding

The bridge has no agent seam. `pi` is not a module — it is a property of the whole
daemon, spread across launch strings, TUI keystrokes, three independent JSONL parsers, a
questions extractor, a models catalog, a filesystem sandbox, and a usage provider list.
Nothing in `bridge/src` ever compares an agent name to `"pi"`, which reads like
agent-agnosticism but is the opposite: there is no discriminator *because* there is only
one possible answer.

herdr itself already speaks 16 agents (`bridge/reference/herdr-schema.json` enumerates
`pi, claude, codex, copilot, opencode, …`), and `AgentSessionInfo {source, agent, kind}`
is already polymorphic. The abstraction exists one layer down; the bridge collapses it.

Android is in much better shape: no model id, provider name, or vendor string appears
anywhere in `app/src/main` (one doc-comment example aside). Its extendability costs are
different — a missing test seam at `BridgeClient`, a stringly-typed action vocabulary,
and four hand-maintained lists that must agree for a new tab to appear.

## Plans

| # | Plan | Strength | Unlocks |
|---|---|---|---|
| 1 | [Agent backend seam in the bridge](01-agent-backend-seam.md) | **Shipped** (`688c020`) | A second agent at all |
| 2 | [One transcript module, three parsers deleted](02-transcript-module.md) | **Shipped** (`f48c82c`) | Prerequisite for 1 |
| 3 | [Herdr port + route table so the HTTP surface is actually tested](03-bridge-route-table-and-herdr-fake.md) | **Shipped** (`3c612bc`, `86a5b7a`) | Safe change to 1 and 2 |
| 4 | [A `CockpitApi` seam on Android](04-android-cockpit-api-seam.md) | **Shipped** (`491e50c`) | Testable features + WS coverage |
| 5 | [Capability-driven session actions](05-capability-driven-session-actions.md) | **Shipped** (`20bb93b`) | Per-agent control sets |
| 6 | [A `Poller` and a `Loadable` for the ViewModels](06-android-poller-and-loadable.md) | **Shipped** (`fad4d24`) | Cheaper screens |
| 7 | [Navigation registry](07-navigation-registry.md) | **Shipped** (`d46a6a4`) | Cheaper tabs |

## Top recommendation

**All seven plans shipped, in the order the sequencing section predicted.**
Plan 2 landed first (`f48c82c`, one `bridge/src/transcript.ts`), plan 3 made the route
layer testable offline (`3c612bc`, `86a5b7a`), plan 1 landed last and cheap (`688c020` —
the seam the analysis below argues for), then 4 (`491e50c`), 6 (`fad4d24`), 5
(`20bb93b`), and 7 (`d46a6a4`) on the Android side. The table above is now a historical
record; the analysis prose below still explains *why* each plan had that shape.

Live planning has moved to the implementation campaign in `plans/` (001–009, execution
ledger in `docs/PLAN-EXECUTION-REPORT.md`); read `plans/README.md` for the current
status. If a third agent backend or a new screen seam ships, only the commit column of
this table needs touching.

## Sequencing

```
2 ──▶ 3 ──▶ 1 ──▶ 5   (all executed)
4 ──▶ 6               (executed)
7 (standalone)        (executed)
```

## Notes on prior decisions

`docs/decisions.md` is the project's decision record. Two entries constrain this work and
were respected:

- **`pi --mode rpc` was removed with no shims** ("Sessions v2 (layer 3)"). No plan here
  reintroduces a bridge-owned agent process; every backend stays pane-native.
- **Controls type into the live pane** ("Session controls — v1 limits"). Plan 5 keeps
  that model and only makes the verb set per-agent instead of global.

None of these plans contradicts a recorded decision.
