import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, statSync, writeFileSync, appendFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseSessionPageLimit, readSession } from "../src/routes/sessions.js";
import { piBackend } from "../src/agents/pi/index.js";
import type { TranscriptReadOpts } from "../src/transcript.js";
import { handleClaudeHook } from "../src/agents/claude/hook.js";

/**
 * Fixture whose message ids are random 8-hex strings in an order that is NOT
 * lexically sorted — the exact shape of real pi session files. A lexical
 * `entryId > since` filter re-sends already-loaded entries from here.
 */
function fixtureJsonl(): string {
  const ids = ["ff676fb5", "a1b2c3d4", "77d90b6d", "0f1e2d3c", "b5a4c3d2"];
  return (
    `{"type":"session","version":3,"id":"s1","timestamp":"2026-08-09T16:39:48Z","cwd":"/tmp"}\n` +
    ids
      .map(
        (id, i) =>
          `{"type":"message","id":"${id}","parentId":null,"timestamp":"2026-08-09T16:39:5${i}Z","message":{"role":"${i % 2 === 0 ? "user" : "assistant"}","content":[{"type":"text","text":"m${i}"}]}}`,
      )
      .join("\n") +
    "\n"
  );
}

function fixtureDir() {
  const dir = mkdtempSync(join(tmpdir(), "scoutr-session-"));
  const agentDir = join(dir, "agent");
  const sessionsDir = join(agentDir, "sessions");
  mkdirSync(sessionsDir, { recursive: true });
  const file = join(sessionsDir, "session.jsonl");
  writeFileSync(file, fixtureJsonl());
  return { dir, agentDir, file };
}

test("readSession returns the full file when no cursor is given", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null);
    assert.equal(result.since, null);
    assert.deepEqual(result.entries.map((e) => e.entryId), ["ff676fb5", "a1b2c3d4", "77d90b6d", "0f1e2d3c", "b5a4c3d2"]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession with the last entry as cursor returns nothing (no overlap)", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, "b5a4c3d2");
    assert.deepEqual(result.entries, []);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession with a middle cursor returns only entries after it in file order", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, "77d90b6d");
    assert.deepEqual(result.entries.map((e) => e.entryId), ["0f1e2d3c", "b5a4c3d2"]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession with an unknown cursor returns a full snapshot with since null", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, "deadbeef");
    assert.equal(result.since, null);
    assert.equal(result.entries.length, 5);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession rejects paths outside the agent root", async () => {
  const { dir, agentDir } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await assert.rejects(() => readSession("/etc/passwd", null), /outside a registered session store/);
    await assert.rejects(
      () => readSession(`${agentDir}-evil/session.jsonl`, null),
      /outside a registered session store/,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession serves an unchanged file from the transcript memo", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const first = await readSession(file, null);
    const second = await readSession(file, null);
    // The memo reuses the parsed Transcript: entries are the same objects,
    // not a re-parse (the 2.5s poll steady state must cost one stat, not a
    // full read + JSON parse).
    assert.equal(first.entries[0], second.entries[0]);
    assert.deepEqual(second.entries, first.entries);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession drops the memo when the file grows", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await readSession(file, null);
    appendFileSync(
      file,
      `{"type":"message","id":"cafe1234","parentId":null,"timestamp":"2026-08-09T16:39:56Z","message":{"role":"user","content":[{"type":"text","text":"m6"}]}}\n`,
    );
    const result = await readSession(file, null);
    assert.equal(result.entries.length, 6);
    assert.equal(result.entries[5].entryId, "cafe1234");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession legacy full snapshot reports no older history", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null);
    assert.equal(result.beforeCursor, null);
    assert.equal(result.hasMoreBefore, false);
    assert.equal(result.lastEntryId, "b5a4c3d2");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession with limit returns the newest page in file order", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null, undefined, { limit: 2 });
    assert.deepEqual(result.entries.map((e) => e.entryId), ["0f1e2d3c", "b5a4c3d2"]);
    assert.equal(result.since, null);
    assert.equal(result.beforeCursor, "0f1e2d3c");
    assert.equal(result.hasMoreBefore, true);
    assert.equal(result.lastEntryId, "b5a4c3d2");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession reverse page returns the preceding chronological entries", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const first = await readSession(file, null, undefined, { before: "0f1e2d3c", limit: 2 });
    assert.deepEqual(first.entries.map((e) => e.entryId), ["a1b2c3d4", "77d90b6d"]);
    assert.equal(first.beforeCursor, "a1b2c3d4");
    assert.equal(first.hasMoreBefore, true);

    const middle = await readSession(file, null, undefined, { before: "77d90b6d", limit: 2 });
    assert.deepEqual(middle.entries.map((e) => e.entryId), ["ff676fb5", "a1b2c3d4"]);
    assert.equal(middle.beforeCursor, null);
    assert.equal(middle.hasMoreBefore, false);

    const finalPage = await readSession(file, null, undefined, { before: "a1b2c3d4", limit: 2 });
    assert.deepEqual(finalPage.entries.map((e) => e.entryId), ["ff676fb5"]);
    assert.equal(finalPage.beforeCursor, null);
    assert.equal(finalPage.hasMoreBefore, false);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession reverse page keeps random ids in file order", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null, undefined, { before: "b5a4c3d2", limit: 3 });
    // File order, not lexical: ff676fb5 > a1b2c3d4 > 77d90b6d would be wrong.
    assert.deepEqual(result.entries.map((e) => e.entryId), ["a1b2c3d4", "77d90b6d", "0f1e2d3c"]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession rejects an invalid limit", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await assert.rejects(() => readSession(file, null, undefined, { limit: 0 }), /limit must be an integer between 1 and 200/);
    await assert.rejects(() => readSession(file, null, undefined, { limit: 201 }), /limit must be an integer between 1 and 200/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession rejects combining since and before", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await assert.rejects(
      () => readSession(file, "b5a4c3d2", undefined, { before: "0f1e2d3c", limit: 2 }),
      /since and before cannot be combined/,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
test("readSession rejects before without limit", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await assert.rejects(
      () => readSession(file, null, undefined, { before: "0f1e2d3c" }),
      /before requires limit/,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession conflicts on a stale reverse cursor", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await assert.rejects(
      () => readSession(file, null, undefined, { before: "deadbeef", limit: 2 }),
      /reverse cursor is no longer in the transcript/,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("parseSessionPageLimit accepts 1 through 200 and rejects the rest", () => {
  assert.equal(parseSessionPageLimit(null), null);
  assert.equal(parseSessionPageLimit("1"), 1);
  assert.equal(parseSessionPageLimit("200"), 200);
  assert.throws(() => parseSessionPageLimit("0"), /limit must be an integer between 1 and 200/);
  assert.throws(() => parseSessionPageLimit("201"), /limit must be an integer between 1 and 200/);
  assert.throws(() => parseSessionPageLimit("1.5"), /limit must be an integer between 1 and 200/);
  assert.throws(() => parseSessionPageLimit("abc"), /limit must be an integer between 1 and 200/);
});

/**
 * Bounded initial page ("limit" with no cursor).
 *
 * The first read of a long session is the one that cannot afford the whole
 * file, so these fix the read mode itself: display entries must come from the
 * tail window, while model, thinking level, and question cards must stay
 * truthful about records that window never saw.
 */

/** Padding that makes each record ~1 KB, so 400 of them clear both windows. */
const FILLER = "x".repeat(1000);

function bigMessageLine(id: string, index: number): string {
  return JSON.stringify({
    type: "message",
    id,
    parentId: null,
    timestamp: `2026-08-10T00:00:${String(index % 60).padStart(2, "0")}Z`,
    message: { role: index % 2 === 0 ? "user" : "assistant", content: [{ type: "text", text: `m${index} ${FILLER}` }] },
  });
}

function piAskLine(id: string, callId: string, questions: unknown[]): string {
  return JSON.stringify({
    type: "message",
    id,
    parentId: null,
    timestamp: "2026-08-10T00:00:00Z",
    message: {
      role: "assistant",
      content: [{ type: "toolCall", id: callId, name: "ask_user_question", arguments: { questions } }],
    },
  });
}

function piAnswerLine(id: string, callId: string, answers: unknown[]): string {
  return JSON.stringify({
    type: "message",
    id,
    parentId: null,
    timestamp: "2026-08-10T00:00:01Z",
    message: { role: "toolResult", toolCallId: callId, content: [], details: { answers } },
  });
}

const ASK_QUESTIONS = [
  { question: "Where should this live?", header: "Scope", options: [{ label: "Here" }, { label: "Elsewhere" }] },
];

/**
 * A session file far past HEAD_WINDOW_BYTES + TAIL_WINDOW_BYTES (192 KiB), so
 * a tail read cannot reach [head] and its window opens mid-record.
 */
function bigFixtureDir(head: string[] = [], count = 400) {
  const dir = mkdtempSync(join(tmpdir(), "scoutr-session-big-"));
  const agentDir = join(dir, "agent");
  const sessionsDir = join(agentDir, "sessions");
  mkdirSync(sessionsDir, { recursive: true });
  const file = join(sessionsDir, "session.jsonl");
  const ids = Array.from({ length: count }, (_, i) => `e${String(i).padStart(4, "0")}`);
  const lines = [
    `{"type":"session","version":3,"id":"s-big","timestamp":"2026-08-10T00:00:00Z","cwd":"/tmp"}`,
    ...head,
    ...ids.map((id, i) => bigMessageLine(id, i)),
  ];
  writeFileSync(file, `${lines.join("\n")}\n`);
  return { dir, agentDir, file, ids };
}

/** Record the opts every display read used, so an unbounded one cannot hide. */
async function withReadSpy<T>(run: () => Promise<T>): Promise<[T, Array<TranscriptReadOpts | undefined>]> {
  const calls: Array<TranscriptReadOpts | undefined> = [];
  const original = piBackend.readTranscript;
  piBackend.readTranscript = async (path: string, opts?: TranscriptReadOpts) => {
    calls.push(opts);
    return original(path, opts);
  };
  try {
    return [await run(), calls];
  } finally {
    piBackend.readTranscript = original;
  }
}

test("readSession serves the initial page from the bounded tail window", async () => {
  const { dir, agentDir, file, ids } = bigFixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const [result, calls] = await withReadSpy(() => readSession(file, null, undefined, { limit: 5 }));
    // The only display read is the bounded one, and it asks for a single
    // entry past the page — the proof that older history exists.
    assert.deepEqual(calls, [{ tail: 6 }]);
    assert.deepEqual(result.entries.map((e) => e.entryId), ids.slice(-5));
    assert.equal(result.beforeCursor, ids.at(-5));
    assert.equal(result.hasMoreBefore, true);
    assert.equal(result.lastEntryId, ids.at(-1));
    // The window opens mid-record; no partial entry may reach the response.
    assert.ok(result.entries.every((e) => e.entryId !== "" && e.content.length > 0));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession initial page keeps a model change older than the tail window", async () => {
  const { dir, agentDir, file } = bigFixtureDir([
    `{"type":"model_change","id":"mc1","timestamp":"2026-08-10T00:00:00Z","provider":"anthropic","modelId":"claude-opus-5"}`,
    `{"type":"thinking_level_change","id":"tl1","timestamp":"2026-08-10T00:00:00Z","thinkingLevel":"high"}`,
  ]);
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null, undefined, { limit: 5 });
    assert.equal(result.model, "anthropic/claude-opus-5");
    assert.equal(result.thinkingLevel, "high");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession initial page keeps question state older than the tail window", async () => {
  const { dir, agentDir, file } = bigFixtureDir([
    piAskLine("ask-open", "call_open", ASK_QUESTIONS),
    piAskLine("ask-done", "call_done", ASK_QUESTIONS),
    piAnswerLine("ans-done", "call_done", [{ questionIndex: 0, kind: "option", answer: "Here" }]),
  ]);
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null, undefined, { limit: 5 });
    // Neither ask is in the displayed page, but an escaped ask still locks
    // Chat's composer, so both cards have to survive the bounded read.
    assert.deepEqual(result.entries.length, 5);
    assert.deepEqual(
      result.questions.map((q) => [q.callId, q.answered]),
      [["call_open", false], ["call_done", true]],
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession initial page reports no older history for a short session", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null, undefined, { limit: 10 });
    assert.equal(result.entries.length, 5);
    assert.equal(result.beforeCursor, null);
    assert.equal(result.hasMoreBefore, false);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession initial page retries a file that grows under the read", async () => {
  const { dir, agentDir, file, ids } = bigFixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  const original = piBackend.readTranscript;
  let reads = 0;
  piBackend.readTranscript = async (path: string, opts?: TranscriptReadOpts) => {
    const transcript = await original(path, opts);
    // Grow the file once, after the first attempt has already read it.
    if (++reads === 1) appendFileSync(file, `${bigMessageLine("late0001", 401)}\n`);
    return transcript;
  };
  try {
    const result = await readSession(file, null, undefined, { limit: 3 });
    assert.equal(reads, 2);
    assert.deepEqual(result.entries.map((e) => e.entryId), [...ids.slice(-2), "late0001"]);
    assert.equal(result.lastEntryId, "late0001");
    // The response describes the revision it actually read, not the one the
    // request started from.
    assert.equal(result.size, statSync(file).size);
  } finally {
    piBackend.readTranscript = original;
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession initial page follows a claude sidecar the transcript never records", async () => {
  const dir = mkdtempSync(join(tmpdir(), "scoutr-session-claude-"));
  const realConfigHome = process.env.XDG_CONFIG_HOME;
  const realClaudeDir = process.env.CLAUDECONFIGDIR;
  process.env.XDG_CONFIG_HOME = dir;
  process.env.CLAUDECONFIGDIR = join(dir, "claude");
  const project = join(dir, "claude", "projects", "-work");
  mkdirSync(project, { recursive: true });
  const session = "9f1c2d3e-0000-0000-0000-0000000000b2";
  const file = join(project, `${session}.jsonl`);
  writeFileSync(file, `${JSON.stringify({
    type: "user",
    uuid: "u1",
    sessionId: session,
    cwd: "/work",
    timestamp: "2026-08-10T00:00:00.000Z",
    message: { role: "user", content: "Pick a color" },
  })}\n`);
  const hook = (event: string) => handleClaudeHook(JSON.stringify({
    hook_event_name: event,
    session_id: session,
    transcript_path: file,
    tool_name: "AskUserQuestion",
    tool_use_id: "toolu_page",
    tool_input: {
      questions: [{ question: "Which color?", header: "Color", options: [{ label: "Red" }, { label: "Green" }] }],
    },
  }));
  try {
    const revision = statSync(file);
    assert.deepEqual((await readSession(file, null, undefined, { limit: 5 })).questions, []);

    // Claude does not write the JSONL until the ask is answered, so only the
    // sidecar moves: a page memoized on the transcript stat alone would miss it.
    hook("PreToolUse");
    assert.deepEqual([statSync(file).mtimeMs, statSync(file).size], [revision.mtimeMs, revision.size]);
    const open = await readSession(file, null, undefined, { limit: 5 });
    assert.deepEqual(open.questions.map((q) => [q.id, q.answered]), [["toolu_page#0", false]]);

    // Answering in the terminal clears the sidecar; still no transcript write.
    hook("PostToolUse");
    assert.deepEqual([statSync(file).mtimeMs, statSync(file).size], [revision.mtimeMs, revision.size]);
    assert.deepEqual((await readSession(file, null, undefined, { limit: 5 })).questions, []);
  } finally {
    if (realConfigHome === undefined) delete process.env.XDG_CONFIG_HOME;
    else process.env.XDG_CONFIG_HOME = realConfigHome;
    if (realClaudeDir === undefined) delete process.env.CLAUDECONFIGDIR;
    else process.env.CLAUDECONFIGDIR = realClaudeDir;
    rmSync(dir, { recursive: true, force: true });
  }
});
