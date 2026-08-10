import test from "node:test";
import assert from "node:assert/strict";
import { extractQuestions, sanitizeAnswerText } from "../src/questions.js";
import type { PiMessageEntry } from "../src/pi/session.js";

const TOOL_CALL_ID = "call_abc123|fc_xyz789";

function assistantWithQuestion(callId: string = TOOL_CALL_ID, args: unknown): PiMessageEntry {
  return {
    entryId: "msg_ask",
    parentId: null,
    timestamp: "2026-08-10T10:00:00.000Z",
    role: "assistant",
    content: [{ type: "toolCall", id: callId, name: "ask_user_question", arguments: args }],
    stopReason: "toolUse",
  };
}

function toolResultWithAnswers(callId: string, answers: unknown[]): PiMessageEntry {
  return {
    entryId: "msg_answer",
    parentId: null,
    timestamp: "2026-08-10T10:00:10.000Z",
    role: "toolResult",
    toolCallId: callId,
    toolName: "ask_user_question",
    content: [{ type: "text", text: "User has answered your questions" }],
    details: { answers, cancelled: false },
  };
}

test("extracts a pending single-choice question from the tool call", () => {
  const questions = extractQuestions([
    assistantWithQuestion(TOOL_CALL_ID, {
      questions: [
        {
          question: "Where should the papercut live?",
          header: "Scope",
          options: [
            { label: "This repo", description: "Handle it here." },
            { label: "Skip it", description: "Leave it open." },
          ],
        },
      ],
    }),
  ]);
  assert.equal(questions.length, 1);
  assert.equal(questions[0].question, "Where should the papercut live?");
  assert.equal(questions[0].header, "Scope");
  assert.equal(questions[0].multiSelect, false);
  assert.equal(questions[0].options.length, 2);
  assert.equal(questions[0].options[1].label, "Skip it");
  assert.equal(questions[0].answered, false);
  assert.equal(questions[0].answerText, null);
});

test("marks the question answered from the matching tool result", () => {
  const questions = extractQuestions([
    assistantWithQuestion(TOOL_CALL_ID, {
      questions: [{ question: "Proceed?", header: "Confirm", options: [{ label: "Yes" }, { label: "No" }] }],
    }),
    toolResultWithAnswers(TOOL_CALL_ID, [
      { questionIndex: 0, question: "Proceed?", kind: "option", answer: "Yes" },
    ]),
  ]);
  assert.equal(questions.length, 1);
  assert.equal(questions[0].answered, true);
  assert.equal(questions[0].answerText, "Yes");
});

test("multi-select answers carry selected labels", () => {
  const questions = extractQuestions([
    assistantWithQuestion(TOOL_CALL_ID, {
      questions: [
        {
          question: "Pick areas",
          header: "Areas",
          multiSelect: true,
          options: [{ label: "Auth" }, { label: "Billing" }, { label: "Docs" }],
        },
      ],
    }),
    toolResultWithAnswers(TOOL_CALL_ID, [
      { questionIndex: 0, question: "Pick areas", kind: "multi", answer: null, selected: ["Auth", "Docs"] },
    ]),
  ]);
  assert.equal(questions[0].multiSelect, true);
  assert.equal(questions[0].answered, true);
  assert.equal(questions[0].answerText, null);
  assert.deepEqual(questions[0].selected, ["Auth", "Docs"]);
});

test("unanswered questions stay pending while later questions are answered", () => {
  const questions = extractQuestions([
    assistantWithQuestion("call_one", {
      questions: [{ question: "First?", header: "Q1", options: [{ label: "A" }, { label: "B" }] }],
    }),
    assistantWithQuestion("call_two", {
      questions: [{ question: "Second?", header: "Q2", options: [{ label: "C" }, { label: "D" }] }],
    }),
    toolResultWithAnswers("call_two", [
      { questionIndex: 0, question: "Second?", kind: "option", answer: "C" },
    ]),
  ]);
  assert.equal(questions.length, 2);
  assert.equal(questions[0].answered, false);
  assert.equal(questions[1].answered, true);
  assert.equal(questions[1].answerText, "C");
});

test("multiple questions in one call align by index", () => {
  const questions = extractQuestions([
    assistantWithQuestion(TOOL_CALL_ID, {
      questions: [
        { question: "One?", header: "1", options: [{ label: "A" }, { label: "B" }] },
        { question: "Two?", header: "2", options: [{ label: "C" }, { label: "D" }] },
      ],
    }),
    toolResultWithAnswers(TOOL_CALL_ID, [
      { questionIndex: 0, question: "One?", kind: "option", answer: "B" },
      { questionIndex: 1, question: "Two?", kind: "option", answer: "D" },
    ]),
  ]);
  assert.equal(questions.length, 2);
  assert.equal(questions[0].answerText, "B");
  assert.equal(questions[1].answerText, "D");
});

test("custom (free-text) answers surface as answerText", () => {
  const questions = extractQuestions([
    assistantWithQuestion(TOOL_CALL_ID, {
      questions: [{ question: "What path?", header: "Path", options: [{ label: "A" }, { label: "B" }] }],
    }),
    toolResultWithAnswers(TOOL_CALL_ID, [
      { questionIndex: 0, question: "What path?", kind: "custom", answer: "~/Dev/ibovasco" },
    ]),
  ]);
  assert.equal(questions[0].answered, true);
  assert.equal(questions[0].answerText, "~/Dev/ibovasco");
});

test("cancelled questionnaires stay pending (no answers)", () => {
  const questions = extractQuestions([
    assistantWithQuestion(TOOL_CALL_ID, {
      questions: [{ question: "Q?", header: "Q", options: [{ label: "A" }, { label: "B" }] }],
    }),
    toolResultWithAnswers(TOOL_CALL_ID, []),
  ]);
  assert.equal(questions[0].answered, false);
});

test("malformed arguments and non-question tools are ignored", () => {
  const questions = extractQuestions([
    assistantWithQuestion("call_x", { notQuestions: true }),
    {
      entryId: "msg_bash",
      parentId: null,
      timestamp: "2026-08-10T10:00:00.000Z",
      role: "assistant",
      content: [{ type: "toolCall", id: "call_bash", name: "bash", arguments: { command: "ls" } }],
    },
  ]);
  assert.equal(questions.length, 0);
});

test("sanitizeAnswerText collapses newlines, strips control chars, and caps length", () => {
  assert.equal(sanitizeAnswerText("  yes  "), "yes");
  assert.equal(sanitizeAnswerText("line1\nline2"), "line1 line2");
  assert.equal(sanitizeAnswerText("a\u0000b\u001fc"), "abc");
  const long = "x".repeat(5000);
  assert.equal(sanitizeAnswerText(long).length, 4000);
  assert.equal(sanitizeAnswerText("\n\n  \n"), "");
});
