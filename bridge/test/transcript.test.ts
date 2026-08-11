import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it, beforeEach, afterEach } from "node:test";
import {
  HEAD_WINDOW_BYTES,
  TAIL_WINDOW_BYTES,
  entryText,
  inspectSessionFile,
  parseTranscript,
  readTranscript,
  writeSessionTitle,
} from "../src/transcript.js";

const SAMPLE = `{"type":"session","version":3,"id":"abc123","timestamp":"2026-08-09T16:39:48.826Z","cwd":"/home/gdezan/Dev/x"}
{"type":"model_change","id":"m1","parentId":null,"timestamp":"2026-08-09T16:39:50Z","provider":"opencode-go","modelId":"deepseek-v4-flash"}
{"type":"thinking_level_change","id":"t1","parentId":"m1","timestamp":"2026-08-09T16:39:50Z","thinkingLevel":"high"}
{"type":"custom","customType":"plannotator","data":{"phase":"idle"},"id":"c1","parentId":"t1","timestamp":"2026-08-09T16:39:51Z"}
{"type":"message","id":"e1","parentId":"c1","timestamp":"2026-08-09T16:39:52Z","message":{"role":"user","content":[{"type":"text","text":"hello"}]}}
{"type":"message","id":"e2","parentId":"e1","timestamp":"2026-08-09T16:39:53Z","message":{"role":"assistant","content":[{"type":"thinking","thinking":"plan"},{"type":"toolCall","id":"call_1","name":"bash","arguments":{"command":"ls"}},{"type":"text","text":"on it"}],"usage":{"input":100,"output":20,"cost":{"input":0.01}},"stopReason":"toolUse","model":"deepseek-v4-flash"}}
{"type":"message","id":"e3","parentId":"e2","timestamp":"2026-08-09T16:39:54Z","message":{"role":"toolResult","toolCallId":"call_1","toolName":"bash","isError":false,"content":[{"type":"text","text":"a.txt"}]}}
`;

describe("parseTranscript", () => {
  it("parses a version-3 pi session file", () => {
    const transcript = parseTranscript(SAMPLE);
    assert.equal(transcript.id, "abc123");
    assert.equal(transcript.cwd, "/home/gdezan/Dev/x");
    assert.equal(transcript.timestamp, "2026-08-09T16:39:48.826Z");
    assert.equal(transcript.lastEntryId, "e3");
    assert.equal(transcript.entries.length, 3);
  });

  it("tracks the active model and thinking level", () => {
    const transcript = parseTranscript(
      `${SAMPLE}\n{"type":"model_change","provider":"openai-codex","modelId":"gpt-5.4"}\n{"type":"thinking_level_change","thinkingLevel":"xhigh"}`,
    );
    assert.equal(transcript.model, "openai-codex/gpt-5.4");
    assert.equal(transcript.thinkingLevel, "xhigh");
  });

  it("extracts user text and assistant blocks", () => {
    const transcript = parseTranscript(SAMPLE);
    const [user, assistant, toolResult] = transcript.entries;
    assert.equal(user.role, "user");
    assert.equal((user.content[0] as { text: string }).text, "hello");
    assert.equal(assistant.role, "assistant");
    const kinds = assistant.content.map((block) => block.type);
    assert.deepEqual(kinds, ["thinking", "toolCall", "text"]);
    const call = assistant.content[1] as { name: string; arguments: unknown };
    assert.equal(call.name, "bash");
    assert.equal(assistant.usage?.input, 100);
    assert.equal(assistant.usage?.cost?.input, 0.01);
    assert.equal(assistant.stopReason, "toolUse");
    assert.equal(toolResult.toolName, "bash");
    assert.equal(toolResult.toolCallId, "call_1");
    assert.equal(toolResult.isError, false);
  });

  it("string content is normalized to a text block", () => {
    const transcript = parseTranscript(
      `{"type":"message","id":"s1","parentId":null,"timestamp":"2026-08-09T00:00:00Z","message":{"role":"user","content":"plain"}}`,
    );
    assert.equal((transcript.entries[0].content[0] as { text: string }).text, "plain");
  });

  it("tolerates garbage lines in a growing file", () => {
    const transcript = parseTranscript(`${SAMPLE}\nnot json at all\n{"type":"message","id":"e9",`);
    assert.equal(transcript.entries.length, 3);
  });

  it("reads the title from session_info and the preview from the first user turn", () => {
    const transcript = parseTranscript(
      `${SAMPLE}{"type":"session_info","id":"i1","parentId":null,"timestamp":"2026-08-09T17:00:00Z","name":"Release review"}\n`,
    );
    assert.equal(transcript.title, "Release review");
    assert.equal(transcript.preview, "hello");
  });

  it("leaves title null and preview empty when the file has neither", () => {
    const transcript = parseTranscript(
      `{"type":"session","version":3,"id":"only","timestamp":"2026-08-09T00:00:00Z","cwd":"/w"}`,
    );
    assert.equal(transcript.title, null);
    assert.equal(transcript.preview, "");
  });

  it("collapses whitespace in the preview and ignores non-user turns", () => {
    const transcript = parseTranscript(
      [
        `{"type":"message","id":"a1","timestamp":"2026-08-09T00:00:00Z","message":{"role":"assistant","content":"assistant first"}}`,
        `{"type":"message","id":"u1","timestamp":"2026-08-09T00:00:01Z","message":{"role":"user","content":"  wrapped\\n  prompt  "}}`,
        `{"type":"message","id":"u2","timestamp":"2026-08-09T00:00:02Z","message":{"role":"user","content":"later prompt"}}`,
      ].join("\n"),
    );
    assert.equal(transcript.preview, "wrapped prompt");
  });
});

describe("parseTranscript read modes", () => {
  it("tail keeps only the last N entries", () => {
    const transcript = parseTranscript(SAMPLE, { tail: 2 });
    assert.deepEqual(transcript.entries.map((entry) => entry.entryId), ["e2", "e3"]);
    assert.equal(transcript.lastEntryId, "e3");
  });

  it("metadataOnly retains metadata but no entries", () => {
    const transcript = parseTranscript(SAMPLE, { metadataOnly: true });
    assert.deepEqual(transcript.entries, []);
    assert.equal(transcript.id, "abc123");
    assert.equal(transcript.model, "opencode-go/deepseek-v4-flash");
    assert.equal(transcript.preview, "hello");
    assert.equal(transcript.lastEntryId, "e3");
  });

  it("all three modes agree on the metadata of one file", () => {
    const full = parseTranscript(SAMPLE);
    const tailed = parseTranscript(SAMPLE, { tail: 40 });
    const metadata = parseTranscript(SAMPLE, { metadataOnly: true });
    for (const other of [tailed, metadata]) {
      assert.equal(other.id, full.id);
      assert.equal(other.cwd, full.cwd);
      assert.equal(other.timestamp, full.timestamp);
      assert.equal(other.model, full.model);
      assert.equal(other.thinkingLevel, full.thinkingLevel);
      assert.equal(other.title, full.title);
      assert.equal(other.preview, full.preview);
      assert.equal(other.lastEntryId, full.lastEntryId);
    }
    assert.deepEqual(tailed.entries, full.entries);
  });
});

describe("entryText", () => {
  it("skips thinking, names tool calls, and truncates", () => {
    const assistant = parseTranscript(SAMPLE).entries[1];
    assert.ok(entryText(assistant, 40).startsWith("[bash] on it"));
    assert.equal(entryText(assistant, 6), "[bash]…");
  });
});

describe("readTranscript", () => {
  let dir: string;

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), "cockpit-transcript-"));
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  /** A file whose head and tail are further apart than either read window. */
  async function writeWideFile(name: string): Promise<string> {
    const path = join(dir, name);
    const filler = Array.from({ length: 120 }, (_, index) =>
      JSON.stringify({
        type: "message",
        id: `f${index}`,
        timestamp: "2026-08-10T00:30:00Z",
        message: { role: "assistant", content: `filler ${index} ${"x".repeat(2000)}` },
      }),
    ).join("\n");
    await writeFile(
      path,
      [
        JSON.stringify({ type: "session", version: 3, id: "wide", cwd: "/w", timestamp: "2026-08-10T00:00:00Z" }),
        JSON.stringify({
          type: "message",
          id: "head",
          timestamp: "2026-08-10T00:00:01Z",
          message: { role: "user", content: "head marker prompt" },
        }),
        filler,
        JSON.stringify({ type: "model_change", provider: "anthropic", modelId: "claude-opus-5" }),
        JSON.stringify({ type: "session_info", name: "Wide session" }),
        JSON.stringify({
          type: "message",
          id: "tail",
          timestamp: "2026-08-10T02:00:00Z",
          message: { role: "user", content: "tail marker prompt" },
        }),
        "",
      ].join("\n"),
    );
    const { size } = await inspectSessionFile(path);
    assert.ok(size > HEAD_WINDOW_BYTES + TAIL_WINDOW_BYTES, `fixture must exceed both windows, got ${size}`);
    return path;
  }

  it("reads the whole file by default", async () => {
    const path = join(dir, "small.jsonl");
    await writeFile(path, SAMPLE);
    const transcript = await readTranscript(path);
    assert.deepEqual(transcript.entries.map((entry) => entry.entryId), ["e1", "e2", "e3"]);
  });

  it("tail mode reads only a bounded window from the end", async () => {
    const path = await writeWideFile("wide-tail.jsonl");
    const transcript = await readTranscript(path, { tail: 40 });
    const ids = transcript.entries.map((entry) => entry.entryId);
    assert.equal(ids.at(-1), "tail");
    // The bound is real: the window, not the requested count, limits the read,
    // and the head of the file was never touched.
    assert.ok(ids.length < 40, `window should cap the entry count, got ${ids.length}`);
    assert.ok(ids.length > 1);
    assert.ok(!ids.includes("head"));
    assert.ok(!ids.includes("f0"));
    assert.equal(transcript.id, "");
    assert.equal(transcript.model, "anthropic/claude-opus-5");
  });

  it("tail mode drops the partial line the window starts inside", async () => {
    const path = await writeWideFile("wide-partial.jsonl");
    const transcript = await readTranscript(path, { tail: 200 });
    for (const entry of transcript.entries) {
      assert.ok(entry.entryId.length > 0);
      assert.ok(entryText(entry).length > 0);
    }
  });

  it("metadataOnly reads head and tail windows and skips the middle", async () => {
    const path = await writeWideFile("wide-metadata.jsonl");
    const transcript = await readTranscript(path, { metadataOnly: true });
    assert.deepEqual(transcript.entries, []);
    assert.equal(transcript.id, "wide");
    assert.equal(transcript.cwd, "/w");
    assert.equal(transcript.timestamp, "2026-08-10T00:00:00Z");
    assert.equal(transcript.preview, "head marker prompt");
    assert.equal(transcript.title, "Wide session");
    assert.equal(transcript.model, "anthropic/claude-opus-5");
  });

  it("metadataOnly loses no record where the head and tail windows meet", async () => {
    // A file just over the head window, with a record placed to straddle the
    // seam. Read as two windows with a separator, that record is torn in half
    // and lost; the windows are contiguous here, so it must survive whole.
    const path = join(dir, "seam.jsonl");
    // Everything here is ASCII, so byte length and string length agree.
    const filler = (index: number, padding: number) =>
      `${JSON.stringify({
        type: "message",
        id: `f${index}`,
        timestamp: "2026-08-10T00:30:00Z",
        message: { role: "assistant", content: `filler ${index} ${"x".repeat(padding)}` },
      })}\n`;
    const straddling = JSON.stringify({ type: "session_info", name: "Seam title" });
    // The seam record must start before the head window ends and finish after
    // it, so put its midpoint exactly on the boundary.
    const seamOffset = HEAD_WINDOW_BYTES - Math.floor(straddling.length / 2);

    let text = `${JSON.stringify({ type: "session", version: 3, id: "seam", cwd: "/w", timestamp: "2026-08-10T00:00:00Z" })}\n`;
    for (let index = 0; text.length < seamOffset - 4_000; index += 1) text += filler(index, 2_000);
    // One last line padded to land the seam record on the exact byte.
    text += filler(998, seamOffset - text.length - filler(998, 0).length);
    assert.equal(text.length, seamOffset, "padding must land the seam record exactly");
    text += `${straddling}\n${filler(999, 2_000)}`;
    await writeFile(path, text);

    assert.ok(seamOffset < HEAD_WINDOW_BYTES, "the seam record must start before the head window ends");
    assert.ok(
      seamOffset + straddling.length > HEAD_WINDOW_BYTES,
      "the seam record must end after the head window ends",
    );
    const { size } = await inspectSessionFile(path);
    assert.ok(size > HEAD_WINDOW_BYTES, `fixture must exceed the head window, got ${size}`);
    assert.ok(size < HEAD_WINDOW_BYTES + TAIL_WINDOW_BYTES, "fixture must fit inside both windows together");

    const metadata = await readTranscript(path, { metadataOnly: true });
    assert.equal(metadata.id, "seam");
    assert.equal(metadata.title, "Seam title");
    assert.equal(metadata.lastEntryId, (await readTranscript(path)).lastEntryId);
  });

  it("all three file read modes agree on a small file", async () => {
    const path = join(dir, "agree.jsonl");
    await writeFile(path, SAMPLE);
    const full = await readTranscript(path);
    const tailed = await readTranscript(path, { tail: 40 });
    const metadata = await readTranscript(path, { metadataOnly: true });
    for (const other of [tailed, metadata]) {
      assert.equal(other.id, full.id);
      assert.equal(other.cwd, full.cwd);
      assert.equal(other.model, full.model);
      assert.equal(other.preview, full.preview);
      assert.equal(other.lastEntryId, full.lastEntryId);
    }
    assert.deepEqual(tailed.entries, full.entries);
  });

  it("reports missing files through inspectSessionFile", async () => {
    const missing = await inspectSessionFile(join(dir, "nope.jsonl"));
    assert.equal(missing.exists, false);
    assert.equal(missing.size, 0);
    const path = join(dir, "there.jsonl");
    await writeFile(path, SAMPLE);
    const present = await inspectSessionFile(path);
    assert.equal(present.exists, true);
    assert.ok(present.size > 0);
    assert.equal(Number.isInteger(present.mtimeMs), true);
  });
});

describe("writeSessionTitle", () => {
  let dir: string;

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), "cockpit-transcript-write-"));
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  it("appends a session_info record the parser reads back", async () => {
    const path = join(dir, "s.jsonl");
    await writeFile(path, SAMPLE);
    await writeSessionTitle(path, "Release follow-up");
    assert.equal((await readTranscript(path)).title, "Release follow-up");
    // The transcript itself is untouched — the record is appended, never rewritten.
    assert.deepEqual((await readTranscript(path)).entries.map((entry) => entry.entryId), ["e1", "e2", "e3"]);
    const lines = (await readFile(path, "utf8")).trim().split("\n");
    const record = JSON.parse(lines.at(-1) as string) as Record<string, unknown>;
    assert.equal(record.type, "session_info");
    assert.equal(record.name, "Release follow-up");
    assert.equal(record.parentId, null);
    assert.equal(typeof record.id, "string");
    assert.ok(!Number.isNaN(Date.parse(record.timestamp as string)));
  });

  it("keeps the newest title when several are appended", async () => {
    const path = join(dir, "s.jsonl");
    await writeFile(path, SAMPLE);
    await writeSessionTitle(path, "First");
    await writeSessionTitle(path, "Second");
    assert.equal((await readTranscript(path)).title, "Second");
  });
});
