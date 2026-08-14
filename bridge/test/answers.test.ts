import { test, describe, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { answerQuestion, clearAnswerProgress, type AnswerDeps } from "../src/answers.js";
import { claudeAnswerPlan } from "../src/agents/claude/questionnaire.js";
import { claudeAnswerQuestion } from "../src/agents/claude/index.js";
import { piAnswerPlan } from "../src/agents/pi/questionnaire.js";
import { piAnswerQuestion } from "../src/agents/pi/index.js";
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

/** The step stream as a compact string, so a plan reads like a keystroke log. */
function steps(plan: { steps: Array<{ kind: string; value: string }> }): string[] {
  return plan.steps.map((step) => (step.kind === "key" ? step.value : `"${step.value}"`));
}

describe("claude questionnaire grammar", () => {
  test("a lone single-select question is answered by its option digit alone", () => {
    const q = question(0);
    const plan = claudeAnswerPlan(q, [q], null, "", ["Beta"]);
    assert.deepEqual(steps(plan), ['"2"']);
    assert.deepEqual(plan.progress, { answered: ["call#0"], cursorTab: 0 });
  });

  test("a lone custom answer types into the entry after the options, then Enter", () => {
    const q = question(0);
    const plan = claudeAnswerPlan(q, [q], null, "Delta", []);
    assert.deepEqual(steps(plan), ['"4"', '"Delta"', "Enter"]);
  });

  test("a multi-question ask submits from the review tab once every question is answered", () => {
    const group = [question(0), question(1)];
    const first = claudeAnswerPlan(group[0]!, group, null, "", ["Alpha"]);
    // Answering tab 0 lands on tab 1; the ask is not complete, so no submit.
    assert.deepEqual(steps(first), ['"1"']);
    assert.deepEqual(first.progress, { answered: ["call#0"], cursorTab: 1 });

    const second = claudeAnswerPlan(group[1]!, group, first.progress, "", ["Gamma"]);
    assert.deepEqual(steps(second), ['"3"', "Enter"]);
    assert.deepEqual(second.progress, { answered: ["call#0", "call#1"], cursorTab: 2 });
  });

  test("tabs are walked with signed left/right because the strip does not wrap", () => {
    const group = [question(0), question(1)];
    const second = claudeAnswerPlan(group[1]!, group, null, "", ["Alpha"]);
    assert.deepEqual(steps(second), ["Right", '"1"']);
    // Answering the last question first parks the cursor on the review tab,
    // so the first question is reached by walking back, never by wrapping.
    const first = claudeAnswerPlan(group[0]!, group, second.progress, "", ["Beta"]);
    assert.deepEqual(steps(first), ["Left", "Left", '"2"', "Right", "Enter"]);
  });

  test("multi-select toggles each option then submits from the row under the list", () => {
    const q = question(0, { multiSelect: true });
    const plan = claudeAnswerPlan(q, [q], null, "", ["Alpha", "Gamma"]);
    // Three options plus "Type something" separate the first row from Submit.
    assert.deepEqual(steps(plan), ['"1"', '"3"', "Down", "Down", "Down", "Down", "Enter", "Enter"]);
  });

  test("multi-select custom text walks the cursor to the text field (no digit focuses it)", () => {
    const q = question(0, { multiSelect: true });
    const plan = claudeAnswerPlan(q, [q], null, "Delta", []);
    assert.deepEqual(steps(plan), ["Down", "Down", "Down", '"Delta"', "Down", "Enter", "Enter"]);
  });

  test("a single-select answer with neither option nor text is not delivered", () => {
    const q = question(0);
    const plan = claudeAnswerPlan(q, [q], null, "", []);
    assert.deepEqual(plan.steps, []);
    assert.deepEqual(plan.progress.answered, []);
  });

  test("every step is its own send, since a batched burst is misread", async () => {
    const herdr = fakeHerdr();
    const q = question(0);
    await claudeAnswerQuestion(
      herdr,
      { paneId: "p1", question: q, group: [q], progress: null, text: "Delta", selectedLabels: [] },
      0,
    );
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "4" } },
      { method: "paneSendText", params: { pane_id: "p1", text: "Delta" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("without a question the answer is typed at the pane's prompt", async () => {
    const herdr = fakeHerdr();
    const result = await claudeAnswerQuestion(
      herdr,
      { paneId: "p1", question: null, group: [], progress: null, text: "keep\ngoing", selectedLabels: [] },
      0,
    );
    assert.equal(result, null);
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "keep going" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });
});

describe("pi questionnaire grammar", () => {
  test("an option pick travels entirely as keys", () => {
    const q = question(0);
    const plan = piAnswerPlan(q, [q], null, "", ["Beta"]);
    assert.deepEqual(plan.keys, ["down", "enter"]);
    assert.equal(plan.custom, false);
    assert.deepEqual(plan.progress, { answered: ["call#0"], cursorTab: 1 });
  });

  test("a custom answer opens the editor after the options and submits with the trailing enter", () => {
    const q = question(0);
    const plan = piAnswerPlan(q, [q], null, "Delta", []);
    assert.deepEqual(plan.keys, ["down", "down", "down", "enter"]);
    assert.deepEqual(plan.trailingKeys, ["enter"]);
    assert.equal(plan.custom, true);
  });

  test("the last question of a multi-question ask also submits the review tab", () => {
    const group = [question(0), question(1)];
    const first = piAnswerPlan(group[0]!, group, null, "", ["Alpha"]);
    assert.deepEqual(first.keys, ["enter"]);
    const second = piAnswerPlan(group[1]!, group, first.progress, "", ["Alpha"]);
    assert.deepEqual(second.keys, ["enter", "enter"]);
    const custom = piAnswerPlan(group[1]!, group, first.progress, "Delta", []);
    assert.deepEqual(custom.trailingKeys, ["enter", "enter"]);
  });

  test("multi-select toggles with space and confirms with enter", () => {
    const q = question(0, { multiSelect: true });
    const plan = piAnswerPlan(q, [q], null, "", ["Alpha", "Gamma"]);
    assert.deepEqual(plan.keys, ["space", "down", "down", "space", "enter"]);
  });

  test("keys and text reach herdr in questionnaire order", async () => {
    const herdr = fakeHerdr();
    const q = question(0);
    await piAnswerQuestion(herdr, {
      paneId: "p1",
      question: q,
      group: [q],
      progress: null,
      text: "Delta",
      selectedLabels: [],
    });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["down", "down", "down", "enter"] } },
      { method: "paneSendText", params: { pane_id: "p1", text: "Delta" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["enter"] } },
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

describe("answering a question card", () => {
  beforeEach(() => clearAnswerProgress());

  function deps(questions: QuestionEntry[], agent = "claude"): { herdr: ReturnType<typeof fakeHerdr>; deps: AnswerDeps } {
    const herdr = fakeHerdr();
    return {
      herdr,
      deps: { herdr, snapshot: snapshotWith(agent), readQuestions: async () => questions },
    };
  }

  test("carries the ask's progress from one answer to the next", async () => {
    const group = [question(0), question(1)];
    const { herdr, deps: answerDeps } = deps(group);
    await answerQuestion(answerDeps, { paneId: "p1", questionId: "call#0", text: "", selectedLabels: ["Alpha"] });
    await answerQuestion(answerDeps, { paneId: "p1", questionId: "call#1", text: "", selectedLabels: ["Beta"] });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "1" } },
      // No tab walk: answering the first question already advanced the strip.
      { method: "paneSendText", params: { pane_id: "p1", text: "2" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });

  test("refuses to answer the same question twice in one ask", async () => {
    const group = [question(0), question(1)];
    const { deps: answerDeps } = deps(group);
    await answerQuestion(answerDeps, { paneId: "p1", questionId: "call#0", text: "", selectedLabels: ["Alpha"] });
    await assert.rejects(
      () => answerQuestion(answerDeps, { paneId: "p1", questionId: "call#0", text: "", selectedLabels: ["Beta"] }),
      /already answered/,
    );
  });

  test("refuses a question that is not open in this session", async () => {
    const { deps: answerDeps } = deps([question(0)]);
    await assert.rejects(
      () => answerQuestion(answerDeps, { paneId: "p1", questionId: "other#0", text: "", selectedLabels: ["Alpha"] }),
      /no open question/,
    );
  });

  test("refuses a question the transcript already records as answered", async () => {
    const answered = question(0, { answered: true, answerText: "Alpha" });
    const { deps: answerDeps } = deps([answered]);
    await assert.rejects(
      () => answerQuestion(answerDeps, { paneId: "p1", questionId: "call#0", text: "", selectedLabels: ["Beta"] }),
      /already answered/,
    );
  });

  test("a completed ask stops carrying progress", async () => {
    const q = question(0);
    const { deps: answerDeps } = deps([q]);
    await answerQuestion(answerDeps, { paneId: "p1", questionId: "call#0", text: "", selectedLabels: ["Alpha"] });
    // The next answer to the same call id starts from a clean strip, which a
    // stale cursorTab would have turned into a spurious tab walk.
    const { herdr, deps: again } = deps([question(0)]);
    await answerQuestion(again, { paneId: "p1", questionId: "call#0", text: "", selectedLabels: ["Alpha"] });
    assert.deepEqual(herdr.sent, [{ method: "paneSendText", params: { pane_id: "p1", text: "1" } }]);
  });

  test("an answer with no question is typed at the prompt", async () => {
    const { herdr, deps: answerDeps } = deps([]);
    await answerQuestion(answerDeps, { paneId: "p1", questionId: "", text: "just this", selectedLabels: [] });
    assert.deepEqual(herdr.sent, [
      { method: "paneSendText", params: { pane_id: "p1", text: "just this" } },
      { method: "paneSendKeys", params: { pane_id: "p1", keys: ["Enter"] } },
    ]);
  });
});
