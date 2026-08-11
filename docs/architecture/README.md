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
| 1 | [Agent backend seam in the bridge](01-agent-backend-seam.md) | **Strong** | A second agent at all |
| 2 | [One transcript module, three parsers deleted](02-transcript-module.md) | **Strong** | Prerequisite for 1 |
| 3 | [Herdr port + route table so the HTTP surface is actually tested](03-bridge-route-table-and-herdr-fake.md) | **Strong** | Safe change to 1 and 2 |
| 4 | [A `CockpitApi` seam on Android](04-android-cockpit-api-seam.md) | **Strong** | Testable features + WS coverage |
| 5 | [Capability-driven session actions](05-capability-driven-session-actions.md) | Worth exploring | Per-agent control sets |
| 6 | [A `Poller` and a `Loadable` for the ViewModels](06-android-poller-and-loadable.md) | Worth exploring | Cheaper screens |
| 7 | [Navigation registry](07-navigation-registry.md) | Worth exploring | Cheaper tabs |

## Top recommendation

**Start with plan 2, then plan 3, then plan 1.**

Plan 1 is the goal, but attempting it first means rewriting three duplicated parsers and
a router whose tests silently skip (`bridge/test/server.test.ts:16` gates the entire HTTP
suite on a live herdr socket existing). That is a refactor with no net.

Plan 2 is pure consolidation with excellent existing coverage — `pi-session.test.ts`,
`session-catalog.test.ts`, and `board-detail.test.ts` already pin the behaviour of all
three parsers, so collapsing them is verifiable on the first run. Plan 3 then makes the
route layer testable offline, which is what turns plan 1 from a leap into a series of
small green steps. Plan 1 lands last and lands cheap.

Plans 4–7 are independent of 1–3 and can proceed in parallel; plan 4 is the one with real
leverage on the Android side, and plan 5 only becomes urgent once plan 1 ships.

## Sequencing

```
2 ──▶ 3 ──▶ 1 ──▶ 5
4 ──▶ 6
7 (standalone)
```

## Notes on prior decisions

`docs/decisions.md` is the project's decision record. Two entries constrain this work and
were respected:

- **`pi --mode rpc` was removed with no shims** ("Sessions v2 (layer 3)"). No plan here
  reintroduces a bridge-owned agent process; every backend stays pane-native.
- **Controls type into the live pane** ("Session controls — v1 limits"). Plan 5 keeps
  that model and only makes the verb set per-agent instead of global.

None of these plans contradicts a recorded decision.
