# Per-file review reads and syntax highlighting

Scoutr's review center moves from one globally truncated diff to a stat-first, per-file model served by three read-only bridge endpoints, and the Android DiffMode gains lazy per-file loading, a full-file view, wrapping, and syntax highlighting via the maintained `dev.snipme:highlights` library.

## Context

The bridge capped the whole combined diff at 64 KiB/800 lines; files past the cap were unreachable ("Content unavailable"), and the Android client parsed the combined diff back into files client-side. Commit log rows showed subjects only, so multi-line commit bodies were invisible. File names were cut off in a single-line dropdown.

## Decision

- `GET /api/repo/diff` becomes stat-only: `git diff -z --numstat` (exact, never-abbreviated paths with real add/del counts; rename rows resolve to the new path), returning `{stat, truncated}`. Parsing the combined diff client-side is removed (`DiffFileParser.kt` deleted).
- `GET /api/repo/diff/file` runs `git diff --unified=6 <range> -- <file>` per file, stream-capped at 64 KiB (the child is killed at the cap, so oversized diffs degrade to truncation instead of an exec-buffer failure) and 800 lines, returning `{diff, truncated}`.
- `GET /api/repo/file` returns the full file at a ref (working-tree file head or `git show <ref>:<file>`), capped at 256 KiB, returning `{content, truncated, binary, exists}`. Binary is a NUL in the first 8192 bytes; missing paths return `exists: false`.
- Both per-file routes share `validateFilePath` — reject empty, >512 chars, NUL/newline/CR, leading `/`, `..` components, and git pathspec magic. Realpath containment (403 on escape) applies to the file-content route, which reads the filesystem; the diff route only reads git objects for a validated pathspec, so it has no filesystem-escape surface.
- Commit-kind ranges resolve the parent via `rev-parse` and fall back to the canonical empty tree for root commits, so "Diff vs parent" works on every commit.
- The commit log gains a `~2 KiB` bounded `body` field parsed from `%b`.
- Android `DiffMode` lazy-fetches one file at a time through `ReviewViewModel` with per-(ref, kind, file) caches, auto-selects the first file per diff session, navigates via prev/next buttons plus a bottom-sheet picker with a filter and full path + stats, and toggles between Diff hunks and the final File content.
- Line wrapping is a toggle in the diff header, default off (horizontal scroll).
- Syntax highlighting uses `dev.snipme:highlights` v1.0.0, which returns token ranges rather than rendering; a custom `SyntaxTheme` carries Scoutr's own palette colors back through `ColorHighlight.rgb`. Diff identity colors stay on the line while token colors compose on top via `AnnotatedString` spans; `+++`/`---`/`@@` lines stay plain. JSON/Markdown/YAML/text get a tiny generic fallback tokenizer (strings, numbers, comments, keywords).
- Commit rows open a `ModalBottomSheet` with the full bounded body and a "Diff vs parent" action; the working-tree row keeps its direct diff.

## Why v1.0.0 of highlights

`highlights` 1.1.0 ships Java 21 bytecode; the project's unit-test JVM is Java 17, so Robolectric runs would fail with `UnsupportedClassVersionError`. 1.0.0 has the identical API surface (`Builder.code/language/theme`, `getHighlights()`, `SyntaxTheme` fields, `SyntaxLanguage` enum) at Java 8 bytecode.

## Consequences

- Files past the 64 KiB/800-line per-file cap show a truncation note instead of disappearing entirely; the 256 KiB full-file view is the last-resort check for final content.
- One network round-trip per opened file, amortized by per-ref caches within a diff session.
- Tokenization failures degrade to unhighlighted text (try/catch → empty spans), never to errors.
