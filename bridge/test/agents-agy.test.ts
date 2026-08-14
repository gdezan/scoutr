import assert from "node:assert/strict";
import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import {
  agyBackend,
  agyControl,
  agyLaunchCommand,
  agyOwnsSessionPath,
  agyResolveSessionPath,
  agyResumeCommand,
  agyAnswerQuestion,
} from "../src/agents/agy/index.js";
import { parseAgyTranscript, extractUserPrompt } from "../src/agents/agy/transcript.js";
import { readAgyModelsCatalog } from "../src/agents/agy/models.js";
import { readAgyCommandsCatalog } from "../src/agents/agy/commands.js";
import { extractAgyQuestions } from "../src/agents/agy/questions.js";
import { backendFor, getBackendOrNull, knownBackends } from "../src/agents/registry.js";
import { fakeHerdr } from "./support/fake-herdr.js";

async function agyStore(): Promise<string> {
  const config = await mkdtemp(join(tmpdir(), "scoutr-agy-"));
  process.env.ANTIGRAVITY_CONFIG_DIR = config;
  await mkdir(join(config, "brain"), { recursive: true });
  return config;
}

describe("agy adapter", () => {
  describe("launch", () => {
    it("launches a plain agy session", () => {
      assert.equal(agyLaunchCommand({}), "agy");
    });

    it("shell-quotes model and effort level", () => {
      assert.equal(
        agyLaunchCommand({ model: "gemini-3.7-flash-high", thinkingLevel: "high" }),
        "agy --model 'gemini-3.7-flash-high' --effort 'high'",
      );
    });
  });

  describe("resume", () => {
    it("resumes by conversation id", () => {
      assert.equal(
        agyResumeCommand("/path/to/brain/conv-uuid-123/.system_generated/logs/transcript.jsonl", "resume"),
        "agy --conversation 'conv-uuid-123'",
      );
    });

    it("rejects fork mode", () => {
      assert.throws(() => agyResumeCommand("conv-uuid", "fork"), /fork-at-path/);
    });
  });

  describe("path containment and resolution", () => {
    it("claims session paths in the brain store", async () => {
      const store = await agyStore();
      const brainDir = join(store, "brain", "conv-1", ".system_generated", "logs");
      await mkdir(brainDir, { recursive: true });
      const transcriptPath = join(brainDir, "transcript.jsonl");
      await writeFile(transcriptPath, "");

      assert.equal(agyOwnsSessionPath(transcriptPath), true);
      assert.equal(agyOwnsSessionPath(join(store, "other.jsonl")), false);
    });

    it("resolves id-kind and path-kind references", async () => {
      const store = await agyStore();
      const resolved = await agyResolveSessionPath({
        agent: "agy",
        kind: "id",
        source: "hook",
        value: "conv-456",
      });
      assert.equal(
        resolved,
        join(store, "brain", "conv-456", ".system_generated", "logs", "transcript.jsonl"),
      );

      const pathResolved = await agyResolveSessionPath({
        agent: "agy",
        kind: "path",
        source: "hook",
        value: "/explicit/path.jsonl",
      });
      assert.equal(pathResolved, "/explicit/path.jsonl");
    });
  });

  describe("transcript parsing", () => {
    it("extracts user prompts and strips tags", () => {
      const content = `<USER_REQUEST>\nFix the broken build\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\nTime: 12:00\n</ADDITIONAL_METADATA>`;
      assert.equal(extractUserPrompt(content), "Fix the broken build");
    });

    it("parses user turns, tool calls, and model responses", () => {
      const jsonl = [
        JSON.stringify({
          step_index: 0,
          source: "USER_EXPLICIT",
          type: "USER_INPUT",
          created_at: "2026-01-01T00:00:00.000Z",
          content: "<USER_REQUEST>\nDeploy website\n</USER_REQUEST>\n<USER_SETTINGS_CHANGE>\nThe user changed setting `Model Selection` from None to Gemini 3.7 Flash (High).\n</USER_SETTINGS_CHANGE>",
        }),
        JSON.stringify({
          step_index: 1,
          source: "MODEL",
          type: "PLANNER_RESPONSE",
          created_at: "2026-01-01T00:00:01.000Z",
          thinking: "Planning deployment...",
          content: "I will now run the build command.",
          tool_calls: [
            {
              name: "run_command",
              args: { CommandLine: "npm run build", Cwd: "/work/project" },
              id: "call-1",
            },
          ],
        }),
        JSON.stringify({
          step_index: 2,
          source: "MODEL",
          type: "RUN_COMMAND",
          created_at: "2026-01-01T00:00:02.000Z",
          content: "Build successful",
        }),
      ].join("\n");

      const transcript = parseAgyTranscript(jsonl);
      assert.equal(transcript.preview, "Deploy website");
      assert.equal(transcript.model, "gemini-3.7-flash-high");
      assert.equal(transcript.thinkingLevel, "high");
      assert.equal(transcript.cwd, "/work/project");
      assert.equal(transcript.entries.length, 3);
      assert.equal(transcript.entries[0]?.role, "user");
      assert.equal(transcript.entries[1]?.role, "assistant");
      assert.equal(transcript.entries[2]?.role, "toolResult");
      assert.equal(transcript.lastEntryId, "step-2");
    });

    it("supports metadataOnly and tail options", () => {
      const jsonl = [
        JSON.stringify({
          step_index: 0,
          source: "USER_EXPLICIT",
          type: "USER_INPUT",
          created_at: "2026-01-01T00:00:00.000Z",
          content: "<USER_REQUEST>First prompt</USER_REQUEST>",
        }),
        JSON.stringify({
          step_index: 1,
          source: "USER_EXPLICIT",
          type: "USER_INPUT",
          created_at: "2026-01-01T00:00:01.000Z",
          content: "<USER_REQUEST>Second prompt</USER_REQUEST>",
        }),
      ].join("\n");

      const meta = parseAgyTranscript(jsonl, { metadataOnly: true });
      assert.equal(meta.entries.length, 0);
      assert.equal(meta.preview, "First prompt");

      const tail = parseAgyTranscript(jsonl, { tail: 1 });
      assert.equal(tail.entries.length, 1);
      assert.equal(tail.entries[0]?.entryId, "step-1");
    });
  });

  describe("questions", () => {
    it("extracts ask_question tool calls and answers", () => {
      const entries = [
        {
          entryId: "step-1",
          parentId: null,
          timestamp: "2026-01-01T00:00:00.000Z",
          role: "assistant",
          content: [
            {
              type: "toolCall",
              id: "q-call-1",
              name: "ask_question",
              arguments: {
                questions: [
                  {
                    question: "Which database to use?",
                    header: "Database Choice",
                    options: ["PostgreSQL", "SQLite"],
                    is_multi_select: false,
                  },
                ],
              },
            },
          ],
        },
        {
          entryId: "step-2",
          parentId: "step-1",
          timestamp: "2026-01-01T00:00:01.000Z",
          role: "toolResult",
          toolCallId: "q-call-1",
          toolName: "ask_question",
          content: [{ type: "text", text: "A1: PostgreSQL" }],
        },
      ];

      const questions = extractAgyQuestions(entries as any);
      assert.equal(questions.length, 1);
      assert.equal(questions[0]?.question, "Which database to use?");
      assert.equal(questions[0]?.options.length, 2);
      assert.equal(questions[0]?.answered, true);
      assert.equal(questions[0]?.answerText, "PostgreSQL");
    });

    it("answers questions via herdr", async () => {
      const port = fakeHerdr();
      await agyAnswerQuestion(port, {
        paneId: "p1",
        question: null,
        group: [],
        progress: null,
        text: "Option A\nNext",
        selectedLabels: [],
      });
      assert.deepEqual(port.sent, [
        { method: "paneSendText", params: { pane_id: "p1", text: "Option A Next" } },
        { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
      ]);
    });

    it("delivers an option pick as the option label", async () => {
      const port = fakeHerdr();
      await agyAnswerQuestion(port, {
        paneId: "p1",
        question: null,
        group: [],
        progress: null,
        text: "",
        selectedLabels: ["Option A", "Option B"],
      });
      assert.deepEqual(port.sent, [
        { method: "paneSendText", params: { pane_id: "p1", text: "Option A, Option B" } },
        { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
      ]);
    });
  });

  describe("control", () => {
    it("handles abort, compact, set_model, set_thinking, and close", async () => {
      const port = fakeHerdr();
      await agyControl(port, { paneId: "p1", action: "abort" });
      await agyControl(port, { paneId: "p1", action: "compact" });
      await agyControl(port, { paneId: "p1", action: "set_model", text: "gemini-3.7-flash-high" });
      await agyControl(port, { paneId: "p1", action: "set_thinking", text: "high" });

      assert.deepEqual(port.sent, [
        { method: "paneSendKeys", params: { pane_id: "p1", keys: ["escape"] } },
        { method: "paneSendInput", params: { pane_id: "p1", text: "/compact", keys: ["Enter"] } },
        { method: "paneSendInput", params: { pane_id: "p1", text: "/model gemini-3.7-flash-high", keys: ["Enter"] } },
        { method: "paneSendInput", params: { pane_id: "p1", text: "/effort high", keys: ["Enter"] } },
      ]);
    });
  });

  describe("models & commands catalog", () => {
    it("provides models grouped by provider", () => {
      const catalog = readAgyModelsCatalog();
      assert.ok(catalog.providers.length >= 2);
      const google = catalog.providers.find((p) => p.name === "google");
      assert.ok(google);
      assert.ok(google?.models.some((m) => m.id === "gemini-3.7-flash-high"));
    });

    it("provides built-in commands and skills", async () => {
      const catalog = await readAgyCommandsCatalog();
      assert.ok(catalog.commands.some((c) => c.name === "goal"));
      assert.ok(catalog.commands.some((c) => c.name === "plan"));
      assert.ok(catalog.commands.some((c) => c.name === "schedule"));
    });
  });

  describe("registry integration", () => {
    it("looks up agy backend by id and aliases", () => {
      assert.equal(backendFor("agy").id, "agy");
      assert.equal(backendFor("gemini").id, "agy");
      assert.equal(backendFor("antigravity_cli").id, "agy");
      assert.equal(getBackendOrNull("unknown"), null);
    });

    it("deduplicates knownBackends", () => {
      const backends = knownBackends();
      const ids = backends.map((b) => b.id);
      assert.deepEqual(ids, ["pi", "claude", "agy"]);
    });
  });
});
