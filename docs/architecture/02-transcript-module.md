# 2. One transcript module, three parsers deleted

**Strength: Strong.** Do this first — it is the cheapest plan with the best existing test
coverage, and plan 1 depends on it.

## Files

`bridge/src/pi/session.ts` (229 lines), `bridge/src/session-catalog.ts:227-261`,
`bridge/src/board-detail.ts:78-121`, `bridge/src/questions.ts`, `bridge/src/server.ts:718-775`.

## Problem

The same pi JSONL format is parsed by three independent implementations, each knowing a
different, overlapping subset of the record vocabulary:

| Parser | Records it knows | Purpose |
|---|---|---|
| `pi/session.ts:82-192` | `session`, `message`, `model_change`, `thinking_level_change`; blocks `text`/`thinking`/`toolCall` | full transcript for chat |
| `session-catalog.ts:227-261` | `session`, `session_info`, `model_change`, `message` | list row metadata |
| `board-detail.ts:78-121` | `model_change`, `message`, **plus** `tool_use` / `tool_result` records and a `tool_use` block | bounded tail for the board card |

The third knows record types the first two do not. Either `tool_use` is real and
`pi/session.ts` silently drops it from the chat transcript, or it is dead code — and
nothing in the codebase can tell you which. That ambiguity is the cost of the duplication.

`session-catalog.ts:91-105` also *writes* a `session_info` record, so format knowledge
flows in both directions from a file that is nominally a catalog.

**Deletion test.** Deleting the catalog and board-detail parsers and routing both through
one transcript module concentrates all format knowledge in one place — the format is
learned once, extended once, and tested once. Complexity concentrates. Passes.

## Solution

One module, `bridge/src/transcript.ts`, owning the record vocabulary and exposing a
format-neutral `Transcript` shape that plan 1's adapters produce.

```ts
// bridge/src/transcript.ts
export interface TranscriptEntry {
  entryId: string;
  parentId: string | null;
  timestamp: string;
  role: "user" | "assistant" | "toolResult" | "system" | "bashExecution" | string;
  content: ContentBlock[];
  toolCallId?: string;
  toolName?: string;
  isError?: boolean;
  details?: unknown;      // structured tool-result payload (question answers etc.)
  stopReason?: string;
  model?: string;
  usage?: TokenUsage;
}

export interface Transcript {
  id: string;
  cwd: string;
  timestamp: string;
  entries: TranscriptEntry[];
  model: string | null;        // provider-qualified where the agent has providers
  thinkingLevel: string | null;
  lastEntryId: string | null;
  title: string | null;        // from session_info, where present
}

export interface ReadOpts {
  /** Read only the last N entries — replaces board-detail's private tail parser. */
  tail?: number;
  /** Skip content blocks; the catalog only needs metadata. */
  metadataOnly?: boolean;
}
```

Three read modes, one parser:

- `readTranscript(path)` — what chat uses today.
- `readTranscript(path, { metadataOnly: true })` — what the catalog list uses. It reads
  the same records and discards content, so a new record type cannot be understood by one
  caller and not another.
- `readTranscript(path, { tail: 40 })` — what the board card uses, keeping the bounded-read
  property that made `board-detail.ts` a separate parser in the first place.

The `tail` mode must stay genuinely bounded (the current implementation reads a bounded
byte window from the end of the file, not the whole file) — that is the one real reason
the third parser exists, and the option preserves it.

Writing moves out of the catalog too: `writeSessionTitle(path, title)` lives in the
transcript module beside the parser that reads it back.

`readSession` (`server.ts:718-775`) then shrinks to cursor resolution plus a call, and
`questions.ts` consumes `Transcript` instead of `PiSession`.

## Benefits

**Locality.** A new record type is understood by chat, the catalog, and the board in one
edit. Today it is three edits, and forgetting one produces a silent divergence rather than
an error.

**Depth.** `readTranscript` + `writeSessionTitle` is a two-function interface hiding the
whole JSONL vocabulary, the bounded-tail read, the cursor semantics, and garbage-line
tolerance.

**Leverage.** `Transcript` is precisely the return type plan 1's `AgentBackend` needs.
Without it, every adapter would have to emit `PiSession` — a pi-shaped type — which is how
a "generic" seam quietly stays pi-specific.

**Tests.** Three test files (`pi-session.test.ts`, `session-catalog.test.ts`,
`board-detail.test.ts`) currently pin three parsers against three fixture sets. They
collapse into one suite over one fixture corpus, and the `tail`/`metadataOnly` modes become
assertions that all three modes agree on the same file — a property no current test can
express.

## Steps

1. Create `bridge/src/transcript.ts` by generalising `pi/session.ts`: rename `Pi*` types to
   `Transcript*`, add `title`, add `ReadOpts`.
2. Fold `board-detail.ts`'s extra record knowledge (`tool_use`, `tool_result`) into the
   single parser, and resolve the ambiguity: either they are real records that chat has
   been dropping (fix it, add a fixture) or they are dead (delete them, note it in the
   commit). Check a live `~/.pi/agent/sessions/*.jsonl` to decide.
3. Implement `tail` as a bounded reverse read; port `board-detail.ts` to it and delete its
   private parser.
4. Implement `metadataOnly`; port `session-catalog.ts:227-261` to it and delete its parser.
5. Move `renameStoredSession`'s record write to `writeSessionTitle`.
6. Point `questions.ts`, `sessions.ts`, and `server.ts:718-775` at the new types.
7. Merge the three test files into `bridge/test/transcript.test.ts`, keeping every existing
   assertion, and add cross-mode agreement tests.
8. Delete `bridge/src/pi/session.ts`.

## Risks

- **The `tail` fixture gap.** `board-detail.test.ts` uses small fixtures where a bounded
  read and a full read are the same thing. Add a fixture larger than the byte window before
  porting, or the bound will be silently lost.
- **Renaming `PiMessageEntry` reaches the wire.** `SessionReadResult.entries` is
  `PiMessageEntry[]`, mirrored by `SessionEntry` in `android/…/data/Models.kt:155`. Field
  names are unchanged by this plan, so the JSON is identical — but verify with a live
  transcript read before committing, and land the Android rename in the same commit.
