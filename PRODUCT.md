# Scoutr — product context

_Compiled by the agent from the accumulated project brief (goal, decisions,
research, and user instructions). Where the original brief was explicit it is
labeled as such; inferences are labeled._

## What this is

Scoutr is a native Android app (Kotlin + Jetpack Compose, Material 3) that
turns a phone into a remote supervision console for the user's AI coding
agents. Agents run on the user's own host (Linux or macOS, herdr + pi); a
local bridge daemon owns the herdr socket and exposes a token-authed HTTP/WSS
API on loopback, published to the phone through whichever **exposure** the
user configures — Tailscale, a Cloudflare Tunnel, or their own reverse proxy.
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

The user explicitly pinned **the Cursor iOS app as the design/UX north star** for supervision patterns. Cursor remains the product reference; Scoutr Android's visual contract is the v2 system in `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md` and ADR 0005: always-dark near-black surfaces, green `#8DF08D` for live/AI-owned state, gray for done, red for user attention, teal `#2C6F72` for Usage data, compact geometry, and restrained motion. Mono is limited to machine facts, code, paths, commands, and terminal output.


**Mode: Operate.** The interface disappears into the task; consistency over
surprise; calm cards so a running agent's state is the visual anchor.

## Product decisions already made (from decisions.md + goal)

- Dark-first, always (never follows system light).
- Grouped vertical board list (not kanban) — taste-reviewed; paths are the
  disambiguator between agents.
- Board cards: 9dp unfilled status rings, mono-caps section counts, quiet time-in-state metadata, and red treatment only for needs-you; workspace paths use Martian Mono.
- Board polls `/api/agents` every 3s; abrupt OkHttp closure made a long-lived Board feed unsafe. The planned terminal is the narrow exception: dedicated terminal/topology sockets run only while its full-screen route is visible and contain cancellation/errors.
- Chat: pane-native sessions (a tab in the folder's herdr workspace + pi --model,
  a new workspace only when the folder has none),
  JSONL transcript polling with a since cursor, dedupe by entryId,
  answer/steer unified composer.
- Push: contentless FCM pings (`kind` + `paneId` only, no notification block), blocked-only triggers; the app wakes, fetches identity from `/api/agents` over the tailnet, and posts one self-clearing notification per pane. See ADR 0007.
- QR pairing (Moshi-style Easy Pair).
- Usage: Codex and Claude quota windows, DeepSeek balance, and xAI weekly credits; rings on the usage screen.

## Constraints

Never expose the herdr socket raw; auth.json / models-store.json read-only;
**no Scoutr-owned cloud or backend and no required subscription**; no Play
Store; user is not interrupted (runs inside a goal); /simplify before every
commit; close every herdr pane created.

"No Scoutr-owned cloud" is about ownership, not about avoiding the network.
The user may point Scoutr at an exposure provider they already own —
Tailscale, a Cloudflare Tunnel, a reverse proxy — and that does not change
the self-hosted identity: there is still no Scoutr relay, Worker, hosted
backend, or account, no Scoutr-operated service in the data path, and no
subscription anyone must buy to use the product. Scoutr consumes a public URL
the user provisioned; it never provisions, manages, or holds credentials for
one.
