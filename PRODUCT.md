# Cockpit — product context

_Compiled by the agent from the accumulated project brief (goal, decisions,
research, and user instructions). Where the original brief was explicit it is
labeled as such; inferences are labeled._

## What this is

Cockpit is a native Android app (Kotlin + Jetpack Compose, Material 3) that
turns a phone into a remote supervision console for the user's AI coding
agents. Agents run on a Linux host (herdr + pi); a local bridge daemon owns
the herdr socket and exposes a token-authed HTTP/WSS API over the tailnet.
The phone shows a live board of agents, per-agent chat/steer, usage rings,
and push notifications — Moshi-style, self-hosted, no subscriptions.

**The mechanism in one sentence:** the phone is the pilot's seat for agents
running on the machine — watch what is running, answer when they need you,
steer when they stall.

## Audience and scene

The user (an experienced engineer) supervises 1-3 coding agents at once,
often in a dark room or while away from the desk; one hand holds the phone,
the other is free. Sessions are short, frequent glances — check status,
answer a question, send a steer, close it. Every screen should answer:
_What is running? What needs my input? Is this safe to ship?_ (Cursor iOS
brief, inference).

## Design north star (user-pinned)

The user explicitly pinned **the Cursor iOS app as the design/UX north
star** ("take heavy inspiration on it") and asked for `/impeccable`-grade
design and animation. Cursor iOS is a directing/reviewing surface, not an
IDE: near-black surfaces, off-white type, restrained typography, hard-edged
depth, one electric-blue AI accent, mono only for paths/commands/diffs,
stack-based navigation, attention-first cards, a streaming event timeline,
and a persistent follow-up composer. (docs/cursor-ios-design-brief.md)

**Mode: Operate.** The interface disappears into the task; consistency over
surprise; calm cards so a running agent's state is the visual anchor.

## Product decisions already made (from decisions.md + goal)

- Dark-first, always (never follows system light).
- Grouped vertical board list (not kanban) — taste-reviewed; paths are the
  disambiguator between agents.
- Board cards: 6dp status dot, muted time-in-state pill, filled accent pill
  reserved for "needs you", workspace path in mono at 60% opacity.
- Board polls /api/agents every 3s (no long-lived WS — crash class).
- Chat: pane-native sessions (new herdr workspace + pane + pi --model),
  JSONL transcript polling with a since cursor, dedupe by entryId,
  answer/steer unified composer.
- Push: self-hosted ntfy, app-side polling, blocked + done notifications.
- QR pairing (Moshi-style Easy Pair).
- Usage: Codex and Claude quota windows, DeepSeek balance, and xAI weekly credits; rings on the usage screen.

## Constraints

Never expose the herdr socket raw; auth.json / models-store.json read-only;
no cloud/subscriptions; no Play Store; user is not interrupted (runs inside
a goal); /simplify before every commit; close every herdr pane created.
