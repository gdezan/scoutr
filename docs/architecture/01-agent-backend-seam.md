# 1. Agent backend seam in the bridge

**Strength: Strong.** This is the plan the review was commissioned for. Do it *after*
plans 2 and 3.

## Files

`bridge/src/sessions.ts`, `bridge/src/pi/{session,models,commands}.ts`,
`bridge/src/questions.ts`, `bridge/src/session-catalog.ts`, `bridge/src/board-detail.ts`,
`bridge/src/server.ts`, `bridge/src/usage/{auth,providers}.ts`.

## Problem

There is no agent module. `pi` is a property of the daemon, and adding Claude Code means
editing every one of the files above. The specific entanglements:

| What | Where | pi-specific because |
|---|---|---|
| Launch command | `sessions.ts:126-136` | literal `"pi"`, `--model`, `--thinking`, `--name` |
| Resume/fork launch | `sessions.ts:178` | `pi --session` / `pi --fork <path>` |
| Control verbs | `sessions.ts:267-330` | `escape`, `/compact`, `/fork`, `/name`, `/model provider/id`, `shift+tab` cycling |
| Question answering | `server.ts:589-598` | types text + Enter into "pi's questionnaire UI" |
| Slash commands | `pi/commands.ts:120-127` | assumes a `/`-prefixed pi grammar |
| Transcript format | `pi/session.ts` (whole file) | pi JSONL v3, `toolResult` / `bashExecution` roles |
| Question extraction | `questions.ts:34,101-147` | pi's `ask_user_question` tool contract |
| Model catalog | `pi/models.ts:25-81` | reads pi's `models-store.json`; identity is `provider/id` |
| Thinking levels | `sessions.ts:9-11`, `pi/models.ts:30-44` | mirrors pi-ai's `getSupportedThinkingLevels` |
| Session root sandbox | `server.ts:735-740`, `session-catalog.ts:71-89` | *"session path must live under the pi agent directory"* |
| Usage providers | `usage/providers.ts:263-267` | `codex`, `deepseek`, `xai` from pi's `auth.json`; no `anthropic` |
| npm dependency | `pi/commands.ts:3-9` | imports `@earendil-works/pi-coding-agent@0.84.1` |

Two structural consequences:

1. **A Claude transcript is rejected before it is parsed.** `resolveCatalogSessionPath`
   (`session-catalog.ts:78-89`) and `readSession` (`server.ts:735-740`) both require
   containment under the pi agent directory. A file under `~/.claude/projects/…` gets a
   403, so no amount of parser work would surface it.
2. **The wire has no discriminator.** `AgentCard` (`server.ts:642-660`) passes
   `agent: string` straight through from herdr, and `SessionReadResult`
   (`server.ts:718-731`) exposes `PiMessageEntry[]` directly. The app cannot tell which
   backend a card came from, so it cannot vary behaviour per backend even if the bridge
   could.

Note what is *already* generic and must not be re-abstracted: `herdr/client.ts`,
`herdr/feed.ts`, `herdr/types.ts` (`AgentSessionInfo {source, agent, kind}` is already
polymorphic), `live-output.ts`, `review.ts`, `dirs.ts`, `attachments.ts`, and herdr's own
`agent_status` blocked/working detection — which is what powers "needs you" and works for
any agent herdr recognises.

## Solution

Introduce one deep module, `bridge/src/agents/`, whose interface is a single `AgentBackend`
and whose registry resolves a backend from a herdr `AgentSessionInfo`. Everything pi knows
about itself moves behind the `pi` adapter; a `claude` adapter is added beside it. This is
a real seam, not a hypothetical one — two adapters exist on day one.

### Interface

```ts
// bridge/src/agents/types.ts
export interface AgentBackend {
  /** herdr's agent identifier — matches PaneInfo.agent / AgentSessionInfo.agent. */
  readonly id: string;              // "pi" | "claude"
  readonly displayName: string;

  /** Which control verbs this agent supports; drives the app's menu (plan 5). */
  readonly capabilities: ReadonlySet<ControlAction>;

  /** Shell command that starts a fresh session in a pane. */
  launchCommand(params: LaunchParams): string;
  /** Shell command that opens a stored transcript. */
  resumeCommand(path: string, mode: "resume" | "fork"): string;

  /** Root directory holding this agent's transcripts; the sandbox boundary. */
  sessionRoot(): string;
  /** Does this path belong to this backend? Used to route a transcript read. */
  ownsSessionPath(path: string): boolean;

  /** Parse a transcript into the shared Transcript shape (see plan 2). */
  readTranscript(path: string, opts?: ReadOpts): Promise<Transcript>;

  /** Structured questions pending in a transcript, if the agent has such a concept. */
  extractQuestions(transcript: Transcript): QuestionEntry[];
  /** How an answer is delivered back into the pane. */
  answerQuestion(herdr: HerdrPort, paneId: string, answer: string): Promise<void>;

  /** One control action, expressed in this agent's own TUI vocabulary. */
  control(herdr: HerdrPort, params: ControlParams, deps: ControlDeps): Promise<void>;

  /** Model catalog and slash commands, or empty when the agent has none. */
  models(): ModelsCatalog;
  commands(cwd?: string): Promise<CommandsCatalog>;
}
```

```ts
// bridge/src/agents/registry.ts
export function backendFor(agentId: string): AgentBackend;          // throws on unknown
export function backendForSessionPath(path: string): AgentBackend;  // routes by ownsSessionPath
export function knownBackends(): readonly AgentBackend[];
```

`HerdrPort` is the narrow interface introduced by plan 3 — the adapters get a keystroke
pipe, not the whole client.

### Wire changes

Add an `agentKind` discriminator so the app can reason about backends. This is a breaking
wire change; per the project's engineering principles, change both sides and delete the
old shape rather than adding a fallback.

- `AgentCard` gains `agentKind: string` (the registry id) and `capabilities: string[]`.
- `SessionReadResult` gains `agentKind`, and its `entries` become the shared
  `TranscriptEntry[]` from plan 2 rather than `PiMessageEntry[]`.
- `GET /api/models` and `GET /api/commands` take an `agent` query parameter, defaulting to
  the backend that owns the referenced pane. A catalog-less agent returns an empty
  catalog rather than a 404 — `readModelsCatalog`'s current
  `"models-store.json not readable"` 404 must not become Claude's default response.
- New `GET /api/agents/kinds` returning the registry: `[{id, displayName, capabilities,
  hasModelCatalog, hasSlashCommands}]`. The new-session sheet needs this to offer a
  backend choice.

### Sandbox

Replace the single "must live under the pi agent directory" check with
`backendForSessionPath(path)` — a path is legal when *some* registered backend claims it,
and the claiming backend then does the read. This keeps the least-privilege property while
making the allow-list extensible. `sessionRoot()` per backend replaces the
`PI_CODING_AGENT_SESSION_DIR` special case; each adapter reads its own env vars.

### The Claude adapter

- `launchCommand`: `claude --model <id>` (no `--thinking`; `capabilities` omits
  `set_thinking`, so the app hides the control rather than sending a verb that silently
  does nothing).
- `resumeCommand`: `claude --resume <session-id>`. Note this takes an **id**, not a path —
  which is exactly why `resumeCommand` takes the transcript path and lets the adapter
  derive whatever the CLI wants.
- `sessionRoot`: `~/.claude/projects`.
- `readTranscript`: Claude's JSONL is a different record set; the adapter maps it onto the
  plan-2 `Transcript` shape.
- `extractQuestions`: returns `[]` initially — Claude has no `ask_user_question`
  equivalent, and herdr's `blocked` status already drives "needs you" for it.
- `control`: `abort` via `escape`, `close` via workspace close (both agent-agnostic);
  `/compact` exists in Claude too; `set_model` uses `/model <id>` without the
  `provider/` prefix. Verbs it lacks are simply absent from `capabilities`.

## Benefits

**Locality.** Adding a third agent becomes one new file under `bridge/src/agents/` plus
one registry line. Today it is a dozen files and a wire-format decision.

**Depth.** `AgentBackend` is a ~12-method interface hiding the launch grammar, the
transcript format, the TUI keybindings, the catalog sources, and the filesystem layout of
an entire coding agent. That ratio is the definition of a deep module.

**Leverage.** The discriminator unblocks plan 5 (per-agent controls) and gives the app a
place to hang backend-specific UI without any further bridge work.

**Tests.** The interface *is* the test surface: one shared conformance suite runs against
every registered adapter (`launchCommand` shell-quotes; `ownsSessionPath` rejects
traversal; `readTranscript` tolerates garbage lines; `control` rejects unsupported verbs).
Today `sessions.test.ts:101` asserts the literal string
`"pi --model 'openai-codex/gpt-5.4' --thinking 'high' --name 'demo'"` — that assertion
becomes one adapter's row in a table-driven suite.

## Steps

1. Land plan 2 (`Transcript`) and plan 3 (`HerdrPort`, route table).
2. Create `bridge/src/agents/types.ts` with `AgentBackend` and `registry.ts`.
3. Move the pi specifics into `bridge/src/agents/pi/` — `sessions.ts`'s launch/control
   bodies, `pi/session.ts`, `pi/models.ts`, `pi/commands.ts`, `questions.ts`. `sessions.ts`
   keeps validation, `resolveAllowedDir`, `launchWorkspace`, and `waitForAgent`, all of
   which are agent-agnostic, and delegates the rest.
4. Route every sandbox check through `backendForSessionPath`.
5. Add `agentKind` + `capabilities` to the wire DTOs; update `data/Models.kt` and the app
   in the same commit. Delete the old shapes.
6. Write the conformance suite; re-point `sessions.test.ts`, `questions.test.ts`,
   `models.test.ts`, `commands.test.ts` at the pi adapter.
7. Add `bridge/src/agents/claude/` and register it. Verify end to end against a real
   `claude` pane in herdr.
8. Usage: add an `anthropic` provider to `usage/providers.ts` reading Claude's own
   credential store, or leave it out and have `GET /api/usage` report only providers it can
   read. Decide explicitly; do not let it fail silently.

## Risks

- **`agent_session.kind === "id"`.** `server.ts:681`, `server.ts:708-714`, and
  `findPaneSessionPath` (`sessions.ts:364-374`) all assume herdr reports a *path*. If
  herdr's Claude integration reports `kind: "id"`, there is no transcript, no board
  detail, and no catalog join, and `AgentBackend` cannot fix that from above. **Verify
  this against a live Claude pane before starting step 7** — it may force a
  `resolveSessionPath(kind, value)` method on the interface.
- **The pi npm dependency** (`@earendil-works/pi-coding-agent`) must end up imported only
  by the pi adapter, or the daemon keeps a hard dependency on one agent's package.
- **Scope creep into herdr.** herdr already abstracts agents; the temptation is to
  reimplement its detection. Don't — `waitForAgent` (`sessions.ts:239-252`) already works
  for any agent herdr recognises.
