import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { answerAsk, dismissAsk, type AnswerDeps } from "../src/answers.js";
import { claudeAskPlan } from "../src/agents/claude/questionnaire.js";
import { claudeAnswerAsk } from "../src/agents/claude/index.js";
import { piAskPlan } from "../src/agents/pi/questionnaire.js";
import { piAnswerAsk } from "../src/agents/pi/index.js";
import { agyAnswerAsk } from "../src/agents/agy/index.js";
import type { AskAnswer } from "../src/agents/types.js";
import type { QuestionEntry } from "../src/questions.js";
import { fakeHerdr } from "./support/fake-herdr.js";
import type { PaneInfo, SessionSnapshot } from "../src/herdr/types.js";

function question(index: number, overrides: Partial<QuestionEntry> = {}): QuestionEntry {
  return {
    id: `call#${index}`,
    callId: "call",
    entryId: "e1",
    question: `Question ${index}`,
    header: `H${index}`,
    options: [
      { label: "Alpha", description: "" },
      { label: "Beta", description: "" },
      { label: "Gamma", description: "" },
    ],
    multiSelect: false,
    answered: false,
    answerText: null,
    selected: [],
    timestamp: "2026-08-14T00:00:00Z",
    ...overrides,
  };
}

function pick(index: number, ...labels: string[]): AskAnswer {
  return { questionId: `call#${index}`, text: "", selectedLabels: labels };
}

function typed(index: number, text: string): AskAnswer {
  return { questionId: `call#${index}`, text, selectedLabels: [] };
}

/** The step stream as a compact string, so a plan reads like a keystroke log. */
function steps(plan: Array<{ kind: string; value: string }>): string[] {
  return plan.map((step) => (step.kind === "key" ? step.value : `"${step.value}"`));
}

describe("claude questionnaire grammar", () => {
  test("a lone single-select question is answered by its option digit alone", () => {
    const q = question(0);
    // No submit tab exists for a lone single-select: the pick submits the ask.
    assert.deepEqual(steps(claudeAskPlan([q], [pick(0, "Beta")])), ['"2"']);
  });

  test("a lone custom answer types into the entry after the options, then Enter", () => {
    const q = question(0);
    assert.deepEqual(steps(claudeAskPlan([q], [typed(0, "Delta")])), ['"4"', '"Delta"', "Enter"]);
  });

  test("a multi-question ask needs no tab walk: answering advances the strip", () => {
    const group = [question(0), question(1)];
    // One digit per question in ask order, then a single Enter on the review
    // tab the last answer landed on. No Left/Right anywhere.
    assert.deepEqual(steps(claudeAskPlan(group, [pick(0, "Alpha"), pick(1, "Gamma")])), [
      '"1"',
      '"3"',
      "Enter",
    ]);
  });

  test("answers are matched by question id, not by the order they arrive in", () => {
    const group = [question(0), question(1)];
    const reversed = [pick(1, "Gamma"), pick(0, "Alpha")];
    assert.deepEqual(steps(claudeAskPlan(group, reversed)), ['"1"', '"3"', "Enter"]);
  });

  test("multi-select toggles each option then submits from the row under the list", () => {
    const q = question(0, { multiSelect: true });
    // Three options plus "Type something" separate the first row from Submit;
    // a lone multi-select still has a review tab, hence the trailing Enter.
    assert.deepEqual(steps(claudeAskPlan([q], [pick(0, "Alpha", "Gamma")])), [
      '"1"',
      '"3"',
      "Down",
      "Down",
      "Down",
      "Down",
      "Enter",
      "Enter",
    ]);
  });

  test("multi-select custom text walks the cursor to the text field (no digit focuses it)", () => {
    const q = question(0, { multiSelect: true });
    assert.deepEqual(steps(claudeAskPlan([q], [typed(0, "Delta")])), [
      "Down",
      "Down",
      "Down",
      '"Delta"',
      "Down",
      "Enter",
      "Enter",
    ]);
  });

  test("an answer with neither an option nor text is refused, not silently dropped", () => {
    const q = question(0);
    assert.throws(() => claudeAskPlan([q], [pick(0)]), /neither an option nor text/);
  });

  test("a question with no answer is refused: the review tab will not submit a gap", () => {
    const group = [question(0), question(1)];
    assert.throws(() => claudeAskPlan(group, [pick(0, "Alpha")]), /no answer for question call#1/);
  });

  test("every step is its own send, since a batched burst is misread", async () => {
    const herdr = fakeHerdr();
    const q = question(0);
    await claudeAnswerAsk(herdr, { paneId: "p1", group: [q], answers: [typed(0, "Delta")], text: "" }, 0);
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "4" } },
      { method: "paneSendText", params: { pane_id: "p1", text: "Delta" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("without questions the answer is typed at the pane's prompt", async () => {
    const herdr = fakeHerdr();
    await claudeAnswerAsk(herdr, { paneId: "p1", group: [], answers: [], text: "keep\ngoing" }, 0);
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "keep going" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });
});

describe("pi questionnaire grammar", () => {
  test("an option pick travels entirely as keys", () => {
    const q = question(0);
    assert.deepEqual(steps(piAskPlan([q], [pick(0, "Beta")])), ["down", "enter"]);
  });

  test("a custom answer opens the editor after the options and submits with the trailing enter", () => {
    const q = question(0);
    assert.deepEqual(steps(piAskPlan([q], [typed(0, "Delta")])), [
      "down",
      "down",
      "down",
      "enter",
      '"Delta"',
      "enter",
    ]);
  });

  test("a multi-question ask ends with the review tab's own enter", () => {
    const group = [question(0), question(1)];
    assert.deepEqual(steps(piAskPlan(group, [pick(0, "Alpha"), pick(1, "Alpha")])), [
      "enter",
      "enter",
      "enter",
    ]);
  });

  test("multi-select toggles with space and confirms with enter", () => {
    const q = question(0, { multiSelect: true });
    assert.deepEqual(steps(piAskPlan([q], [pick(0, "Alpha", "Gamma")])), [
      "space",
      "down",
      "down",
      "space",
      "enter",
    ]);
  });

  test("keys and text reach herdr in questionnaire order", async () => {
    const herdr = fakeHerdr();
    const q = question(0);
    await piAnswerAsk(herdr, { paneId: "p1", group: [q], answers: [typed(0, "Delta")], text: "" });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["down", "down", "down", "enter"] } },
      { method: "paneSendText", params: { pane_id: "p1", text: "Delta" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["enter"] } },
    ]);
  });
});

describe("agy has no questionnaire", () => {
  test("a lone answer is the bare value", async () => {
    const herdr = fakeHerdr();
    const q = question(0);
    await agyAnswerAsk(herdr, { paneId: "p1", group: [q], answers: [pick(0, "Beta")], text: "" });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "Beta" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("a round is labelled and sent as one prompt, not one turn per question", async () => {
    const herdr = fakeHerdr();
    const group = [question(0), question(1)];
    await agyAnswerAsk(herdr, {
      paneId: "p1",
      group,
      answers: [pick(0, "Alpha"), typed(1, "Delta")],
      text: "",
    });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "H0: Alpha; H1: Delta" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });
});

function snapshotWith(agent: string): SessionSnapshot {
  const pane: PaneInfo = {
    pane_id: "p1",
    workspace_id: "ws1",
    tab_id: "t1",
    terminal_id: "term1",
    focused: false,
    agent_status: "blocked",
    revision: 0,
    agent,
    display_agent: agent,
    agent_session: null,
    cwd: null,
    foreground_cwd: null,
    label: null,
    title: null,
    terminal_title: null,
    terminal_title_stripped: null,
    state_labels: {},
    scroll: null,
  };
  return {
    version: "0.8.0",
    protocol: 19,
    focused_workspace_id: null,
    focused_tab_id: null,
    focused_pane_id: null,
    workspaces: [],
    tabs: [],
    panes: [pane],
    agents: [],
    layouts: [],
  };
}

describe("answering an ask", () => {
  function deps(
    questions: QuestionEntry[],
    agent = "claude",
  ): { herdr: ReturnType<typeof fakeHerdr>; deps: AnswerDeps } {
    const herdr = fakeHerdr();
    return {
      herdr,
      deps: { herdr, snapshot: snapshotWith(agent), readQuestions: async () => questions },
    };
  }

  test("the whole round is delivered in one pass", async () => {
    const group = [question(0), question(1)];
    const { herdr, deps: answerDeps } = deps(group);
    await answerAsk(answerDeps, {
      paneId: "p1",
      callId: "call",
      answers: [pick(0, "Alpha"), pick(1, "Beta")],
      text: "",
    });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "1" } },
      { method: "paneSendText", params: { pane_id: "p1", text: "2" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("nothing is delivered when the round is incomplete", async () => {
    const group = [question(0), question(1)];
    const { herdr, deps: answerDeps } = deps(group);
    await assert.rejects(
      () => answerAsk(answerDeps, { paneId: "p1", callId: "call", answers: [pick(0, "Alpha")], text: "" }),
      /missing an answer for question call#1/,
    );
    // The gap is caught before a single keystroke lands, so the questionnaire
    // is never left half-walked by a client that under-filled the round.
    assert.deepEqual(herdr.sent, []);
  });

  test("refuses an ask that is not open in this session", async () => {
    const { deps: answerDeps } = deps([question(0)]);
    await assert.rejects(
      () => answerAsk(answerDeps, { paneId: "p1", callId: "other", answers: [], text: "" }),
      /no open ask/,
    );
  });

  test("refuses an ask the transcript already records as answered", async () => {
    const answered = question(0, { answered: true, answerText: "Alpha" });
    const { deps: answerDeps } = deps([answered]);
    await assert.rejects(
      () => answerAsk(answerDeps, { paneId: "p1", callId: "call", answers: [pick(0, "Beta")], text: "" }),
      /already answered/,
    );
  });

  test("an answer with no ask is typed at the prompt", async () => {
    const { herdr, deps: answerDeps } = deps([]);
    await answerAsk(answerDeps, { paneId: "p1", callId: "", answers: [], text: "just this" });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "just this" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("dismissing sends the agent's escape, then clears the pending-ask sidecar", async () => {
    const { herdr, deps: answerDeps } = deps([question(0)]);
    await dismissAsk(answerDeps, "p1");
    assert.deepEqual(herdr.sent[0], { method: "paneSendKeys", params: { pane_id: "p1", keys: ["escape"] } });
    // Escape alone would leave the card: Claude's sidecar is only cleared by
    // PostToolUse, which a cancelled call never reaches, so the backend looks
    // the session up itself and removes the file.
    assert.deepEqual(
      herdr.sent.map((call) => call.method),
      ["paneSendKeys", "snapshot"],
    );
  });
});
