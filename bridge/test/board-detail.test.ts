import { strict as assert } from "node:assert";
import { mkdir, mkdtemp, readFile, stat, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it, beforeEach, afterEach } from "node:test";
import {
  BoardDetailCache,
  currentAttention,
  deriveBoardDetail,
  cleanActivity,
  promptAttention,
} from "../src/board-detail.js";
import { parsePiTranscript } from "../src/agents/pi/transcript.js";
import { piBackend } from "../src/agents/pi/index.js";
import { claudeBackend } from "../src/agents/claude/index.js";
import { handleClaudeHook } from "../src/agents/claude/hook.js";
import type { QuestionEntry } from "../src/questions.js";

let nextId = 0;

function sessionLine(type: string, fields: Record<string, unknown>, ts: string): string {
  nextId += 1;
  return JSON.stringify({ type, id: `r${nextId}`, timestamp: ts, ...fields });
}

/** Board detail as the cache computes it: a parsed tail plus the file mtime. */
function detailOf(text: string, mtimeMs: number) {
  return deriveBoardDetail(parsePiTranscript(text, { tail: 40 }), mtimeMs);
}

describe("deriveBoardDetail", () => {
  it("extracts the latest model_change", () => {
    const text = [
      sessionLine("model_change", { provider: "openai-codex", modelId: "gpt-5.4" }, "2026-08-10T00:00:00Z"),
      sessionLine("model_change", { provider: "anthropic", modelId: "claude-sonnet-4-6" }, "2026-08-10T01:00:00Z"),
    ].join("\n");
    assert.equal(detailOf(text, 1).model, "anthropic/claude-sonnet-4-6");
  });

  it("picks the latest meaningful user/assistant text, skipping one-char echoes", () => {
    const text = [
      sessionLine("message", { message: { role: "user", content: "Fix the billing bug" } }, "2026-08-10T00:00:00Z"),
      sessionLine("message", { message: { role: "assistant", content: "I found the rounding error." } }, "2026-08-10T00:00:10Z"),
      sessionLine("message", { message: { role: "user", content: "ok" } }, "2026-08-10T00:00:20Z"),
    ].join("\n");
    const detail = detailOf(text, 1);
    assert.equal(detail.latestActivity, "I found the rounding error.");
    assert.equal(detail.latestActivityAtMs, Date.parse("2026-08-10T00:00:10Z"));
  });

  it("skips meaningless echoes and falls back to mtime", () => {
    const text = [
      sessionLine("message", { message: { role: "user", content: "x" } }, "2026-08-10T00:00:00Z"),
      sessionLine("message", { message: { role: "user", content: "  " } }, "2026-08-10T00:00:05Z"),
    ].join("\n");
    const mtime = Date.parse("2026-08-10T02:00:00Z");
    const detail = detailOf(text, mtime);
    assert.equal(detail.latestActivity, "");
    assert.equal(detail.latestActivityAtMs, mtime);
  });

  it("records a tool call as activity", () => {
    const text = [
      sessionLine("message", { message: { role: "assistant", content: [{ type: "text", text: "Looking now" }] } }, "2026-08-10T00:00:00Z"),
      sessionLine(
        "message",
        { message: { role: "assistant", content: [{ type: "toolCall", id: "c1", name: "read", arguments: {} }] } },
        "2026-08-10T00:00:05Z",
      ),
    ].join("\n");
    assert.equal(detailOf(text, 1).latestActivity, "[read]");
  });

  it("keeps a tool call whose name is shorter than the noise threshold", () => {
    const text = sessionLine(
      "message",
      { message: { role: "assistant", content: [{ type: "toolCall", id: "c1", name: "ls", arguments: {} }] } },
      "2026-08-10T00:00:05Z",
    );
    assert.equal(detailOf(text, 1).latestActivity, "[ls]");
  });

  it("is robust to malformed lines", () => {
    const detail = detailOf("not json\n{}\n", 1);
    assert.equal(detail.model, null);
    assert.equal(detail.latestActivity, "");
  });
});

describe("BoardDetailCache", () => {
  let dir: string;

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), "scoutr-board-detail-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = dir;
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  it("memoizes by mtime and returns stable detail", async () => {
    const path = join(dir, "s.jsonl");
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "Hello there" } }, "2026-08-10T00:00:00Z"));
    const cache = new BoardDetailCache();
    const first = await cache.detailFor(path);
    const second = await cache.detailFor(path);
    assert.equal(first?.latestActivity, "Hello there");
    assert.equal(first, second, "a memo hit returns the same detail object");
    assert.equal(cache.size, 1);
  });

  it("invalidates after the file changes", async () => {
    const path = join(dir, "s.jsonl");
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "First" } }, "2026-08-10T00:00:00Z"));
    const cache = new BoardDetailCache();
    assert.equal((await cache.detailFor(path))?.latestActivity, "First");
    await new Promise((resolve) => setTimeout(resolve, 5));
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "Second" } }, "2026-08-10T00:00:10Z"));
    assert.equal((await cache.detailFor(path))?.latestActivity, "Second");
  });

  it("returns null for missing files and prunes", async () => {
    const cache = new BoardDetailCache();
    assert.equal(await cache.detailFor(join(dir, "missing.jsonl")), null);
    const path = join(dir, "s.jsonl");
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "Hello" } }, "2026-08-10T00:00:00Z"));
    await cache.detailFor(path);
    assert.equal(cache.size, 1);
    cache.prune(new Set([join(dir, "other.jsonl")]));
    assert.equal(cache.size, 0);
  });

  it("tracks a model change outside bounded windows and after incremental growth", async () => {
    const path = join(dir, "long.jsonl");
    const launch = sessionLine("model_change", { provider: "openai-codex", modelId: "gpt-luna" }, "2026-08-10T00:00:00Z");
    const noise = (start: number) => Array.from({ length: 40 }, (_, i) =>
      sessionLine(
        "message",
        { message: { role: "user", content: `n${String(start + i).padStart(3, "0")}` + "x".repeat(2400) } },
        "2026-08-10T00:10:00Z",
      ),
    ).join("\n");
    const switched = sessionLine("model_change", { provider: "opencode-go", modelId: "hy3" }, "2026-08-10T01:00:00Z");
    await writeFile(path, `${launch}\n${noise(0)}\n${switched}\n${noise(40)}\n`);
    const cache = new BoardDetailCache();
    assert.equal((await cache.detailFor(path))?.model, "opencode-go/hy3");

    const changedAgain = sessionLine("model_change", { provider: "anthropic", modelId: "claude-sonnet" }, "2026-08-10T02:00:00Z");
    await writeFile(path, `${await readFile(path, "utf8")}${changedAgain}\n`);
    assert.equal((await cache.detailFor(path))?.model, "anthropic/claude-sonnet");
  });

  it("reads only the bounded tail of a large file", async () => {
    const path = join(dir, "big.jsonl");
    // 40 small lines of noise (about 100 KiB), then the meaningful marker at the end.
    const noise = Array.from({ length: 40 }, (_, i) =>
      sessionLine("message", { message: { role: "user", content: `n${String(i).padStart(3, "0")}` + "x".repeat(2400) } }, "2026-08-10T00:00:00Z"),
    ).join("\n");
    const marker = sessionLine("message", { message: { role: "user", content: "Tail marker found" } }, "2026-08-10T01:00:00Z");
    await writeFile(path, `${noise}\n${marker}\n`);
    const cache = new BoardDetailCache();
    const detail = await cache.detailFor(path);
    assert.equal(detail?.latestActivity, "Tail marker found");
    // The activity is capped even for very long single lines.
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "y".repeat(200) } }, "2026-08-10T02:00:00Z"));
    const capped = await cache.detailFor(path);
    assert.equal(capped?.latestActivity.length, 160);
  });
});

describe("cleanActivity", () => {
  it("collapses whitespace and caps length with an ellipsis", () => {
    assert.equal(cleanActivity("  a   b  ", 10), "a b");
    assert.equal(cleanActivity("a".repeat(200), 10), `${"a".repeat(9)}…`);
  });
});

/** One question card, as a backend extractor would report it. */
function question(fields: Partial<QuestionEntry> & { id: string }): QuestionEntry {
  return {
    callId: "call1",
    entryId: "e1",
    question: "Which color?",
    header: "Color",
    options: [{ label: "Red", description: "Warm" }, { label: "Green", description: "" }],
    multiSelect: false,
    answered: false,
    answerText: null,
    selected: [],
    timestamp: "2026-08-10T00:00:00.000Z",
    ...fields,
  };
}

describe("currentAttention", () => {
  it("summarizes a simple single-select ask as quick-answerable", () => {
    const attention = currentAttention([question({ id: "call1#0" })]);
    assert.equal(attention?.kind, "ask");
    assert.equal(attention?.callId, "call1");
    assert.equal(attention?.questionCount, 1);
    assert.equal(attention?.currentQuestion?.id, "call1#0");
    assert.equal(attention?.currentQuestion?.header, "Color");
    assert.deepEqual(attention?.currentQuestion?.options.map((o) => o.label), ["Red", "Green"]);
    assert.equal(attention?.canQuickAnswer, true);
  });

  it("has no attention once every question is answered", () => {
    assert.equal(currentAttention([question({ id: "call1#0", answered: true })]), null);
    assert.equal(currentAttention([]), null);
  });

  it("counts a multi-question ask and refuses to quick answer it", () => {
    const attention = currentAttention([
      question({ id: "call1#0" }),
      question({ id: "call1#1", question: "Which size?", header: "Size" }),
    ]);
    assert.equal(attention?.questionCount, 2);
    assert.equal(attention?.currentQuestion?.id, "call1#0");
    assert.equal(attention?.canQuickAnswer, false);
  });

  it("points at the first unanswered question of a partly answered group", () => {
    const attention = currentAttention([
      question({ id: "call1#0", answered: true }),
      question({ id: "call1#1", question: "Which size?", header: "Size" }),
    ]);
    assert.equal(attention?.currentQuestion?.id, "call1#1");
    assert.equal(attention?.questionCount, 1);
    assert.equal(attention?.canQuickAnswer, true);
  });

  it("refuses quick answer for multi-select, free text, and long option lists", () => {
    assert.equal(currentAttention([question({ id: "a#0", multiSelect: true })])?.canQuickAnswer, false);
    assert.equal(currentAttention([question({ id: "a#0", options: [] })])?.canQuickAnswer, false);
    const many = ["A", "B", "C", "D"].map((label) => ({ label, description: "" }));
    assert.equal(currentAttention([question({ id: "a#0", options: many })])?.canQuickAnswer, false);
  });

  it("exposes the newest open group when an older one is still unanswered", () => {
    const attention = currentAttention([
      question({ id: "old#0", callId: "old", timestamp: "2026-08-10T00:00:00.000Z" }),
      question({ id: "new#0", callId: "new", timestamp: "2026-08-10T01:00:00.000Z", question: "Ship it?" }),
    ]);
    assert.equal(attention?.callId, "new");
    assert.equal(attention?.currentQuestion?.question, "Ship it?");
  });

  it("passes option labels through verbatim, however long", () => {
    const label = "y".repeat(300);
    const attention = currentAttention([question({ id: "a#0", options: [{ label, description: "" }] })]);
    assert.equal(attention?.currentQuestion?.options[0]?.label, label);
  });
});

describe("promptAttention", () => {
  it("describes a blocked pane without inventing a question", () => {
    const attention = promptAttention();
    assert.equal(attention.kind, "prompt");
    assert.equal(attention.currentQuestion, null);
    assert.equal(attention.callId, null);
    assert.equal(attention.questionCount, 0);
    assert.equal(attention.canQuickAnswer, false);
  });
});

/** A pi `ask_user_question` call, as the session file records it. */
function piAskLine(callId: string, questions: unknown[], ts: string): string {
  return sessionLine(
    "message",
    { message: { role: "assistant", content: [{ type: "toolCall", id: callId, name: "ask_user_question", arguments: { questions } }] } },
    ts,
  );
}

function piAnswerLine(callId: string, answers: unknown[], ts: string): string {
  return sessionLine(
    "message",
    { message: { role: "toolResult", toolCallId: callId, toolName: "ask_user_question", content: [], details: { answers } } },
    ts,
  );
}

const PI_QUESTIONS = [
  {
    question: "Where should the papercut live?",
    header: "Scope",
    options: [{ label: "This repo", description: "Handle it here." }, { label: "Skip it", description: "Leave it open." }],
  },
];

describe("BoardDetailCache attention (pi)", () => {
  let dir: string;

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), "scoutr-board-attention-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = dir;
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  it("exposes the same open ask Chat reads from the transcript", async () => {
    const path = join(dir, "ask.jsonl");
    await writeFile(path, `${piAskLine("call_pi", PI_QUESTIONS, "2026-08-10T00:00:00Z")}\n`);
    const detail = await new BoardDetailCache().detailFor(path);
    const chat = piBackend.extractQuestions(await piBackend.readTranscript(path));
    assert.equal(chat.length, 1, "Chat sees exactly the one open question");
    assert.equal(detail?.attention?.kind, "ask");
    assert.equal(detail?.attention?.currentQuestion?.id, chat[0]?.id);
    assert.equal(detail?.attention?.callId, chat[0]?.callId);
    assert.deepEqual(
      detail?.attention?.currentQuestion?.options,
      chat[0]?.options.map((o) => ({ label: o.label, description: o.description })),
    );
    assert.equal(detail?.attention?.canQuickAnswer, true);
  });

  it("drops the attention once the ask is answered", async () => {
    const path = join(dir, "answered.jsonl");
    await writeFile(path, [
      piAskLine("call_pi", PI_QUESTIONS, "2026-08-10T00:00:00Z"),
      piAnswerLine("call_pi", [{ questionIndex: 0, kind: "option", answer: "This repo" }], "2026-08-10T00:00:10Z"),
    ].join("\n"));
    const detail = await new BoardDetailCache().detailFor(path);
    assert.equal(detail?.attention, null);
  });

  it("leaves a working session with no attention at all", async () => {
    const path = join(dir, "plain.jsonl");
    await writeFile(path, sessionLine("message", { message: { role: "user", content: "Fix the billing bug" } }, "2026-08-10T00:00:00Z"));
    const detail = await new BoardDetailCache().detailFor(path);
    assert.equal(detail?.attention, null);
  });
});

const CLAUDE_SESSION = "9f1c2d3e-0000-0000-0000-0000000000a1";

function claudePreToolUse(transcriptPath: string, toolUseId = "toolu_board"): string {
  return JSON.stringify({
    hook_event_name: "PreToolUse",
    session_id: CLAUDE_SESSION,
    transcript_path: transcriptPath,
    tool_name: "AskUserQuestion",
    tool_use_id: toolUseId,
    tool_input: {
      questions: [
        {
          question: "Which color?",
          header: "Color",
          multiSelect: false,
          options: [{ label: "Red", description: "Warm" }, { label: "Green" }],
        },
      ],
    },
  });
}

describe("BoardDetailCache attention (claude sidecar)", () => {
  let home = "";
  let path = "";
  const realConfigHome = process.env.XDG_CONFIG_HOME;
  const realClaudeDir = process.env.CLAUDECONFIGDIR;

  beforeEach(async () => {
    home = await mkdtemp(join(tmpdir(), "scoutr-board-claude-"));
    process.env.XDG_CONFIG_HOME = home;
    process.env.CLAUDECONFIGDIR = join(home, "claude");
    const project = join(home, "claude", "projects", "-work");
    await mkdir(project, { recursive: true });
    path = join(project, `${CLAUDE_SESSION}.jsonl`);
    await writeFile(path, `${JSON.stringify({
      type: "user",
      uuid: "u1",
      sessionId: CLAUDE_SESSION,
      cwd: "/work",
      timestamp: "2026-08-10T00:00:00.000Z",
      message: { role: "user", content: "Pick a color for me" },
    })}\n`);
  });

  afterEach(async () => {
    if (realConfigHome === undefined) delete process.env.XDG_CONFIG_HOME;
    else process.env.XDG_CONFIG_HOME = realConfigHome;
    if (realClaudeDir === undefined) delete process.env.CLAUDECONFIGDIR;
    else process.env.CLAUDECONFIGDIR = realClaudeDir;
    await rm(home, { recursive: true, force: true });
  });

  it("converges when the pending sidecar appears and clears without a transcript change", async () => {
    const cache = new BoardDetailCache();
    const before = await stat(path);
    assert.equal((await cache.detailFor(path))?.attention, null);

    // The hook writes the open ask; Claude does not touch the JSONL until the
    // ask is answered, so only the sidecar has changed.
    handleClaudeHook(claudePreToolUse(path));
    assert.deepEqual(await statTimes(path), [before.mtimeMs, before.size]);
    const open = (await cache.detailFor(path))?.attention;
    assert.equal(open?.kind, "ask");
    assert.equal(open?.currentQuestion?.id, "toolu_board#0");
    assert.equal(open?.canQuickAnswer, true);

    // Answering in the terminal clears the sidecar; still no transcript write.
    handleClaudeHook(JSON.stringify({
      hook_event_name: "PostToolUse",
      session_id: CLAUDE_SESSION,
      tool_name: "AskUserQuestion",
      tool_use_id: "toolu_board",
    }));
    assert.deepEqual(await statTimes(path), [before.mtimeMs, before.size]);
    assert.equal((await cache.detailFor(path))?.attention, null);
  });

  it("exposes the ids and options Chat reads for the same open ask", async () => {
    handleClaudeHook(claudePreToolUse(path));
    const detail = await new BoardDetailCache().detailFor(path);
    const chat = claudeBackend.extractQuestions(await claudeBackend.readTranscript(path));
    assert.equal(chat.length, 1, "Chat sees the hook-reported ask");
    assert.equal(chat[0]?.id, "toolu_board#0");
    assert.equal(detail?.attention?.currentQuestion?.id, chat[0]?.id);
    assert.equal(detail?.attention?.callId, chat[0]?.callId);
    assert.deepEqual(
      detail?.attention?.currentQuestion?.options,
      chat[0]?.options.map((o) => ({ label: o.label, description: o.description })),
    );
  });
});

async function statTimes(path: string): Promise<[number, number]> {
  const info = await stat(path);
  return [info.mtimeMs, info.size];
}
