import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { MAX_FILE_EDIT_BYTES, MAX_FILE_EDIT_LINES } from "../src/agents/file-edit.js";
import { parseClaudeTranscript } from "../src/agents/claude/transcript.js";
import { parsePiTranscript } from "../src/agents/pi/transcript.js";
import type { FileEditBlock, Transcript } from "../src/transcript.js";

/**
 * Edit detection is evidence-based: a tool result becomes a `fileEdit` because
 * it carries a patch, never because of the tool's name. These cases use the
 * record shapes observed in real claude and pi session files.
 */

function editsOf(transcript: Transcript): FileEditBlock[] {
  return transcript.entries
    .flatMap((entry) => entry.content)
    .filter((block): block is FileEditBlock => block.type === "fileEdit");
}

function claudeResult(toolUseResult: unknown, text = "done"): string {
  return JSON.stringify({
    type: "user",
    uuid: "u1",
    parentUuid: "a1",
    timestamp: "2026-08-14T10:00:00Z",
    sessionId: "s1",
    cwd: "/repo",
    message: { role: "user", content: [{ type: "tool_result", tool_use_id: "toolu_1", content: text }] },
    toolUseResult,
  });
}

function piResult(details: unknown, toolName = "edit"): string {
  return JSON.stringify({
    type: "message",
    id: "e1",
    parentId: "e0",
    timestamp: "2026-08-14T10:00:00Z",
    message: {
      role: "toolResult",
      toolCallId: "call_1",
      toolName,
      isError: false,
      content: [{ type: "text", text: "ok" }],
      details,
    },
  });
}

describe("claude file edits", () => {
  it("reads a structuredPatch off toolUseResult", () => {
    const edits = editsOf(parseClaudeTranscript(claudeResult({
      filePath: "/repo/src/main.ts",
      oldString: "a",
      newString: "b",
      structuredPatch: [
        { oldStart: 10, oldLines: 3, newStart: 10, newLines: 4, lines: [" ctx", "-old", "+new", "+extra"] },
      ],
    })));
    assert.equal(edits.length, 1);
    const edit = edits[0]!;
    assert.equal(edit.path, "/repo/src/main.ts");
    assert.equal(edit.changeKind, "edit");
    assert.equal(edit.added, 2);
    assert.equal(edit.removed, 1);
    assert.equal(edit.truncated, false);
    assert.equal(edit.hunks[0]?.header, "@@ -10,3 +10,4 @@");
  });

  it("marks a Write of a new file as a create", () => {
    const edits = editsOf(parseClaudeTranscript(claudeResult({
      type: "create",
      filePath: "/repo/new.ts",
      content: "hello",
      structuredPatch: [{ oldStart: 1, oldLines: 0, newStart: 1, newLines: 1, lines: ["+hello"] }],
    })));
    assert.equal(edits[0]?.changeKind, "create");
    assert.equal(edits[0]?.added, 1);
  });

  it("keeps the tool-result text alongside the edit", () => {
    const transcript = parseClaudeTranscript(claudeResult({
      filePath: "/repo/x.ts",
      structuredPatch: [{ oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, lines: ["+x"] }],
    }, "The file /repo/x.ts has been updated successfully."));
    const types = transcript.entries[0]?.content.map((block) => block.type);
    assert.deepEqual(types, ["text", "fileEdit"]);
  });

  it("leaves a non-edit tool result alone", () => {
    assert.deepEqual(editsOf(parseClaudeTranscript(claudeResult(undefined))), []);
    assert.deepEqual(editsOf(parseClaudeTranscript(claudeResult({ stdout: "ls output" }))), []);
  });

  it("ignores a result whose patch has no usable hunks", () => {
    assert.deepEqual(editsOf(parseClaudeTranscript(claudeResult({ filePath: "/repo/x.ts", structuredPatch: [] }))), []);
  });
});

describe("pi file edits", () => {
  it("parses a unified patch and takes the path from its header", () => {
    const patch = [
      "--- /repo/src/client.ts",
      "+++ /repo/src/client.ts",
      "@@ -205,4 +205,5 @@",
      "           if (ack.error) {",
      "-            fail();",
      "+            fail(new Error());",
      "+            return;",
      "           }",
    ].join("\n");
    const edits = editsOf(parsePiTranscript(piResult({ diff: "ignored", patch, firstChangedLine: 205 })));
    assert.equal(edits.length, 1);
    const edit = edits[0]!;
    assert.equal(edit.path, "/repo/src/client.ts");
    assert.equal(edit.added, 2);
    assert.equal(edit.removed, 1);
    assert.equal(edit.hunks[0]?.header, "@@ -205,4 +205,5 @@");
    assert.deepEqual(edit.hunks[0]?.lines[1], "-            fail();");
  });
  it("classifies unified create and delete patches", () => {
    const create = "--- /dev/null\n+++ b/new.ts\n@@ -0,0 +1 @@\n+new";
    const deletion = "--- a/old.ts\n+++ /dev/null\n@@ -1 +0,0 @@\n-old";
    const created = editsOf(parsePiTranscript(piResult({ patch: create, diff: "" })))[0];
    const deleted = editsOf(parsePiTranscript(piResult({ patch: deletion, diff: "" })))[0];

    assert.equal(created?.changeKind, "create");
    assert.equal(created?.path, "new.ts");
    assert.equal(deleted?.changeKind, "delete");
    assert.equal(deleted?.path, "old.ts");
  });

  it("strips anchors from a replace diff and splits hunks on elision", () => {
    const diff = [
      " ...",
      " Ehz│# Usage:",
      "-Csl│#   old line",
      "+l5P│#   new line",
      " ...",
      " qCJ│set -euo pipefail",
      "+Ev9│pick_device() {",
    ].join("\n");
    const edits = editsOf(parsePiTranscript(piResult(
      {
        diff,
        firstChangedLine: 5,
        snapshotId: "v2|/repo/scripts/install-app.sh|4790906|1786716267620.213|1786716267634.213|4332",
        metrics: { added_lines: 2, removed_lines: 1 },
      },
      "replace",
    )));
    assert.equal(edits.length, 1);
    const edit = edits[0]!;
    assert.equal(edit.path, "/repo/scripts/install-app.sh");
    assert.equal(edit.hunks.length, 2);
    assert.equal(edit.hunks[0]?.header, null);
    assert.deepEqual(edit.hunks[0]?.lines, [" # Usage:", "-#   old line", "+#   new line"]);
    assert.deepEqual(edit.hunks[1]?.lines, [" set -euo pipefail", "+pick_device() {"]);
    assert.equal(edit.added, 2);
    assert.equal(edit.removed, 1);
  });

  it("recognises an extension edit tool by its patch, not its name", () => {
    const patch = ["--- /repo/test.ts", "+++ /repo/test.ts", "@@ -1,1 +1,2 @@", " one", "+two"].join("\n");
    const edits = editsOf(parsePiTranscript(piResult({ patch, diff: "x", readSeekValue: { tool: "edit" } }, "readSeek_edit")));
    assert.equal(edits[0]?.path, "/repo/test.ts");
  });

  it("ignores results with no edit evidence", () => {
    assert.deepEqual(editsOf(parsePiTranscript(piResult({ metrics: {}, snapshotId: "v2|/repo/x.ts|1" }, "read"))), []);
    // A failed readSeek_edit reports empty strings rather than omitting them.
    assert.deepEqual(editsOf(parsePiTranscript(piResult({ diff: "", patch: "" }, "readSeek_edit"))), []);
  });

  it("keeps the raw details for consumers that already read them", () => {
    const transcript = parsePiTranscript(piResult({ patch: "", diff: "", firstChangedLine: 3 }));
    assert.deepEqual(transcript.entries[0]?.details, { patch: "", diff: "", firstChangedLine: 3 });
  });
});

describe("inline diff caps", () => {
  it("truncates the hunks but still reports the whole diff's counts", () => {
    const lines = Array.from({ length: MAX_FILE_EDIT_LINES + 50 }, (_, i) => `+line ${i}`);
    const edits = editsOf(parseClaudeTranscript(claudeResult({
      type: "create",
      filePath: "/repo/big.ts",
      structuredPatch: [{ oldStart: 1, oldLines: 0, newStart: 1, newLines: lines.length, lines }],
    })));
    const edit = edits[0]!;
    assert.equal(edit.truncated, true);
    assert.equal(edit.added, lines.length);
    const kept = edit.hunks.reduce((total, hunk) => total + hunk.lines.length, 0);
    assert.equal(kept, MAX_FILE_EDIT_LINES - 1);
    assert.equal(edit.hunks[0]?.lines[0], "+line 0");
  });

  it("caps on bytes when the lines are long", () => {
    const lines = Array.from({ length: 40 }, () => `+${"x".repeat(500)}`);
    const edits = editsOf(parseClaudeTranscript(claudeResult({
      filePath: "/repo/wide.ts",
      structuredPatch: [{ oldStart: 1, oldLines: 0, newStart: 1, newLines: 40, lines }],
    })));
    const edit = edits[0]!;
    assert.equal(edit.truncated, true);
    assert.equal(edit.added, 40);
    const kept = edit.hunks.reduce((total, hunk) => total + hunk.lines.length, 0);
    assert.ok(kept < 40, `expected a byte cap below 40 lines, kept ${kept}`);
    const bytes = edit.hunks.reduce(
      (total, hunk) => total + (hunk.header ? Buffer.byteLength(hunk.header, "utf8") + 1 : 0) + hunk.lines.reduce((sum, line) => sum + Buffer.byteLength(line, "utf8") + 1, 0),
      0,
    );
    assert.ok(bytes <= MAX_FILE_EDIT_BYTES, `expected bytes <= cap, got ${bytes}`);
  });
  it("caps UTF-8 lines without inserting replacement characters", () => {
    const lines = [`+${"é".repeat(10000)}`];
    const edits = editsOf(parseClaudeTranscript(claudeResult({
      filePath: "/repo/unicode.ts",
      structuredPatch: [{ oldStart: 1, oldLines: 0, newStart: 1, newLines: 1, lines }],
    })));
    const edit = edits[0]!;
    const shown = edit.hunks.flatMap((hunk) => hunk.lines).join("\n");

    assert.equal(edit.truncated, true);
    assert.equal(shown.includes("\uFFFD"), false);
  });
});
