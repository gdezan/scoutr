# 1. Agent backend seam in the bridge

**Status: Shipped.** Two adapters are live (`pi`, `claude`); Android is backend-aware
end to end. This page is the design record — the line references below are the current
files, not a plan.

## Files

- `bridge/src/agents/types.ts` — the `AgentBackend` contract.
- `bridge/src/agents/registry.ts` — self-populating registry (registers `piBackend` and
  `claudeBackend` at module load).
- `bridge/src/agents/pi/` — pi adapter: `index.ts` (launch/resume/control/catalog
  wiring), `transcript.ts` (JSONL v3 parser + `writePiSessionTitle`), `models.ts`,
  `commands.ts`.
- `bridge/src/agents/claude/` — claude adapter: `index.ts`, `transcript.ts` (flat
  append-only event-log parser).
- `bridge/src/sessions.ts` — agent-agnostic orchestration (create/launch/control).
- `bridge/src/session-catalog.ts` — multi-backend catalog and path routing.
- `bridge/src/transcript.ts` — shared transcript machinery (plan 2).
- `bridge/src/shell.ts`, `bridge/src/herdr/panes.ts` — shared helpers extracted to
  break the import cycle (adapters never import `sessions.ts`).
- Routes: `agents.ts` (AgentCard + `/api/agents/kinds`), `sessions.ts`, `catalog.ts`,
  `models.ts`, `commands.ts` (agent query param), `board-detail.ts`.
- `bridge/src/commands.ts` — WS slash-command validation and `answer_question`
  dispatch through the pane's backend.
- Android: `data/Models.kt` (AgentCard `agentKind`/`capabilities`/`displayName`,
  `SessionReadResult.agentKind`, `AgentKindsResponse`), `net/BridgeClient.kt`
  (`models(agent)`, `commands(cwd, agent)`, `createSession(agent)`, `agentKinds()`),
  `state/NewSessionViewModel.kt` (backend selection), `state/ChatViewModel.kt`
  (capability gating, agent-aware model fetch), `ui/screens/NewSessionSheet.kt`
  (BackendSection), `ChatScreen.kt` (capability-gated action menu, Agent chip),
  `ConversationConfigSheet.kt`, `HistoryScreen.kt` (agentKind-gated rename/fork),
  `ui/components/SectionLabel.kt`.

## Problem (why the seam exists)

There is no agent module. `pi` was a property of the daemon, and adding Claude Code meant
editing every consumer file. The specific entanglements the seam removes:

| What | pi-specific because | Now |
|---|---|---|
| Launch command | literal `"pi"`, `--model`, `--thinking`, `--name` | `backend.launchCommand(params)` |
| Resume/fork launch | `pi --session` / `pi --fork <path>` | `backend.resumeCommand(path, mode)`; claude resumes by **session id** |
| Control verbs | `escape`, `/compact`, `/fork`, `/name`, `/model provider/id`, `shift+tab` cycling | `backend.control(herdr, params)`; abort/retry/compact/fork are pane-direct in `sessions.ts` |
| Question answering | types text + Enter into "pi's questionnaire UI" | `backend.answerQuestion(herdr, paneId, answer)` |
| Slash commands | assumes a `/`-prefixed pi grammar | `backend.commands(cwd)`; `hasSlashCommands` |
| Transcript format | pi JSONL v3, `toolResult` / `bashExecution` roles | `backend.readTranscript(path, opts)` |
| Question extraction | pi's `ask_user_question` tool contract | `backend.extractQuestions(transcript)` |
| Model catalog | reads pi's `models-store.json`; identity is `provider/id` | `backend.models()`; `hasModelCatalog` |
| Thinking levels | mirrors pi-ai's `getSupportedThinkingLevels` | `set_thinking` capability |
| Session root sandbox | *"session path must live under the pi agent directory"* | `backend.ownsSessionPath(path)` |
| Usage providers | `codex`, `deepseek`, `xai` from pi's `auth.json`; no `anthropic` | pi-only for now, reported only for providers that can be read (see Usage below) |
| npm dependency | imports `@earendil-works/pi-coding-agent` | only inside the pi adapter |

Two structural consequences that forced the wire change:

1. **A Claude transcript was rejected before it was parsed.** Both `resolveCatalogSessionPath`
   and `readSession` required containment under the pi agent directory, so a file under
   `~/.claude/projects/…` got a 403 no matter what the parser did.
2. **The wire had no discriminator.** `AgentCard` passed `agent: string` straight through
   from herdr, and `SessionReadResult` exposed pi entry types directly. The app could not
   tell which backend a card came from.

What is *already* generic and was not re-abstracted: `herdr/client.ts`, `herdr/feed.ts`,
`herdr/types.ts` (`AgentSessionInfo {source, agent, kind: "id" | "path"}` was already
polymorphic), `live-output.ts`, `review.ts`, `dirs.ts`, `attachments.ts`, and herdr's own
`agent_status` blocked/working detection.

## The contract

```ts
// bridge/src/agents/types.ts
export interface AgentBackend {
  readonly id: string;              // "pi" | "claude" — matches PaneInfo.agent
  readonly displayName: string;     // "Pi" | "Claude Code"
  readonly capabilities: ReadonlySet<ControlAction>;  // drives the app's menus
  readonly hasModelCatalog: boolean;
  readonly hasSlashCommands: boolean;

  launchCommand(params: LaunchParams): string;
  resumeCommand(path: string, mode: "resume" | "fork"): string;

  /** Root directory holding this agent's transcripts; the sandbox boundary. */
  sessionRoot(): string;
  ownsSessionPath(path: string): boolean;
  /** Resolve a herdr agent_session reference to a transcript path. */
  resolveSessionPath(ref: AgentSessionInfo): Promise<string | null>;

  readTranscript(path: string, opts?: TranscriptReadOpts): Promise<Transcript>;
  extractQuestions(transcript: Transcript): QuestionEntry[];
  answerQuestion(herdr: HerdrPort, paneId: string, answer: string): Promise<void>;
  control(herdr: HerdrPort, params: ControlParams): Promise<void>;
  /** Initial-prompt delivery into a fresh pane; defaults to `herdr.agentPrompt`. */
  deliverInitialPrompt?(herdr: HerdrPort, paneId: string, text: string): Promise<void>;

  models(): ModelsCatalog;
  commands(cwd?: string): Promise<CommandsCatalog>;
  /** Renaming a stored session; absent means the agent cannot be renamed. */
  renameStoredSession?(path: string, title: string): Promise<void>;
}
```

`ControlAction` is the union `abort | retry | compact | fork | rename | close |
set_model | set_thinking`; each adapter advertises only the subset its TUI speaks.

```ts
// bridge/src/agents/registry.ts
export function backendFor(agentId: string): AgentBackend;          // throws on unknown
export function backendForSessionPath(path: string): AgentBackend | null; // routes by ownsSessionPath
export function backendForAgentSessionInfo(info: AgentSessionInfo): AgentBackend | null;
export function knownBackends(): readonly AgentBackend[];
```

`HerdrPort` (plan 3) is what the adapters get — a keystroke pipe, not the whole client.

### Resolving a live agent_session reference

Herdr reports session references as `{kind, value}` where kind is exactly `"id"` or
`"path"` (herdr 0.8.0 schema). Pi panes report `kind: "path"`; a live Claude pane
launched with `claude --session-id <uuid>` reports `kind: "id"` (verified live). The pi
adapter accepts only `"path"`; the claude adapter accepts `"path"` directly and `"id"`
via a bounded recursive walk of the projects root (`MAX_SCAN_DEPTH 3`,
`MAX_SCAN_FILES 5000`) matching `<id>.jsonl` — this preserves the transcript for the
board, catalog joins, and chat reads while herdr only knows the id.

## Wire changes (breaking, both sides changed in one commit)

- `AgentCard` gains `agentKind` (registry id), `capabilities: string[]`, and
  `displayName`. The bridge always sends `capabilities` for known agents — an explicit
  empty array means *no* capabilities, and only a null/absent field means "assume pi
  defaults" (older bridge).
- `SessionReadResult` gains `agentKind`; `entries` are the shared `TranscriptEntry[]`.
- `GET /api/models` and `GET /api/commands` take an `agent` query parameter (default
  `pi`; unknown agent → 404). A catalog-less agent returns an empty catalog rather than
  the old "models-store.json not readable" 404.
- New `GET /api/agents/kinds`: `[{id, displayName, capabilities, hasModelCatalog,
  hasSlashCommands}]`. The new-session sheet hides its backend selector until there are
  ≥2 kinds and hides the model pick for catalog-less backends.
- Android propagates `agentKind`/`capabilities` through `ChatUiState`; `set_thinking`
  controls and the Thinking chip render only with the capability, the session action
  menu keeps only supported verbs, the config-sheet model search is hidden when the
  catalog is empty, and HistoryScreen gates Rename/Fork on `agentKind == "pi"`.

## Sandbox

`backendForSessionPath(path)` replaces the pi-directory containment check: a path is
legal when *some* registered backend claims it (`ownsSessionPath` — both adapters
realpath-canonicalize root and target so symlinked roots work), and the claiming backend
does the read. Catalog scanning is fair: the global 2000-candidate cap is split evenly
across roots (`MAX_CANDIDATES / roots.length`), so a giant pi store cannot starve the
claude root.

## Control dispatch

`controlSession` in `sessions.ts` runs the four pane-direct verbs (`abort`, `retry`,
`compact`, `fork`) itself — they are identical across backends and need no pane identity,
so a transient snapshot failure can never block an emergency Escape. The remaining verbs
(`rename`, `close`, `set_model`, `set_thinking`) resolve the pane's backend
(`backendForAgentSessionInfo` on `pane.agent_session`, else `pane.agent`, else the
snapshot agents list) and delegate. Backends throw `BridgeError` with deliberate statuses
(404 vanished pane, 409 missing session path); other errors wrap as 400.

## The Claude adapter

- `launchCommand`: `claude [--model <id>] [--name <n>]` — no `--thinking`;
  `set_thinking` is absent from capabilities so the app hides the control instead of
  sending a verb that silently does nothing.
- `resumeCommand`: `claude --resume <session-id>` — the id is the transcript path's
  basename. Fork-at-path throws with guidance (resume and use `/fork`).
- `sessionRoot`: `~/.claude/projects` (honors `CLAUDECONFIGDIR`).
- `readTranscript`: Claude's JSONL is a flat append-only event log with no session
  header — per-record `sessionId`/`cwd`/`timestamp`, `uuid`/`parentUuid` identity,
  string user content vs `tool_result` arrays, assistant `content` blocks
  (text/thinking/tool_use) with `message.model`/`stop_reason`/`usage`. Non-conversation
  records (`mode`, `permission-mode`, `queue-operation`, `system`, `file-history-snapshot`)
  are skipped; unparseable lines are tolerated; titles come from `aiTitle` on user
  records or `custom-title` records. The on-disk format is internal to Claude and
  version-variable — the parser is defensive by design.
- `extractQuestions`: `[]` — Claude has no `ask_user_question` equivalent; herdr's
  `blocked` status drives "needs you".
- `control`: `abort` (escape), `compact` (`/compact`), `close` (workspace close),
  `set_model` (`/model <id>`, no `provider/` prefix, length-capped at 200).
- Stored sessions cannot be renamed (the title lives in a pi session file) —
  `renameStoredSession` is absent and the catalog route rejects with
  "Claude Code sessions cannot be renamed"; the app hides the action.

## Benefits

**Locality.** Adding a third agent is one new directory under `bridge/src/agents/` plus
one registry registration. Previously it was a dozen files and a wire-format decision.

**Depth.** `AgentBackend` hides the launch grammar, transcript format, TUI keybindings,
catalog sources, and filesystem layout of an entire coding agent behind ~15 methods.

**Leverage.** The discriminator unblocked per-agent controls in the app (action menu,
thinking gating, backend selector, HistoryScreen actions) with no further bridge work.

**Tests.** The interface is the test surface: `bridge/test/agents-claude.test.ts` is a
focused conformance suite (launch/resume quoting, containment + symlink canonicalization,
id-kind resolution, control verbs, capability advertisement, transcript parsing against
real record shapes), and `session-catalog.test.ts` covers the fair per-root budget. The
acceptance gates are `npm run typecheck && npm test` in `bridge/`.

## Usage (documented decision)

`usage/providers.ts` remains pi-only: it reads pi's `auth.json` and can only enumerate
codex/deepseek/xai. Anthropic credentials live in Claude's own credential store, and
there is no anthropic provider yet — `GET /api/usage` reports only the providers it can
actually read rather than failing. Adding an `anthropic` provider is a follow-up, not a
silent gap.

## Risks (status)

- **`agent_session.kind === "id"`** — resolved: verified live against a Claude pane
  (herdr 0.8.0, Claude Code 2.1.227, `claude --session-id <uuid>` → `kind: "id"`), and
  `resolveSessionPath` handles both kinds.
- **The pi npm dependency** is imported only by the pi adapter.
- **Scope creep into herdr** — avoided: `waitForAgent` and herdr's detection are reused
  as-is.
