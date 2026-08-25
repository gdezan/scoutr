import assert from "node:assert/strict";
import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import {
  claudeBackend,
  claudeControl,
  claudeLaunchCommand,
  claudeOwnsSessionPath,
  claudeProjectDir,
  claudeResolveSessionPath,
  claudeResumeCommand,
} from "../src/agents/claude/index.js";
import { parseClaudeTranscript } from "../src/agents/claude/transcript.js";
import { readClaudeCommandsCatalog } from "../src/agents/claude/commands.js";
import { claudeDeliverInitialPrompt, claudeExtractQuestions, claudeReadTranscriptState } from "../src/agents/claude/index.js";
import type { AgentReadResponse } from "../src/herdr/client.js";
import type { ContentBlock, ToolCallBlock } from "../src/transcript.js";
import { fakeHerdr } from "./support/fake-herdr.js";

interface ClaudeUserRecordExtra {
  aiTitle?: string;
}

/** CLAUDECONFIGDIR honing: point the claude adapter at a temp store. */
async function claudeStore(): Promise<string> {
  const config = await mkdtemp(join(tmpdir(), "scoutr-claude-"));
  process.env.CLAUDECONFIGDIR = config;
  await mkdir(join(config, "projects"), { recursive: true });
  return config;
}

function userRecord(uuid: string, content: string, extra: ClaudeUserRecordExtra = {}) {
  return {
    type: "user",
    uuid,
    parentUuid: null,
    timestamp: "2026-01-01T00:00:00.000Z",
    cwd: "/work/alpha",
    sessionId: "9f1c2d3e-0000-0000-0000-000000000001",
    message: { role: "user", content },
    ...extra,
  };
}

function toolResultRecord(uuid: string, toolUseId: string) {
  return {
    type: "user",
    uuid,
    parentUuid: null,
    timestamp: "2026-01-01T00:00:01.000Z",
    message: {
      role: "user",
      content: [{ type: "tool_result", tool_use_id: toolUseId, content: "42", is_error: false }],
    },
  };
}

function assistantRecord(uuid: string, blocks: unknown[]) {
  return {
    type: "assistant",
    uuid,
    parentUuid: null,
    timestamp: "2026-01-01T00:00:02.000Z",
    message: {
      role: "assistant",
      content: blocks,
      model: "claude-sonnet-4-6",
      stop_reason: "end_turn",
      usage: { input_tokens: 10, output_tokens: 20 },
    },
    requestId: "req-1",
  };
}

function agentReadResponse(text: string): AgentReadResponse {
  return {
    type: "pane_read",
    read: {
      pane_id: "p1",
      workspace_id: "ws1",
      tab_id: "t1",
      source: "recent_unwrapped",
      format: "text",
      text,
      revision: 0,
      truncated: false,
    },
  };
}

function isToolCallBlock(block: ContentBlock): block is ToolCallBlock {
  return block.type === "toolCall" && "id" in block && "name" in block && "arguments" in block;
}

describe("claude adapter", () => {
  describe("launch", () => {
    it("launches a plain claude session", () => {
      assert.equal(claudeLaunchCommand({}), "claude");
    });
    it("shell-quotes model and name", () => {
      assert.equal(claudeLaunchCommand({ model: "claude-sonnet-4-6", name: "a b'c" }), "claude --model 'claude-sonnet-4-6' --name 'a b'\\''c'");
    });
    it("drops the picker's provider prefix from the model", () => {
      assert.equal(claudeLaunchCommand({ model: "anthropic/claude-opus-5" }), "claude --model 'claude-opus-5'");
    });
    it("passes a thinking level as --effort", () => {
      assert.equal(
        claudeLaunchCommand({ model: "anthropic/claude-opus-5", thinkingLevel: "xhigh" }),
        "claude --model 'claude-opus-5' --effort 'xhigh'",
      );
    });
    it("omits effort unsupported by the selected model", () => {
      assert.equal(
        claudeLaunchCommand({ model: "anthropic/claude-haiku-4-5-20251001", thinkingLevel: "xhigh" }),
        "claude --model 'claude-haiku-4-5-20251001'",
      );
    });
    it("ignores a thinking level claude does not accept", () => {
      assert.equal(claudeLaunchCommand({ thinkingLevel: "off" }), "claude");
    });
  });

  describe("commands", () => {
    it("lists built-ins plus the project's and user's own commands and skills", async () => {
      const config = await claudeStore();
      const project = await mkdtemp(join(tmpdir(), "scoutr-claude-cwd-"));

      await mkdir(join(config, "commands"), { recursive: true });
      await writeFile(
        join(config, "commands", "standup.md"),
        "---\ndescription: Write today's standup note\nargument-hint: <date>\n---\n\nSummarize what changed.\n",
      );
      // Subdirectories group commands but do not namespace them.
      await mkdir(join(config, "commands", "frontend"), { recursive: true });
      await writeFile(join(config, "commands", "frontend", "component.md"), "Scaffold a component.\n");

      await mkdir(join(config, "skills", "unslop"), { recursive: true });
      await writeFile(
        join(config, "skills", "unslop", "SKILL.md"),
        "---\nname: unslop\ndescription: Remove AI writing patterns from prose\n---\n\nBody.\n",
      );

      await mkdir(join(project, ".claude", "commands"), { recursive: true });
      await writeFile(
        join(project, ".claude", "commands", "deploy.md"),
        "---\ndescription: Ship the bridge\n---\n\nRun the deploy.\n",
      );

      const catalog = await readClaudeCommandsCatalog(project, config);
      const byName = new Map(catalog.commands.map((command) => [command.name, command]));

      assert.equal(byName.get("model")?.source, "builtin");
      assert.equal(byName.get("security-review")?.source, "skill", "bundled skills ship inside the CLI");
      assert.equal(byName.get("deploy")?.description, "Ship the bridge");
      assert.equal(byName.get("deploy")?.source, "prompt");
      assert.equal(byName.get("standup")?.argumentHint, "<date>");
      assert.equal(byName.get("component")?.description, "Scaffold a component.", "no frontmatter: first prose line");
      assert.equal(byName.get("unslop")?.source, "skill");
      assert.equal(new Set(catalog.commands.map((c) => c.name)).size, catalog.commands.length, "names are unique");
    });

    it("falls back to built-ins when the stores are missing", async () => {
      const config = await mkdtemp(join(tmpdir(), "scoutr-claude-empty-"));
      const catalog = await readClaudeCommandsCatalog(undefined, config);
      assert.ok(catalog.commands.length > 0);
      assert.ok(catalog.commands.every((command) => command.source === "builtin" || command.source === "skill"));
    });
  });

  describe("models", () => {
    it("offers an anthropic catalog the picker can key by provider/id", () => {
      const catalog = claudeBackend.models();
      assert.equal(catalog.providers.length, 1);
      const provider = catalog.providers[0]!;
      assert.equal(provider.name, "anthropic");
      assert.ok(provider.models.some((model) => model.id === "claude-opus-5"));
      for (const model of provider.models) {
        assert.equal(model.provider, "anthropic");
        assert.ok(model.thinkingLevels.includes("high"));
      }
      const haiku = provider.models.find((model) => model.id === "claude-haiku-4-5-20251001");
      assert.deepEqual(haiku?.thinkingLevels, ["low", "medium", "high"]);
    });
  });

  describe("resume", () => {
    it("derives the session id from the transcript basename", () => {
      assert.equal(
        claudeResumeCommand("/home/u/.claude/projects/-repo-hash/7d012817-0fb3-4810-9172-f26710238ead.jsonl", "resume"),
        "claude --resume '7d012817-0fb3-4810-9172-f26710238ead'",
      );
    });
    it("refuses fork-at-path launch with guidance", () => {
      assert.throws(
        () => claudeResumeCommand("/p/abc.jsonl", "fork"),
        /resume the session and use \/fork/,
      );
    });
  });

  describe("ownsSessionPath", () => {
    it("claims transcripts under the projects root and rejects traversal", async () => {
      const config = await claudeStore();
      const path = join(config, "projects", "-encoded-project-", "sess.jsonl");
      assert.equal(claudeOwnsSessionPath(path), true);
      assert.equal(claudeOwnsSessionPath(join(config, "projects", "-encoded-project-", "notes.txt")), false);
      assert.equal(claudeOwnsSessionPath(join(config, "other", "sess.jsonl")), false);
      // a path that has not been created yet (live pane) still resolves lexically
      assert.equal(claudeOwnsSessionPath(join(config, "projects", "live", "live.jsonl")), true);
    });
    it("canonicalizes symlinked roots (review fix: symlink mismatch)", async () => {
      const config = await claudeStore();
      const real = join(config, "real-store");
      await mkdir(join(real, "projects"), { recursive: true });
      await writeFile(join(real, "projects", "sess.jsonl"), "");
      const link = join(config, "link");
      const { symlink } = await import("node:fs/promises");
      await symlink(real, link);
      process.env.CLAUDECONFIGDIR = link;
      assert.equal(claudeOwnsSessionPath(join(link, "projects", "sess.jsonl")), true);
    });
  });

  describe("resolveSessionPath", () => {
    it("passes path-kind references through", async () => {
      const ref = { source: "herdr:claude", agent: "claude", kind: "path" as const, value: "/x/y.jsonl" };
      assert.equal(await claudeResolveSessionPath(ref), "/x/y.jsonl");
    });
    it("walks the projects root to find an id-kind reference", async () => {
      const config = await claudeStore();
      const id = "7d012817-0fb3-4810-9172-f26710238ead";
      const dir = join(config, "projects", "-encoded-project-");
      await mkdir(dir, { recursive: true });
      await writeFile(join(dir, `${id}.jsonl`), "");
      const ref = { source: "herdr:claude", agent: "claude", kind: "id" as const, value: id };
      assert.equal(await claudeResolveSessionPath(ref), join(dir, `${id}.jsonl`));
    });
    it("returns null for an unknown id without a cwd", async () => {
      await claudeStore();
      const ref = { source: "herdr:claude", agent: "claude", kind: "id" as const, value: "nope" };
      assert.equal(await claudeResolveSessionPath(ref), null);
    });
    it("predicts the not-yet-written transcript path from the pane cwd", async () => {
      const config = await claudeStore();
      const ref = { source: "herdr:claude", agent: "claude", kind: "id" as const, value: "fresh-session" };
      // Claude 2.1.228 writes the JSONL only after the first exchange, so a
      // fresh idle session must resolve to its deterministic location.
      assert.equal(
        await claudeResolveSessionPath(ref, "/home/gdezan/Dev/agents-mobile"),
        join(config, "projects", "-home-gdezan-Dev-agents-mobile", "fresh-session.jsonl"),
      );
    });
    it("matches claude's project-dir encoding for special characters", () => {
      assert.equal(claudeProjectDir("/tmp/claude enc.dir/α space"), "-tmp-claude-enc-dir---space");
      assert.equal(claudeProjectDir("/home/gdezan"), "-home-gdezan");
      assert.equal(claudeProjectDir("/home/gdezan/Dev/chronica"), "-home-gdezan-Dev-chronica");
    });
  });

  describe("control", () => {
    it("aborts with escape", async () => {
      const herdr = fakeHerdr();
      await claudeControl(herdr, { paneId: "p1", action: "abort" });
      assert.deepEqual(herdr.sent.map((c) => c.method), ["paneSendKeys"]);
    });
    it("compacts with /compact", async () => {
      const herdr = fakeHerdr();
      await claudeControl(herdr, { paneId: "p1", action: "compact" });
      assert.deepEqual(herdr.sent.map((c) => c.method), ["paneSendInput"]);
      assert.equal(herdr.sent[0].params.text, "/compact");
    });
    it("sets a model with claude's /model grammar (no provider/ prefix)", async () => {
      const herdr = fakeHerdr();
      await claudeControl(herdr, { paneId: "p1", action: "set_model", text: "claude-sonnet-4-6" });
      assert.equal(herdr.sent[0].params.text, "/model claude-sonnet-4-6");
    });
    it("strips the picker's provider prefix before /model", async () => {
      const herdr = fakeHerdr();
      await claudeControl(herdr, { paneId: "p1", action: "set_model", text: "anthropic/claude-opus-5" });
      assert.equal(herdr.sent[0].params.text, "/model claude-opus-5");
    });
    it("sets a thinking level with Claude's /effort grammar", async () => {
      const herdr = fakeHerdr();
      await claudeControl(herdr, { paneId: "p1", action: "set_thinking", text: "high" });
      assert.deepEqual(herdr.sent[0], {
        method: "paneSendInput",
        params: { pane_id: "p1", text: "/effort high", keys: ["Enter"] },
      });
    });
    it("rejects unsupported thinking levels", async () => {
      const herdr = fakeHerdr();
      await assert.rejects(
        claudeControl(herdr, { paneId: "p1", action: "set_thinking", text: "off" }),
        /valid effort level is required/,
      );
      assert.deepEqual(herdr.sent, []);
      await assert.rejects(
        claudeControl(herdr, {
          paneId: "p1",
          action: "set_thinking",
          text: "xhigh",
          model: "anthropic/claude-haiku-4-5-20251001",
        }),
      );
    });

    it("retries the initial prompt until the pane shows it, then stops", async () => {
      // Simulates the live drop: prompts sent in claude's first ~2s vanish,
      // so delivery must verify the pane text and re-send with backoff.
      let reads = 0;
      const base = fakeHerdr();
      const herdr = {
        ...base,
        agentRead: async () => {
          reads += 1;
          return agentReadResponse(reads >= 3 ? "\u276f Reply with exactly: HI there" : "");
        },
      };
      await claudeDeliverInitialPrompt(herdr, "p1", "Reply with exactly: HI there", [1, 1]);
      const prompts = base.sent.filter((c) => c.method === "agentPrompt");
      assert.equal(prompts.length, 2, "attempt 1 re-reads before re-sending, so the drop is recovered with one retry");
      assert.equal(reads, 3, "pre-send check + post-send verification per attempt");
    });
    it("does not re-send when the pre-check already shows the marker", async () => {
      // A prompt that landed but whose echo scrolled away must never be sent
      // twice: the pre-send read on attempt 1 sees the marker and returns.
      let reads = 0;
      const base = fakeHerdr();
      const herdr = {
        ...base,
        agentRead: async () => {
          reads += 1;
          // First read (post-send verification) misses the echo; the pre-send
          // read on the next attempt sees it in a wider window.
          return agentReadResponse(reads >= 2 ? "\u276f Hello there" : "");
        },
      };
      await claudeDeliverInitialPrompt(herdr, "p1", "Hello there", [1, 1]);
      const prompts = base.sent.filter((c) => c.method === "agentPrompt");
      assert.equal(prompts.length, 1, "marker visible pre-send → no second agentPrompt");
    });
    it("gives up rather than blind-resend when the pane is unreadable", async () => {
      const base = fakeHerdr();
      const herdr = {
        ...base,
        agentRead: async () => {
          throw new Error("pane gone");
        },
      };
      await claudeDeliverInitialPrompt(herdr, "p1", "Hello there", [1, 1]);
      const prompts = base.sent.filter((c) => c.method === "agentPrompt");
      assert.equal(prompts.length, 1, "unverifiable pane → never blind-resend");
    });
    it("stops after the first prompt when delivery succeeds", async () => {
      const base = fakeHerdr();
      const herdr = {
        ...base,
        agentRead: async () => agentReadResponse("\u276f Hello there"),
      };
      await claudeDeliverInitialPrompt(herdr, "p1", "Hello there", [1, 1]);
      const prompts = base.sent.filter((c) => c.method === "agentPrompt");
      assert.equal(prompts.length, 1);
    });
    it("rejects control characters in set_model", async () => {
      const herdr = fakeHerdr();
      await assert.rejects(
        claudeControl(herdr, { paneId: "p1", action: "set_model", text: "claude-sonnet-4-6\n/quit" }),
        /valid model is required/,
      );
      await assert.rejects(
        claudeControl(herdr, { paneId: "p1", action: "set_model", text: "claude-sonnet-4-6\u0007" }),
        /valid model is required/,
      );
    });
    it("rejects verbs claude does not support", async () => {
      const herdr = fakeHerdr();
      for (const action of ["rename", "fork", "retry"] as const) {
        await assert.rejects(claudeControl(herdr, { paneId: "p1", action }), /unsupported control action for claude/);
      }
    });
    it("advertises only its real capabilities", () => {
      assert.deepEqual([...claudeBackend.capabilities], ["abort", "compact", "close", "set_model", "set_thinking"]);
      assert.equal(claudeBackend.hasModelCatalog, true);
      assert.equal(claudeBackend.hasSlashCommands, true);
    });
  });

  /**
   * The exact model read. Claude stamps the model on every assistant record,
   * so the tail window normally answers it without touching the rest of the
   * file — but a session that ends in a long run of user records has to keep
   * finding the older one.
   */
  describe("model state", () => {
    async function stateOf(lines: unknown[]): Promise<string | null> {
      const dir = await mkdtemp(join(tmpdir(), "scoutr-claude-state-"));
      const path = join(dir, "session.jsonl");
      await writeFile(path, `${lines.map((line) => JSON.stringify(line)).join("\n")}\n`);
      return (await claudeReadTranscriptState(path)).model;
    }

    it("reads the newest model from the tail window", async () => {
      const model = await stateOf([
        userRecord("u1", "hello"),
        assistantRecord("a1", [{ type: "text", text: "hi" }]),
      ]);
      assert.equal(model, "claude-sonnet-4-6");
    });

    it("still finds a model the tail window cannot reach", async () => {
      // 64 KiB of user records after the only assistant one push it out of
      // the tail window entirely.
      const filler = Array.from({ length: 80 }, (_, i) => userRecord(`f${i}`, "y".repeat(1000)));
      const model = await stateOf([
        assistantRecord("a1", [{ type: "text", text: "hi" }]),
        ...filler,
      ]);
      assert.equal(model, "claude-sonnet-4-6");
    });
  });

  describe("AskUserQuestion", () => {
    /** The shape Claude Code writes: the call carries the questions... */
    function askRecords(questions: unknown[], answers?: Record<string, string>): string {
      const lines = [
        assistantRecord("a1", [
          { type: "tool_use", id: "toolu_ask", name: "AskUserQuestion", input: { questions } },
        ]),
      ];
      if (answers) {
        const answerRecord = {
          type: "user",
          uuid: "u2",
          parentUuid: "a1",
          timestamp: "2026-01-01T00:00:03.000Z",
          message: {
            role: "user",
            content: [
              { type: "tool_result", tool_use_id: "toolu_ask", content: "Your questions have been answered." },
            ],
          },
          toolUseResult: { questions, answers, annotations: {} },
        };
        lines.push(answerRecord);
      }
      return lines.map((line) => JSON.stringify(line)).join("\n");
    }

    const colorQuestion = {
      question: "Which color?",
      header: "Color",
      multiSelect: false,
      options: [
        { label: "Red", description: "Warm" },
        { label: "Green", description: "Calm" },
      ],
    };
    const sizeQuestion = {
      question: "Which sizes?",
      header: "Size",
      multiSelect: true,
      options: [{ label: "Small" }, { label: "Large" }, { label: "Extra, roomy" }],
    };

    it("reads an unanswered ask as one card per question", () => {
      const transcript = parseClaudeTranscript(askRecords([colorQuestion, sizeQuestion]));
      const questions = claudeExtractQuestions(transcript);
      assert.equal(questions.length, 2);
      assert.deepEqual(questions[0], {
        id: "toolu_ask#0",
        callId: "toolu_ask",
        entryId: "a1",
        question: "Which color?",
        header: "Color",
        options: [
          { label: "Red", description: "Warm" },
          { label: "Green", description: "Calm" },
        ],
        multiSelect: false,
        answered: false,
        answerText: null,
        selected: [],
        timestamp: "2026-01-01T00:00:02.000Z",
      });
      assert.equal(questions[1]?.multiSelect, true);
      assert.deepEqual(questions[1]?.options[0], { label: "Small", description: "" });
    });

    it("pairs answers by question text, the only key Claude records", () => {
      const transcript = parseClaudeTranscript(
        askRecords([colorQuestion, sizeQuestion], {
          "Which color?": "Green",
          "Which sizes?": "Small, Large",
        }),
      );
      const questions = claudeExtractQuestions(transcript);
      assert.equal(questions[0]?.answered, true);
      assert.equal(questions[0]?.answerText, "Green");
      assert.deepEqual(questions[0]?.selected, []);
      assert.equal(questions[1]?.answered, true);
      assert.equal(questions[1]?.answerText, null);
      assert.deepEqual(questions[1]?.selected, ["Small", "Large"]);
    });

    it("splits a multi-select answer around a label that contains a comma", () => {
      const transcript = parseClaudeTranscript(
        askRecords([sizeQuestion], { "Which sizes?": "Small, Extra, roomy" }),
      );
      const questions = claudeExtractQuestions(transcript);
      assert.deepEqual(questions[0]?.selected, ["Small", "Extra, roomy"]);
    });

    it("keeps a custom answer that matches no option", () => {
      const transcript = parseClaudeTranscript(askRecords([colorQuestion], { "Which color?": "Teal" }));
      const questions = claudeExtractQuestions(transcript);
      assert.equal(questions[0]?.answerText, "Teal");
      assert.equal(questions[0]?.answered, true);
    });

    it("keeps only the answers out of toolUseResult, never the whole result", () => {
      const bulky = JSON.stringify({
        type: "user",
        uuid: "u3",
        parentUuid: null,
        timestamp: "2026-01-01T00:00:04.000Z",
        message: { role: "user", content: [{ type: "tool_result", tool_use_id: "toolu_read", content: "ok" }] },
        toolUseResult: { file: { content: "x".repeat(5000) } },
      });
      const transcript = parseClaudeTranscript(bulky);
      assert.equal(transcript.entries[0]?.details, undefined);
    });
  });

  describe("transcript parsing", () => {
    it("maps user prompts, tool results, and assistant blocks", () => {
      const lines = [
        userRecord("u1", "fix the billing math"),
        assistantRecord("a1", [
          { type: "text", text: "Let me look." },
          { type: "tool_use", id: "toolu_1", name: "Bash", input: { command: "git status" } },
        ]),
        toolResultRecord("u2", "toolu_1"),
      ];
      const transcript = parseClaudeTranscript(lines.map((l) => JSON.stringify(l)).join("\n"));
      assert.equal(transcript.entries.length, 3);
      assert.deepEqual(transcript.entries[0], {
        entryId: "u1",
        parentId: null,
        timestamp: "2026-01-01T00:00:00.000Z",
        role: "user",
        content: [{ type: "text", text: "fix the billing math" }],
      });
      const toolUse = transcript.entries[1];
      assert.equal(toolUse.role, "assistant");
      assert.equal(toolUse.model, "claude-sonnet-4-6");
      assert.equal(toolUse.stopReason, "end_turn");
      assert.deepEqual(toolUse.usage, { input: 10, output: 20 });
      const call = toolUse.content.find(isToolCallBlock);
      assert.ok(call);
      assert.deepEqual(call, { type: "toolCall", id: "toolu_1", name: "Bash", arguments: { command: "git status" } });
      const result = transcript.entries[2];
      assert.equal(result.role, "toolResult");
      assert.equal(result.toolCallId, "toolu_1");
    });
    it("skips non-conversation records and tolerates garbage lines", () => {
      const lines = [
        { type: "mode", value: "default" },
        { type: "system", subtype: "pre_tool_use", content: "notice" },
        { type: "file-history-snapshot", fileHistorySnapshotId: "fhs_1" },
        { type: "queue-operation", operation: "enqueue" },
        "this is not json",
        userRecord("u1", "hello"),
      ];
      const transcript = parseClaudeTranscript(lines.map((l) => JSON.stringify(l)).join("\n"));
      assert.equal(transcript.entries.length, 1);
      assert.equal(transcript.entries[0].entryId, "u1");
    });
    it("takes the title from aiTitle and custom-title records", () => {
      const lines = [
        userRecord("u1", "hello", { aiTitle: "Docs refresh" }),
        { type: "custom-title", customTitle: "Manual title" },
      ];
      const transcript = parseClaudeTranscript(lines.map((l) => JSON.stringify(l)).join("\n"));
      // custom-title arrives last and wins; the parser keeps the newest title.
      assert.equal(transcript.title, "Manual title");
    });
    it("metadataOnly skips entries but keeps session identity", () => {
      const lines = [
        userRecord("u1", "hello", { aiTitle: "AI title" }),
        assistantRecord("a1", [{ type: "text", text: "ok" }]),
      ];
      const transcript = parseClaudeTranscript(lines.map((l) => JSON.stringify(l)).join("\n"), { metadataOnly: true });
      assert.equal(transcript.entries.length, 0);
      assert.equal(transcript.id, "9f1c2d3e-0000-0000-0000-000000000001");
      assert.equal(transcript.cwd, "/work/alpha");
      assert.equal(transcript.model, "claude-sonnet-4-6");
      assert.equal(transcript.title, "AI title");
    });
  });
});
