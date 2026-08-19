# Chat Context Usage in the Header Blueprint

## Current situation

The chat header's mono meta line shows only `paneId · model`:

```kotlin
// ui/screens/ChatScreen.kt:509
Text(
    listOfNotNull(paneId, model?.substringAfterLast('/')).joinToString(" · "),
    style = ScoutrType.monoMeta,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)
```

Everything the readout needs already crosses the bridge→app boundary; **no bridge
change is required**:

- Per-entry token usage is parsed by every agent adapter and shipped on the
  transcript entry — `bridge/src/agents/claude/transcript.ts:174` (`input_tokens`,
  `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`) and
  `bridge/src/agents/pi/transcript.ts:130` (already-normalized keys). It is
  decoded on the app as `EntryUsage` (`data/Models.kt:245`) hanging off
  `SessionEntry.usage` (`data/Models.kt:211`).
- The context window per model is authored in the bridge catalogs
  (`bridge/src/agents/claude/models.ts:26` = `200_000`,
  `bridge/src/agents/agy/models.ts:11` = `1_048_576`,
  `bridge/src/agents/pi/models.ts:67` passthrough), typed at
  `bridge/src/agents/types.ts:65`, and decoded as `ModelInfo.contextWindow`
  (`data/Models.kt:392`).
- `ChatUiState.activeModel` (`state/ChatViewModel.kt:362`) already resolves the
  session's `ModelInfo` from the catalog, and `refreshConfiguration()` is called
  on every chat poll cycle (`state/ChatViewModel.kt:719`), not only when the
  configuration sheet opens. So `contextWindow` is available a poll or two after
  the chat opens without any new fetch.

Precedent followed: derived read-only properties on `ChatUiState`
(`lastUserMessage`, `activeModel`, `hasPendingQuestion`,
`state/ChatViewModel.kt:358-398`) and per-segment coloring inside one `Text` via
`buildAnnotatedString` + `SpanStyle` (`HeaderConfigurationChip`,
`ui/screens/ChatScreen.kt:651`).

Design contract: `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`.
§7a keeps the chip rail for *configuration* facts (agent, provider, model,
thinking) and puts live machine facts on the mono meta line; the status color
language assigns amber to "a warning or threshold" and red to "the operator is
needed or something failed".

## Objective and why

Show how full the conversation's context window is, on the chat header, so the
operator can see at a glance that a session is approaching its limit and decide
to `/compact` before the agent does it for them.

**Done when:** opening a chat with at least one assistant turn shows a mono meta
line reading `pane-3 · claude-opus-5 · 124k/200k`, the fraction advances as the
session grows, and the readout turns amber at ≥80% and red at ≥95%.

## Scope

**In:** a pure derivation of context usage from the transcript already in
`ChatUiState`, its rendering on the chat header's mono meta line, and JVM unit
coverage for the derivation.

**Non-goals:** any bridge change; correcting the authored `contextWindow` values;
modelling Claude Code's autocompact reserve; a ring, gauge, or chip; a context
readout on board cards, the history screen, or the terminal; notifications or
auto-compact behavior.

## Resolved decisions

- **Placement: the existing mono meta line under the chat title**, appended after
  `paneId · model`. The chip rail is configuration-only per DESIGN.md §7a and
  context usage is a live value; the meta line already exists, so this adds no
  new geometry. (Rejected: a fourth "Context" chip in the rail — puts a
  live-changing value in a rail defined as configuration; rejected: a status ring
  — most new UI and instrumentation surface for the least additional information.)
- **Threshold coloring: gray below 80%, `tertiary` amber (#E8B84B) at ≥80%,
  `error` red at ≥95%.** DESIGN.md already assigns amber to threshold states and
  the Usage screen uses amber/red the same way. Coloring applies only to the
  context segment of the line, never to `paneId` or the model.
- **Denominator: `ModelInfo.contextWindow` exactly as the catalog reports it**,
  with no autocompact subtraction. Claude models therefore read `/200k` today;
  if that number is ever wrong it is a one-line edit in
  `bridge/src/agents/claude/models.ts` and the app follows automatically.
- **Used tokens = `input + cacheRead + cacheWrite`** of the most recent assistant
  entry that carries any of them. This is what occupies the context window; the
  turn's own `output` is excluded because it has not yet been fed back as input.
  `totalTokens` is deliberately not used as a fallback — for pi it includes
  output and would overstate the fraction.
- *(Assumption, not asked)* **When `contextWindow` is unknown** — a pi provider
  that reports none, or the catalog not yet loaded — render the used half alone
  (`124k`) in gray, with no threshold coloring. Hiding the segment until the
  catalog lands would make the line visibly reflow a second after the chat opens.
- *(Assumption, not asked)* **When no assistant entry carries usage** — a fresh
  session, or an agent whose adapter reports none — render nothing extra; the
  line stays `pane-3 · claude-opus-5`.

## Approach

`ChatUiState` already holds both inputs (`entries`, `activeModel`). A pure
top-level function reduces them to a small value type; a derived property exposes
it; `ChatScreen` passes it into `ChatHeader`, which appends one colored segment to
the meta line it already renders.

Flow: bridge transcript poll → `ChatUiState.entries` → `contextUsageOf(entries,
activeModel?.contextWindow)` → `ContextUsage` → `ChatHeader` meta line.

No new network call, no new poll, no new state to invalidate: the value is
recomputed from state that already refreshes on the existing 2.5s chat cycle.

## Contracts and interfaces

```kotlin
// state/ContextUsage.kt (new)

/** How full a session's context window is, derived from its last assistant turn. */
data class ContextUsage(
    /** Tokens occupying the window: input + cacheRead + cacheWrite of the last assistant turn. */
    val usedTokens: Long,
    /** The model's window, or null when the catalog reports none (or has not loaded). */
    val windowTokens: Long?,
) {
    /** 0f..1f, or null without a window. Values above 1f are clamped. */
    val fraction: Float?

    /** `124k/200k`, or `124k` without a window. */
    val label: String

    /** Quiet, Warning (>=80%), or Critical (>=95%); always Quiet without a window. */
    val tone: ContextTone
}

enum class ContextTone { Quiet, Warning, Critical }

/** Null when no assistant entry reports usage. */
fun contextUsageOf(entries: List<SessionEntry>, windowTokens: Long?): ContextUsage?
```

Invariants:

- `contextUsageOf` scans `entries` in reverse and takes the **first** entry with
  `role == "assistant"` whose `usage` has a non-null `input`, `cacheRead`, or
  `cacheWrite`; missing components count as 0. Entries of any other role are
  ignored even if they carry usage.
- A usage object present but with all three components null does not qualify —
  keep scanning.
- `usedTokens` is never negative; `fraction` is `usedTokens / windowTokens`
  coerced into `0f..1f`; a `windowTokens` of 0 or less is treated as unknown.
- Token formatting: `< 1_000` → the exact number (`842`); `< 1_000_000` → rounded
  thousands (`124k`, half rounds up); otherwise millions with one decimal and a
  trailing `.0` trimmed (`1M`, `1.2M`).

## Changes

- [ ] **1 — Add the context-usage derivation**

  New file `android/app/src/main/java/dev/scoutr/app/state/ContextUsage.kt`
  holding `ContextUsage`, `ContextTone`, `contextUsageOf`, and the token
  formatter, exactly as specified in *Contracts and interfaces*. Pure Kotlin: no
  Compose, no Android framework types, so it tests on the JVM without Robolectric.

  Add the derived property beside the existing ones on `ChatUiState`:

  ```kotlin
  val contextUsage: ContextUsage?
      get() = contextUsageOf(entries, activeModel?.contextWindow)
  ```

  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/ContextUsage.kt` (new)
    and `android/app/src/main/java/dev/scoutr/app/state/ChatViewModel.kt` →
    `ChatUiState.activeModel` (insert the new property after it, `:362`)
  - Proof: new `android/app/src/test/java/dev/scoutr/app/state/ContextUsageTest.kt`
    passes — `cd android && ./gradlew testDebugUnitTest --tests "dev.scoutr.app.state.ContextUsageTest"`

- [ ] **2 — Render it on the chat header's mono meta line**

  Add a `contextUsage: ContextUsage?` parameter to `ChatHeader`, pass
  `ui.contextUsage` at the call site, and replace the meta-line `Text` with a
  `buildAnnotatedString` that appends `" · "` plus `contextUsage.label` when
  non-null, wrapping only that segment in a `SpanStyle` whose color is
  `onSurfaceVariant` / `tertiary` / `error` for `Quiet` / `Warning` / `Critical`.
  Keep `ScoutrType.monoMeta`, `maxLines = 1`, and `TextOverflow.Ellipsis`
  unchanged, and tag the line `Modifier.testTag("chat_header_meta")` so a later
  instrumentation assertion has a handle.

  - Anchor: `android/app/src/main/java/dev/scoutr/app/ui/screens/ChatScreen.kt` →
    `ChatHeader` (`:471`, meta line at `:509`) and its call site (`:211`)
  - Proof: `cd android && ./gradlew assembleDebug` compiles, and the final
    emulator acceptance pass shows `pane · model · <used>/<window>` on an open chat

## Failure handling

- **No assistant usage yet** (fresh session, adapter reports none):
  `contextUsageOf` returns null and the meta line renders exactly as it does
  today. Covered by change 1's test.
- **Catalog not loaded / no `contextWindow`**: `windowTokens` is null, the label
  is the used half alone, `tone` is `Quiet`. The line gains its `/200k` half a
  poll later when `refreshConfiguration()` lands; that reflow is intended.
- **Model not in the catalog** (a session pinned to a model the catalog no longer
  lists): `activeModel` is already null in that case today — same path as above.
- **A window smaller than the used tokens** (a model swapped mid-session):
  `fraction` clamps to 1f, so the readout reads e.g. `260k/200k` in red rather
  than drawing an impossible value.
- **Very long pane ids or model names**: the line is still one `maxLines = 1`
  ellipsized `Text`, so the context segment can be truncated away rather than
  wrapping or pushing the header taller. Accepted.
- No new I/O, coroutine, or cancellation surface is introduced.

## Validation

New JVM test `android/app/src/test/java/dev/scoutr/app/state/ContextUsageTest.kt`
(plain JUnit, no `RobolectricTestRunner` — the code under test is pure Kotlin;
build `SessionEntry` values directly as `ChatMergeTest` does), covering:

1. last assistant entry wins over an earlier one with larger usage;
2. `input + cacheRead + cacheWrite` are summed and `output` is excluded;
3. a null component counts as 0;
4. non-assistant entries with usage are ignored;
5. an assistant entry whose usage has all three components null is skipped in
   favour of an earlier qualifying one;
6. no qualifying entry → null;
7. label/fraction/tone with a window: `124_000` of `200_000` → `"124k/200k"`,
   fraction `0.62f`, `Quiet`;
8. thresholds: `160_000/200_000` → `Warning`; `190_000/200_000` → `Critical`;
   `159_999` stays `Quiet`;
9. null or non-positive window → label `"124k"`, fraction null, tone `Quiet`;
10. used above the window clamps `fraction` to `1f` and stays `Critical`;
11. formatter boundaries: `842` → `"842"`, `1_500` → `"2k"`, `1_048_576` → `"1M"`,
    `1_200_000` → `"1.2M"`.

Commands:

- `cd android && ./gradlew testDebugUnitTest --tests "dev.scoutr.app.state.ContextUsageTest"` — the targeted cheap check.
- `make android-test` — full JVM suite before review, to catch any `ChatUiState` fallout.
- `cd android && ./gradlew assembleDebug` — proves change 2 compiles.

Final runtime acceptance (once, at the end, per the AGENTS.md verification
boundary — not in the inner loop): install on `emulator-5554`, open a Claude
session with real history, and confirm the meta line reads
`<pane> · <model> · <used>/200k` and that the number moves after another turn.
Use `skills/scoutr-verification/SKILL.md` to select and run it, and
`skills/scoutr-vision/SKILL.md` if visual evidence is needed.

## Local discretion

- File and symbol naming inside `ContextUsage.kt`, and whether `label`/`tone` are
  computed properties or functions.
- Exact test method names and how `SessionEntry` fixtures are constructed.
- Whether the tone→color mapping lives as a `when` inside `ChatHeader` or a small
  private helper next to it.
- Whether the threshold constants are named `private const val` or inlined.
- Formatting/import ordering to match the surrounding file.

## Escalation triggers

- The last assistant entry turns out **not** to carry usage for a real Claude
  session on the emulator (i.e. the readout never appears) — that means the
  transcript path drops `usage` somewhere between the adapter and
  `ChatUiState.entries`, which is a bridge or merge investigation, not a UI fix.
- `contextUsage` needs anything beyond `entries` and `activeModel` — a new fetch,
  a new bridge field, or ViewModel-held state — which changes the scope from
  "derive from what we have" to a protocol change.
- The meta line cannot fit the readout without truncating the model name on a
  normal-width phone, making a different placement necessary.
- Any change to `ChatUiState` that forces edits across unrelated ViewModels or
  tests.

## Review handoff

An independent reviewer should:

- Re-run `make android-test` and confirm `ContextUsageTest` passes.
- Confirm no file under `bridge/` changed.
- Confirm the used-token formula excludes `output` and that `totalTokens` is not
  used as a fallback.
- Confirm the amber/red spans cover only the context segment, and that no other
  header element gained color.
- Confirm the meta line still has `maxLines = 1` and ellipsizes.
- Confirm the DESIGN.md color roles used are `tertiary` and `error` from the
  theme, with no hardcoded hex.
- Run `skills/scoutr-review/SKILL.md` and resolve or consciously dismiss every
  concrete finding before committing.

## Addendum — found during implementation

`ChatUiState.activeModel` (pre-existing, `ChatViewModel.kt:362`) matched only
the full picker key (`anthropic/claude-sonnet-5`), but the bridge relays a real
Claude Code session's model as the bare CLI id (`claude-sonnet-5`, confirmed
live via `/api/agents`) — pi sessions already store the full key in their own
transcripts, so only pi matched. `activeModel` was therefore always null for
Claude Code sessions, which meant `contextWindow` (this feature's denominator)
and `availableThinkingLevels`/`canSetThinking` were silently broken for the
most common backend, predating this change. Escalated to the user, who chose
to fix it now: `activeModel` falls back to a bare-id match within the
session's own (already backend-scoped) catalog when the picker-key match
misses. No bridge change. Verified live: the header now reads
`w7X:pJ · claude-sonnet-5 · 251k/200k` in red on a real over-window session.

Separately, mid-implementation the user asked to declutter the header so the
full meta line (now longer with the context segment) reads without
truncating: the standalone thinking/tool-details/files icon buttons moved into
the overflow menu, and every menu item — including the pre-existing session
actions — gained a leading icon. `ChatControlsTest.kt` was updated to open the
menu before clicking the relocated toggles. The wider `androidTest` module has
a pre-existing, unrelated compile break (`ConnectionStore.save` called with 4
args in ~9 places across other test files) that blocks running the
instrumentation suite; left alone as out of scope and flagged to the user.

## Completion checklist

- [x] `ContextUsage.kt` exists with `ContextUsage`, `ContextTone`, and `contextUsageOf`.
- [x] `ChatUiState.contextUsage` derived property added.
- [x] `ChatHeader` renders the segment with the three tones; call site passes `ui.contextUsage`.
- [x] `ContextUsageTest` covers all eleven cases and passes.
- [x] `make android-test` green; `assembleDebug` green.
- [x] `/simplify` pass run before commit (PRODUCT.md constraint).
- [x] Final emulator acceptance shows the live readout.
- [x] Review: the delegated `code-review` background agent stalled without
      output; resolved with a manual independent review against this file's
      review-handoff checklist instead. Committed on `main` with
      `git push --follow-tags`.

## References

- `android/app/src/main/java/dev/scoutr/app/ui/screens/ChatScreen.kt` — `ChatHeader` (`:471`), meta line (`:509`), call site (`:211`), `buildAnnotatedString` precedent (`:651`)
- `android/app/src/main/java/dev/scoutr/app/state/ChatViewModel.kt` — `ChatUiState.entries` (`:283`), `activeModel` (`:362`), `refreshConfiguration()` (`:521`, called `:719`)
- `android/app/src/main/java/dev/scoutr/app/data/Models.kt` — `SessionEntry.usage` (`:211`), `EntryUsage` (`:245`), `ModelInfo.contextWindow` (`:392`)
- `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md` — status color language, §7a chat header
- `android/app/src/main/java/dev/scoutr/app/ui/theme/Theme.kt` — `tertiary` `#E8B84B` (`:172`), `error` `#E5484D` (`:184`), `ScoutrType.monoMeta` (`:113`)
- `bridge/src/agents/claude/transcript.ts:174` and `bridge/src/agents/pi/transcript.ts:130` — usage parsing (read-only context)
- `bridge/src/agents/claude/models.ts:26`, `bridge/src/agents/agy/models.ts:11`, `bridge/src/agents/pi/models.ts:67` — authored context windows
- `AGENTS.md` verification boundary; `skills/scoutr-verification/SKILL.md`; `skills/scoutr-review/SKILL.md`
