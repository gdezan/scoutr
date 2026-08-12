# Plan 009: Small-fix batch — verified one-file bugs and dead code

> **Executor instructions**: Follow this plan step by step. Each fix is
> independent — a STOP on one fix stops only that fix; finish the others and
> report the stopped one. Run every verification command. When done, update
> the status row for this plan in `plans/README.md` — unless a reviewer
> dispatched you and told you they maintain the index.
>
> **Drift check (run first)**: `git diff --stat 1ece5c9..HEAD -- bridge/src/review.ts bridge/src/notify.ts bridge/src/attachments.ts bridge/src/dirs.ts bridge/src/board-detail.ts bridge/src/agents/claude/index.ts android/app/src/main/java/dev/cockpit/app/ui/screens/ChatScreen.kt android/app/src/main/java/dev/cockpit/app/ui/screens/BoardScreen.kt android/app/src/main/java/dev/cockpit/app/ui/screens/HistoryScreen.kt`
> For any in-scope file that changed, compare that fix's excerpt against the
> live code before applying it; on a mismatch, skip that fix and report.

## Status

- **Priority**: P3
- **Effort**: M (sum of S items)
- **Risk**: LOW overall; fix H is MED and has its own STOP rule
- **Depends on**: none (fix D touches `ChatScreen.kt`; if plan 005 is in
  flight, coordinate — different functions, trivial merge)
- **Category**: bug + tech-debt
- **Planned at**: commit `1ece5c9`, 2026-08-12

## Why this matters

Eight independently-verified small defects, none worth a plan of its own, all
cheap and safe together: a diff-truncation bug that throws away up to half of
what the user is allowed to see, an image preview that has never worked, two
unbounded maps and an untimed fetch in a daemon that runs for weeks, an
upload path that trusts declared content types, dead exports left by the
consolidation refactors, two duplicated time formatters that make their own
tests timing-dependent, and a retry loop that can double-deliver a session's
first prompt.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Bridge | `cd bridge && npm run typecheck && npm test` | exit 0, all pass |
| Android unit | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL |
| Android emulator | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest` | BUILD SUCCESSFUL |
| Android build | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` | BUILD SUCCESSFUL |

## Git workflow

- Work directly on `main`. One commit per fix or per half (bridge/android),
  conventional style (`fix(bridge): …`, `fix(chat): …` — see git log).

---

## Fix A — review diff truncation discards up to half the cap

`bridge/src/review.ts:285-292` halves repeatedly instead of cutting to the cap:

```ts
function capUtf8(text: string, maxBytes: number): { text: string; truncated: boolean } {
  if (Buffer.byteLength(text, "utf8") <= maxBytes) return { text, truncated: false };
  let truncated = text;
  while (Buffer.byteLength(truncated, "utf8") > maxBytes) {
    truncated = truncated.slice(0, Math.floor(truncated.length / 2));
  }
  return { text: truncated, truncated: true };
}
```

The sibling in `bridge/src/live-output.ts:84-88` already does it right
(byte-slice + strip a leading partial code point — note it keeps the TAIL;
review wants the HEAD):

```ts
function capUtf8(text: string, maxBytes: number): { text: string; truncated: boolean } {
  const bytes = Buffer.from(text, "utf8");
  if (bytes.length <= maxBytes) return { text, truncated: false };
  return { text: bytes.subarray(bytes.length - maxBytes).toString("utf8").replace(/^�/, ""), truncated: true };
}
```

**Change**: in `review.ts`, byte-slice the head — `bytes.subarray(0,
maxBytes)` decoded, then strip a TRAILING replacement char (the mirror of the
live-output pattern: `.replace(/�$/, "")`).

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/review.test.ts` → green, plus a new case: a string of
`maxBytes + 1` ASCII bytes truncates to exactly `maxBytes` bytes; a multibyte
char straddling the cut is dropped cleanly.

## Fix B — prune the ntfy throttle map; time-bound the publish fetch

`bridge/src/notify.ts` — `lastPublishedAt` (`:18`) only ever grows, and
`publish` (`:62-69`) fetches with no timeout, so a hung ntfy server pins a
socket forever (the pattern to copy exists in
`bridge/src/usage/providers.ts` — an `AbortSignal.timeout` fetch; read it
first).

**Change**:
1. In `publish`, add `signal: AbortSignal.timeout(10_000)` to the fetch
   options (the existing catch already swallows failures with a
   `console.error` — keep that).
2. Add a `prune(paneIds: ReadonlySet<string>): void` method to
   `NtfyPublisher` mirroring `StatusTracker.prune`
   (`bridge/src/status.ts:22-27`), and call it from the pane-close branch in
   `bridge/src/server.ts:69-76` when `publisher` exists (plan 001 rewrites
   that branch — if it landed, add the call to its new form).

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/notify.test.ts` → green, plus a case: after `prune(new Set())` a
previously-throttled pane publishes again immediately.

## Fix C — verify attachment bytes match the declared image type

`bridge/src/attachments.ts` trusts the client's `Content-Type` prefix
(`storeAttachment`, `:60`: `if (!contentType.startsWith("image/")) throw`)
and the extension from the client-supplied name (`sanitizeName`, `:32-37`,
allow-list `.png/.jpg/.jpeg/.gif/.webp`). The bytes are never checked, then
the host-absolute path is handed to the agent.

**Change**: before `writeFileSync`, sniff the leading bytes and require a
match with the extension family:
- PNG: `89 50 4E 47 0D 0A 1A 0A`
- JPEG: `FF D8 FF`
- GIF: ASCII `GIF87a` or `GIF89a`
- WebP: ASCII `RIFF` at 0 + ASCII `WEBP` at 8

On mismatch: `throw new AttachmentError("attachment bytes do not match the declared image type", 400)`.

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/attachments.test.ts` → existing cases green (check whether existing
fixtures use real image bytes — if they use dummy text bodies with image
names, update those fixtures to minimal valid headers as part of this fix),
plus new cases: text bytes named `.png` → 400; a minimal real PNG header →
stored.

## Fix D — attachment thumbnail never renders (`stream.reset()` on a non-markable stream)

`android/.../ui/screens/ChatScreen.kt:1178-1190` (`AttachmentChip`):

```kotlin
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
                stream.reset()
                val sample = (bounds.outWidth / 96).coerceAtLeast(1)
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (_: Exception) {
            null
        }
```

`openInputStream` typically returns a FileInputStream-backed stream where
`markSupported()` is false, so `reset()` throws `IOException`, the catch
returns null, and the preview silently never shows.

**Change**: open the stream twice — first pass with `inJustDecodeBounds` in
its own `use{}`, then a fresh `openInputStream(uri)?.use{}` for the sampled
decode. No `reset()`.

**Verify**: `assembleDebug` green; emulator suite green. (No existing test
covers the chip; a Robolectric test of a composable-embedded private fun is
poor ROI — manual/emulator evidence is acceptable here, note it in the
commit.)

## Fix E — dead exports from the consolidation refactors

Verified unreferenced outside their own definitions (re-verify before
deleting: `grep -rn "<name>" bridge/src bridge/test`):
- `rootWithTrailingSep` (`bridge/src/dirs.ts:55-58`)
- `homeJoin` (`bridge/src/dirs.ts:60-63`)
- `fileName` in `bridge/src/board-detail.ts` (~`:93`; the `fileName` hits in
  `session-catalog.test.ts` are an unrelated local parameter — confirm)

**Change**: delete all three.

**Verify**: `cd bridge && npm run typecheck && npm test` → green.

## Fix F — one relative-time formatter, testable at a fixed instant

Two near-identical ladders, both hard-wired to `System.currentTimeMillis()`:
- `android/.../ui/screens/BoardScreen.kt:459-468` — `timeInState(sinceMs:
  Double?)`: now/5m/3h/2d, `else` keeps counting days.
- `android/.../ui/screens/HistoryScreen.kt:603-617` — `relativeTime(epochMs:
  Double)`: same ladder but >7d renders a `MMM d` date.

**Change**: add `android/app/src/main/java/dev/cockpit/app/ui/RelativeTime.kt`
with one internal function
`relativeTime(epochMs: Double, nowMs: Long = System.currentTimeMillis(), dateAfterDays: Int? = null): String`
implementing the superset (null `dateAfterDays` = keep counting days;
`7` = History behavior). Rewrite both call sites as thin wrappers or direct
calls preserving their exact current outputs (Board keeps `"now"` for <1m and
day-counting forever; History switches to the date after 7 days). Port
`BoardFormatTest.kt` and `HistoryFormatTest.kt` to pass explicit `nowMs`
values and drop any clock-seeding.

**Verify**: `testDebugUnitTest --rerun-tasks` → green; the two format test
files assert identical strings to before (copy the expected values from the
existing tests — they are the characterization).

## Fix G — pane-close pruning symmetry check (bridge)

If plan 001 landed, `StatusTracker.prune` is already wired; confirm and skip.
If NOT landed: do nothing here — the tracker prune belongs to plan 001's
rewritten close branch; do not create a competing edit.

**Verify**: `grep -rn "tracker.prune" bridge/src/server.ts` — present (001
landed) or absent (001 pending; skip recorded in the report).

## Fix H — make the claude initial-prompt retry idempotent (MED risk)

`bridge/src/agents/claude/index.ts:143-166` re-sends the prompt at the top of
every attempt and only checks delivery AFTER sending; a prompt that landed
but whose echo scrolled off the 80-line read window (or whose verification
read failed — it is `.catch(() => null)`) gets re-sent up to 3 more times:

```ts
  const marker = (text.split(/\r?\n/, 1)[0] ?? "").slice(0, 24);
  for (let attempt = 0; ; attempt += 1) {
    await herdr.agentPrompt(paneId, text);
    await sleep(2_500);
    const read = await herdr
      .agentRead(paneId, "recent_unwrapped", { lines: 80, stripAnsi: true, requestTimeoutMs: 4_000 })
      .catch(() => null);
    if (read?.read?.text?.includes(marker)) return;
    if (attempt >= delays.length) return; // give up quietly; the session is still usable manually
    await sleep(delays[attempt] ?? 3_000);
  }
```

The retry exists for a real verified window (the doc comment: a prompt at
+950ms after launch was silently dropped; the +10s retry landed). The fix
must keep that protection.

**Change**: check-before-resend. Restructure so every attempt AFTER the first
re-reads the pane BEFORE re-sending, and re-sends only when the marker is
absent from that fresh read:

```ts
  for (let attempt = 0; ; attempt += 1) {
    if (attempt > 0) {
      const check = await herdr
        .agentRead(paneId, "recent_unwrapped", { lines: 200, stripAnsi: true, requestTimeoutMs: 4_000 })
        .catch(() => null);
      if (check?.read?.text?.includes(marker)) return; // already delivered; do not double-send
      if (check === null) return; // cannot verify — never blind-resend
    }
    await herdr.agentPrompt(paneId, text);
    // ... existing post-send verification unchanged ...
  }
```

Widen the read to 200 lines on the pre-send check (scroll-away resistance).
The behavior change to pin in tests: a read that shows the marker → no second
`agentPrompt`; an unreadable pane → give up rather than blind-resend.

**Existing tests**: `bridge/test/agents-claude.test.ts` covers
`claudeDeliverInitialPrompt` with injectable `delays` — read those tests
first; they define the current retry contract. Extend rather than rewrite.

**STOP (this fix only)**: if the existing tests encode "resend even when the
marker is visible" as intended behavior (i.e. the double-send is deliberate),
stop this fix and report — the commit `c8ec61f fix(bridge): deliver the
initial prompt reliably for claude sessions` may have chosen that tradeoff.

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/agents-claude.test.ts` → green plus the two new cases.

---

## Done criteria

- [ ] `cd bridge && npm run typecheck && npm test` exits 0
- [ ] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks pixel2api36DebugAndroidTest assembleDebug` exits 0
- [ ] `grep -n "truncated.length / 2" bridge/src/review.ts` → no match
- [ ] `grep -n "AbortSignal" bridge/src/notify.ts` → match
- [ ] `grep -rn "rootWithTrailingSep\|homeJoin" bridge/src bridge/test` → no matches
- [ ] `grep -n "stream.reset()" android/app/src/main/java/dev/cockpit/app/ui/screens/ChatScreen.kt` → no match
- [ ] `grep -rn "System.currentTimeMillis" android/app/src/main/java/dev/cockpit/app/ui/screens/BoardScreen.kt android/app/src/main/java/dev/cockpit/app/ui/screens/HistoryScreen.kt` → no matches inside the time formatters
- [ ] Fixes skipped (if any) listed in the report with their STOP reason
- [ ] `plans/README.md` status row updated

## Maintenance notes

- Fix F's shared formatter is where a future locale/i18n pass starts.
- Fix C's magic-byte table must grow if `ALLOWED_EXTENSIONS` ever grows —
  they live 30 lines apart; a reviewer should check they stay in sync.
- Fix H interacts with anything that changes claude launch timing (e.g. a
  faster `waitForAgent`); the `delays` parameter is the tuning knob.
