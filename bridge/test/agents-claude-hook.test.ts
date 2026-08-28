import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile, mkdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { beforeEach, afterEach, describe, it } from "node:test";
import { defaultHookCommand, handleClaudeHook, installClaudeHook } from "../src/agents/claude/hook.js";
import { pendingAsksDir, pruneStalePendingAsks, readPendingAsk, writePendingAsk } from "../src/agents/claude/pending-asks.js";
import { claudeQuestions } from "../src/agents/claude/questions.js";
import type { Transcript } from "../src/transcript.js";

const SESSION = "9f1c2d3e-0000-0000-0000-000000000001";

interface ClaudeHookEntry {
  matcher?: string;
  hooks: Array<{ type: string; command: string }>;
}

interface ClaudeSettings {
  model?: string;
  hooks: {
    PreToolUse: ClaudeHookEntry[];
    PostToolUse: ClaudeHookEntry[];
  };
}

function emptyTranscript(id = SESSION): Transcript {
  return {
    version: 3,
    id,
    cwd: "/work",
    timestamp: "2026-08-14T00:00:00Z",
    entries: [],
    model: null,
    thinkingLevel: null,
    lastEntryId: null,
    title: null,
    preview: "",
  };
}

function preToolUse(toolUseId = "toolu_ask"): string {
  return JSON.stringify({
    hook_event_name: "PreToolUse",
    session_id: SESSION,
    transcript_path: "/home/u/.claude/projects/p/s.jsonl",
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

describe("claude pending asks", () => {
  let home = "";
  const realHome = process.env.XDG_CONFIG_HOME;

  beforeEach(async () => {
    home = await mkdtemp(join(tmpdir(), "scoutr-hook-"));
    process.env.XDG_CONFIG_HOME = home;
  });
  afterEach(async () => {
    if (realHome === undefined) delete process.env.XDG_CONFIG_HOME;
    else process.env.XDG_CONFIG_HOME = realHome;
    await rm(home, { recursive: true, force: true });
  });

  it("shows the open ask as unanswered cards, with the ids the transcript will use", () => {
    // Claude writes the tool call to its JSONL only once the ask is answered,
    // so without the hook this transcript has no card to show at all.
    handleClaudeHook(preToolUse());
    const questions = claudeQuestions(emptyTranscript());
    assert.equal(questions.length, 1);
    assert.equal(questions[0]?.id, "toolu_ask#0");
    assert.equal(questions[0]?.callId, "toolu_ask");
    assert.equal(questions[0]?.answered, false);
    assert.deepEqual(questions[0]?.options.map((o) => o.label), ["Red", "Green"]);
  });

  it("flags a question whose options carry previews, which changes its key grammar", () => {
    // Claude renders a single-select preview question in a different layout;
    // the adapter's questionnaire reads this flag to pick the right keys.
    handleClaudeHook(
      JSON.stringify({
        hook_event_name: "PreToolUse",
        session_id: SESSION,
        transcript_path: "/home/u/.claude/projects/p/s.jsonl",
        tool_name: "AskUserQuestion",
        tool_use_id: "toolu_ask",
        tool_input: {
          questions: [
            {
              question: "Which layout?",
              header: "Layout",
              multiSelect: false,
              options: [{ label: "Wide", preview: "WIDE\nlines" }, { label: "Tall" }],
            },
            {
              question: "Which color?",
              header: "Color",
              multiSelect: false,
              options: [{ label: "Red" }, { label: "Green" }],
            },
          ],
        },
      }),
    );
    const questions = claudeQuestions(emptyTranscript());
    // Per question, not per ask: one preview option does not flag its sibling.
    assert.deepEqual(questions.map((q) => q.hasPreviews), [true, undefined]);
  });

  it("clears the ask when the tool result comes back", () => {
    handleClaudeHook(preToolUse());
    handleClaudeHook(JSON.stringify({
      hook_event_name: "PostToolUse",
      session_id: SESSION,
      tool_name: "AskUserQuestion",
      tool_use_id: "toolu_ask",
    }));
    assert.equal(readPendingAsk(SESSION), null);
    assert.deepEqual(claudeQuestions(emptyTranscript()), []);
  });

  it("drops the sidecar once the transcript carries the same call", () => {
    handleClaudeHook(preToolUse());
    const transcript = emptyTranscript();
    transcript.entries = [
      {
        entryId: "a1",
        parentId: null,
        timestamp: "2026-08-14T00:00:01Z",
        role: "assistant",
        content: [
          {
            type: "toolCall",
            id: "toolu_ask",
            name: "AskUserQuestion",
            arguments: { questions: [{ question: "Which color?", header: "Color", options: [{ label: "Red" }] }] },
          },
        ],
      },
    ];
    const questions = claudeQuestions(transcript);
    assert.equal(questions.length, 1); // the transcript's card, not a duplicate
    assert.equal(questions[0]?.entryId, "a1");
    assert.equal(readPendingAsk(SESSION), null); // and the sidecar is gone
  });

  it("ignores other tools and unparseable payloads", () => {
    handleClaudeHook(JSON.stringify({ hook_event_name: "PreToolUse", session_id: SESSION, tool_name: "Bash" }));
    handleClaudeHook("not json");
    assert.equal(readPendingAsk(SESSION), null);
  });

  it("forgets an ask nobody could still be looking at", () => {
    writePendingAsk({
      sessionId: SESSION,
      toolUseId: "toolu_old",
      timestamp: new Date(Date.now() - 48 * 60 * 60 * 1000).toISOString(),
      transcriptPath: "",
      questions: [{ question: "Old?", header: "Old", options: [{ label: "Yes" }] }],
    });
    assert.equal(readPendingAsk(SESSION), null);
    assert.equal(existsSync(join(pendingAsksDir(), `${SESSION}.json`)), false);
  });

  it("prunes sidecars left by a session that died mid-ask", () => {
    writePendingAsk({
      sessionId: SESSION,
      toolUseId: "toolu_old",
      timestamp: new Date(Date.now() - 48 * 60 * 60 * 1000).toISOString(),
      transcriptPath: "",
      questions: [],
    });
    assert.equal(pruneStalePendingAsks(), 1);
    assert.equal(pruneStalePendingAsks(), 0);
  });
});

const HOOK_COMMAND = defaultHookCommand();

describe("claude hook installation", () => {
  it("names an absolute interpreter and CLI, since a hook inherits no PATH", () => {
    const [node] = HOOK_COMMAND.split(" ");
    assert.equal(node?.replace(/'/g, "").startsWith("/"), true);
    assert.match(HOOK_COMMAND, /cli\.js'? hook claude$/);
    assert.equal(HOOK_COMMAND.includes("src/cli.ts"), false);
  });

  let dir = "";
  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), "scoutr-claude-settings-"));
  });
  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  it("adds the hooks without disturbing existing settings", async () => {
    const path = join(dir, "settings.json");
    await mkdir(dir, { recursive: true });
    await writeFile(
      path,
      JSON.stringify({
        model: "opus",
        hooks: { PreToolUse: [{ matcher: "Bash", hooks: [{ type: "command", command: "audit" }] }] },
      }),
    );
    const first = await installClaudeHook(HOOK_COMMAND, path);
    assert.equal(first.changed, true);
    // SAFETY: installClaudeHook writes the tested Claude settings shape with both hook arrays.
    const settings = JSON.parse(await readFile(path, "utf8")) as ClaudeSettings;
    assert.equal(settings.model, "opus");
    assert.equal(settings.hooks.PreToolUse.length, 2);
    assert.equal(settings.hooks.PreToolUse[0].matcher, "Bash");
    assert.equal(settings.hooks.PreToolUse[1].hooks[0].command, HOOK_COMMAND);
    assert.equal(settings.hooks.PostToolUse[0].matcher, "AskUserQuestion");

    const second = await installClaudeHook(HOOK_COMMAND, path);
    assert.equal(second.changed, false);
  });

  it("writes a fresh settings file when there is none", async () => {
    const path = join(dir, "nested", "settings.json");
    const result = await installClaudeHook(HOOK_COMMAND, path);
    assert.equal(result.changed, true);
    // SAFETY: installClaudeHook writes the tested Claude settings shape with both hook arrays.
    const settings = JSON.parse(await readFile(path, "utf8")) as ClaudeSettings;
    assert.equal(settings.hooks.PreToolUse[0].hooks[0].command, HOOK_COMMAND);
  });
});
