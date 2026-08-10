import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readSession } from "../src/server.js";

const TOOL_CALL_ID = "call_integration|fc_0001";

const SESSION_LINES = [
  JSON.stringify({
    type: "session",
    version: 3,
    id: "int-session",
    timestamp: "2026-08-10T10:00:00.000Z",
    cwd: "/work/int",
  }),
  JSON.stringify({
    type: "message",
    id: "msg_1",
    parentId: null,
    timestamp: "2026-08-10T10:00:01.000Z",
    message: {
      role: "assistant",
      content: [
        {
          type: "toolCall",
          id: TOOL_CALL_ID,
          name: "ask_user_question",
          arguments: {
            questions: [
              {
                question: "Proceed with the migration?",
                header: "Migration",
                options: [
                  { label: "Yes", description: "Start now" },
                  { label: "No", description: "Hold off" },
                ],
              },
            ],
          },
        },
      ],
      stopReason: "toolUse",
    },
  }),
  JSON.stringify({
    type: "message",
    id: "msg_2",
    parentId: null,
    timestamp: "2026-08-10T10:00:05.000Z",
    message: {
      role: "toolResult",
      toolCallId: TOOL_CALL_ID,
      toolName: "ask_user_question",
      content: [{ type: "text", text: "User has answered your questions" }],
      details: {
        answers: [{ questionIndex: 0, question: "Proceed with the migration?", kind: "option", answer: "Yes" }],
        cancelled: false,
      },
      isError: false,
    },
  }),
];

test("readSession surfaces structured questions derived from session events", async () => {
  const agentRoot = await mkdtemp(join(tmpdir(), "cockpit-agent-root-"));
  await mkdir(join(agentRoot, "int-project"));
  const path = join(agentRoot, "int-project", "session.jsonl");
  await writeFile(path, SESSION_LINES.join("\n"));

  const previous = process.env.PI_CODING_AGENT_DIR;
  process.env.PI_CODING_AGENT_DIR = agentRoot;
  try {
    const result = await readSession(path, null);
    assert.equal(result.exists, true);
    assert.equal(result.entries.length, 2);
    assert.equal(result.questions.length, 1);

    const question = result.questions[0];
    assert.equal(question.question, "Proceed with the migration?");
    assert.equal(question.header, "Migration");
    assert.equal(question.options.length, 2);
    assert.equal(question.answered, true);
    assert.equal(question.answerText, "Yes");
  } finally {
    if (previous === undefined) delete process.env.PI_CODING_AGENT_DIR;
    else process.env.PI_CODING_AGENT_DIR = previous;
  }
});

test("readSession keeps pending questions pending when unanswered", async () => {
  const agentRoot = await mkdtemp(join(tmpdir(), "cockpit-agent-root-"));
  await mkdir(join(agentRoot, "int-project"));
  const path = join(agentRoot, "int-project", "session.jsonl");
  await writeFile(path, SESSION_LINES.slice(0, 2).join("\n"));

  const previous = process.env.PI_CODING_AGENT_DIR;
  process.env.PI_CODING_AGENT_DIR = agentRoot;
  try {
    const result = await readSession(path, null);
    assert.equal(result.questions.length, 1);
    assert.equal(result.questions[0].answered, false);
    assert.equal(result.questions[0].answerText, null);
  } finally {
    if (previous === undefined) delete process.env.PI_CODING_AGENT_DIR;
    else process.env.PI_CODING_AGENT_DIR = previous;
  }
});
