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
import { claudeDeliverInitialPrompt } from "../src/agents/claude/index.js";
import { fakeHerdr } from "./support/fake-herdr.js";

/** CLAUDECONFIGDIR honing: point the claude adapter at a temp store. */
async function claudeStore(): Promise<string> {
  const config = await mkdtemp(join(tmpdir(), "cockpit-claude-"));
  process.env.CLAUDECONFIGDIR = config;
  await mkdir(join(config, "projects"), { recursive: true });
  return config;
}

function userRecord(uuid: string, content: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
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

function toolResultRecord(uuid: string, toolUseId: string): Record<string, unknown> {
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

function assistantRecord(uuid: string, blocks: unknown[]): Record<string, unknown> {
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

describe("claude adapter", () => {
  describe("launch", () => {
    it("launches a plain claude session", () => {
      assert.equal(claudeLaunchCommand({}), "claude");
    });
    it("shell-quotes model and name", () => {
      assert.equal(claudeLaunchCommand({ model: "claude-sonnet-4-6", name: "a b'c" }), "claude --model 'claude-sonnet-4-6' --name 'a b'\\''c'");
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
      const config = await claudeStore();
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
    it("retries the initial prompt until the pane shows it, then stops", async () => {
      // Simulates the live drop: prompts sent in claude's first ~2s vanish,
      // so delivery must verify the pane text and re-send with backoff.
      let reads = 0;
      const base = fakeHerdr();
      const herdr = {
        ...base,
        agentRead: async () => {
          reads += 1;
          return { read: { text: reads >= 3 ? "\u276f Reply with exactly: HI there" : "" } } as never;
        },
      };
      await claudeDeliverInitialPrompt(herdr as never, "p1", "Reply with exactly: HI there", [1, 1]);
      const prompts = base.sent.filter((c) => c.method === "agentPrompt");
      assert.equal(prompts.length, 3, "one prompt per attempt until the marker is visible");
      assert.equal(reads, 3);
    });
    it("stops after the first prompt when delivery succeeds", async () => {
      const base = fakeHerdr();
      const herdr = {
        ...base,
        agentRead: async () => ({ read: { text: "\u276f Hello there" } }) as never,
      };
      await claudeDeliverInitialPrompt(herdr as never, "p1", "Hello there", [1, 1]);
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
      for (const action of ["set_thinking", "rename", "fork", "retry"] as const) {
        await assert.rejects(claudeControl(herdr, { paneId: "p1", action }), /unsupported control action for claude/);
      }
    });
    it("advertises only its real capabilities", () => {
      assert.deepEqual([...claudeBackend.capabilities], ["abort", "compact", "close", "set_model"]);
      assert.equal(claudeBackend.hasModelCatalog, false);
      assert.equal(claudeBackend.hasSlashCommands, false);
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
      const call = toolUse.content.find((b) => b.type === "toolCall") as { type: string; id: string; name: string; arguments: unknown };
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
