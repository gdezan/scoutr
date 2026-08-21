import * as v from "valibot";
import type { FileEditBlock, FileEditHunk } from "../transcript.js";

/**
 * Normalizes each agent's edit evidence into one `fileEdit` block.
 *
 * Every supported agent already reports what an edit did — the app just never
 * saw it. This module owns the conversion, so the chat UI reads one shape and
 * an agent adapter only has to point at whichever field its CLI writes:
 *
 *   claude  `toolUseResult.structuredPatch` — hunks, already structured
 *   pi      `details.patch`                 — a unified patch (edit, readSeek_edit)
 *   pi      `details.diff`                  — anchor-prefixed lines (replace)
 *
 * Detection is deliberately evidence-based rather than name-based: a tool is an
 * edit because it produced a patch, not because it is called "Edit". Extension
 * tools that follow their agent's tool-result convention (pi's `readSeek_edit`)
 * therefore render as diffs without being known here.
 */

/** Longest diff kept inline in a transcript entry, in lines and in bytes. */
export const MAX_FILE_EDIT_LINES = 200;
export const MAX_FILE_EDIT_BYTES = 8 * 1024;

/** A `structuredPatch` hunk as Claude Code writes it. */
const structuredPatchHunkSchema = v.looseObject({
  oldStart: v.optional(v.number()),
  oldLines: v.optional(v.number()),
  newStart: v.optional(v.number()),
  newLines: v.optional(v.number()),
  lines: v.optional(v.array(v.string())),
});

type StructuredPatchHunk = v.InferOutput<typeof structuredPatchHunkSchema>;

export const claudeFileEditResultSchema = v.looseObject({
  filePath: v.optional(v.string()),
  type: v.optional(v.string()),
  structuredPatch: v.optional(v.unknown()),
});

type ClaudeFileEditResult = v.InferOutput<typeof claudeFileEditResultSchema>;

/**
 * Claude Code: `toolUseResult` on the tool-result record. `type: "create"` marks
 * a Write of a new file; an Edit has no `type` and carries old/new strings
 * alongside the same `structuredPatch`.
 */
export function fileEditFromClaudeResult(result: ClaudeFileEditResult | undefined): FileEditBlock | null {
  if (!result) return null;
  const path = result.filePath ?? "";
  if (!path) return null;
  const patch = result.structuredPatch;
  if (!Array.isArray(patch) || patch.length === 0) return null;

  const hunks: FileEditHunk[] = [];
  for (const raw of patch) {
    const hunkParsed = v.safeParse(structuredPatchHunkSchema, raw);
    if (!hunkParsed.success) continue;
    const hunk = hunkParsed.output;
    const lines = hunk.lines ?? [];
    if (lines.length === 0) continue;
    hunks.push({ header: structuredPatchHeader(hunk), lines });
  }
  if (hunks.length === 0) return null;
  return finalizeFileEdit(path, result.type === "create" ? "create" : "edit", hunks);
}

function structuredPatchHeader(hunk: StructuredPatchHunk): string | null {
  const oldStart = hunk.oldStart ?? null;
  const newStart = hunk.newStart ?? null;
  if (oldStart === null || newStart === null) return null;
  const oldLines = hunk.oldLines ?? 0;
  const newLines = hunk.newLines ?? 0;
  return `@@ -${oldStart},${oldLines} +${newStart},${newLines} @@`;
}

/**
 * pi: `details.patch`, a standard unified patch. The `---` header carries the
 * path, so this needs no help from the call arguments.
 */
export function fileEditFromUnifiedPatch(patch: string, fallbackPath = ""): FileEditBlock | null {
  if (!patch.trim()) return null;
  let path = fallbackPath;
  let oldPath: string | null = null;
  let newPath: string | null = null;
  const hunks: FileEditHunk[] = [];
  let current: FileEditHunk | null = null;

  for (const line of patch.split("\n")) {
    if (line.startsWith("--- ")) {
      const header = stripPatchPathPrefix(line.slice(4).trim());
      oldPath = header || null;
      if (header && header !== "/dev/null" && !fallbackPath) path = header;
      continue;
    }
    if (line.startsWith("+++ ")) {
      // Prefer the post-image path: it is the surviving name across a rename.
      const header = stripPatchPathPrefix(line.slice(4).trim());
      newPath = header || null;
      if (header && header !== "/dev/null" && !fallbackPath) path = header;
      continue;
    }
    if (line.startsWith("diff --git") || line.startsWith("index ")) continue;
    if (line.startsWith("@@")) {
      current = { header: line.trimEnd(), lines: [] };
      hunks.push(current);
      continue;
    }
    // "\ No newline at end of file" is a note about the previous line, not content.
    if (line.startsWith("\\")) continue;
    if (current === null) continue;
    if (line.startsWith("+") || line.startsWith("-") || line.startsWith(" ")) {
      current.lines.push(line);
    } else if (line.length === 0) {
      // An empty context line loses its leading space in some writers.
      current.lines.push(" ");
    }
  }

  const kept = hunks.filter((hunk) => hunk.lines.length > 0);
  if (kept.length === 0 || !path) return null;
  const changeKind = oldPath === "/dev/null" ? "create" : newPath === "/dev/null" ? "delete" : "edit";
  return finalizeFileEdit(path, changeKind, kept);
}

/** `a/src/x.ts` / `b/src/x.ts` → `src/x.ts`; absolute paths are left alone. */
function stripPatchPathPrefix(path: string): string {
  if (path.startsWith("a/") || path.startsWith("b/")) return path.slice(2);
  return path;
}

/**
 * pi `replace`: `details.diff`, whose lines are `<marker><anchor>│<code>` — the
 * anchor is pi's edit addressing scheme, not content, so it is stripped. Elided
 * context appears as a bare ` ...` line, which is where one hunk ends and the
 * next begins.
 */
export function fileEditFromAnchoredDiff(diff: string, path: string): FileEditBlock | null {
  if (!diff.trim() || !path) return null;
  const hunks: FileEditHunk[] = [];
  let current: FileEditHunk | null = null;

  for (const raw of diff.split("\n")) {
    if (raw.length === 0) continue;
    const marker = raw[0];
    if (marker !== " " && marker !== "+" && marker !== "-") continue;
    const body = raw.slice(1);
    const separator = body.indexOf("│");
    if (separator === -1) {
      // No anchor: an elision marker between hunks, or a stray line.
      if (body.trim() === "...") current = null;
      continue;
    }
    if (current === null) {
      current = { header: null, lines: [] };
      hunks.push(current);
    }
    current.lines.push(`${marker}${body.slice(separator + 1)}`);
  }

  const kept = hunks.filter((hunk) => hunk.lines.length > 0);
  if (kept.length === 0) return null;
  return finalizeFileEdit(path, "edit", kept);
}

/**
 * pi's `replace` records the file in `details.snapshotId`, a `|`-delimited
 * token whose second field is the absolute path.
 */
export function pathFromSnapshotId(snapshotId: string | undefined): string {
  if (!snapshotId) return "";
  const parts = snapshotId.split("|");
  return parts.length > 1 ? (parts[1] ?? "") : "";
}

/**
 * Count the whole diff, then cap what travels. The counts describe the edit
 * itself, so a truncated diff still shows a truthful `+n −n`.
 */
function finalizeFileEdit(
  path: string,
  changeKind: FileEditBlock["changeKind"],
  hunks: FileEditHunk[],
): FileEditBlock {
  let added = 0;
  let removed = 0;
  for (const hunk of hunks) {
    for (const line of hunk.lines) {
      if (line.startsWith("+")) added++;
      else if (line.startsWith("-")) removed++;
    }
  }
  const capped = capHunks(hunks);
  return {
    type: "fileEdit",
    path,
    changeKind,
    added,
    removed,
    hunks: capped.hunks,
    truncated: capped.truncated,
  };
}

/**
 * Keep the head of the diff within both caps. Transcript reads are served from
 * a bounded tail window, so one unbounded edit — a Write of a large file
 * arrives as a single all-added hunk — would otherwise push earlier turns out
 * of the window entirely.
 */
function truncateUtf8(value: string, maxBytes: number): string {
  let result = "";
  for (const character of value) {
    const next = result + character;
    if (Buffer.byteLength(next, "utf8") > maxBytes) break;
    result = next;
  }
  return result;
}

function capHunks(hunks: FileEditHunk[]) {
  const capped: FileEditHunk[] = [];
  let lines = 0;
  let bytes = 0;

  for (const hunk of hunks) {
    const headerBytes = hunk.header === null ? 0 : Buffer.byteLength(hunk.header, "utf8") + 1;
    if (lines >= MAX_FILE_EDIT_LINES || bytes + headerBytes > MAX_FILE_EDIT_BYTES) {
      return { hunks: capped, truncated: true };
    }
    if (hunk.header !== null) {
      lines++;
      bytes += headerBytes;
    }

    const kept: string[] = [];
    for (const line of hunk.lines) {
      if (lines >= MAX_FILE_EDIT_LINES) {
        if (kept.length > 0) capped.push({ header: hunk.header, lines: kept });
        return { hunks: capped, truncated: true };
      }
      const lineBytes = Buffer.byteLength(line, "utf8") + 1;
      if (bytes + lineBytes > MAX_FILE_EDIT_BYTES) {
        const available = MAX_FILE_EDIT_BYTES - bytes - 1;
        if (available > 0) {
          const partial = truncateUtf8(line, available);
          kept.push(partial);
          lines++;
          bytes += Buffer.byteLength(partial, "utf8") + 1;
        }
        if (kept.length > 0) capped.push({ header: hunk.header, lines: kept });
        return { hunks: capped, truncated: true };
      }
      kept.push(line);
      lines++;
      bytes += lineBytes;
    }
    capped.push({ header: hunk.header, lines: kept });
  }
  return { hunks: capped, truncated: false };
}
