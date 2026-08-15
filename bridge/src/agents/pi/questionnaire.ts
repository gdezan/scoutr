import type { QuestionEntry } from "../../questions.js";
import type { AskAnswer } from "../types.js";

/**
 * pi's ask_user_question TUI grammar.
 *
 * The questionnaire is keyboard-only: up/down move inside a question, space
 * toggles a multi-select option, enter chooses/continues, tab moves to the
 * next question. One ask renders as `n` question tabs plus a review tab, and
 * answering a question auto-advances one tab (capped at the review tab).
 *
 * Typed text is dropped while an option list is focused (and enter would pick
 * option 1), so option answers travel entirely as keys; a custom answer opens
 * the "Type something" editor — the entry just after the authored options —
 * and the text is typed there, submitted by the trailing enter. On the last
 * question of a multi-question ask that lands on the review tab, which needs
 * one more enter to submit the whole ask.
 *
 * Because answering auto-advances, a plan that walks the questions in ask
 * order from a freshly opened questionnaire never needs a `tab` key — which is
 * why the ask travels as one batch (see `piAskPlan`).
 */
export interface PiStep {
  kind: "key" | "text";
  value: string;
}

const key = (value: string): PiStep => ({ kind: "key", value });
const text = (value: string): PiStep => ({ kind: "text", value });

/**
 * Keystrokes for a whole ask, answered in ask order from a freshly opened
 * questionnaire (tab 0, first row focused). Every question must have an
 * answer; the review tab will not submit an incomplete ask.
 */
export function piAskPlan(group: QuestionEntry[], answers: AskAnswer[]): PiStep[] {
  const steps: PiStep[] = [];
  for (const question of group) {
    const answer = answers.find((candidate) => candidate.questionId === question.id);
    if (!answer) throw new Error(`no answer for question ${question.id}`);
    steps.push(...piQuestionSteps(question, answer));
  }
  // A lone question submits on its own answer; anything longer lands on the
  // review tab, which needs one more enter.
  if (group.length > 1) steps.push(key("enter"));
  return steps;
}

/** Keystrokes that answer one question, with its tab focused and row 0 selected. */
function piQuestionSteps(question: QuestionEntry, answer: AskAnswer): PiStep[] {
  const steps: PiStep[] = [];
  const indices = answer.selectedLabels
    .map((label) => question.options.findIndex((option) => option.label === label))
    .filter((index) => index >= 0);
  // Options and text are mutually exclusive: "Type something" is the entry
  // after the authored options, so an answer is a pick or a typed one.
  const custom = indices.length === 0;

  if (custom) {
    if (!answer.text) throw new Error(`answer for ${question.id} has neither an option nor text`);
    // "Type something" is the first entry after the authored options.
    for (let i = 0; i < question.options.length; i += 1) steps.push(key("down"));
    steps.push(key("enter")); // open the editor
    steps.push(text(answer.text));
    steps.push(key("enter")); // submit the answer, advancing one tab
    return steps;
  }

  if (question.multiSelect) {
    let position = 0;
    for (const index of [...indices].sort((a, b) => a - b)) {
      const step = index - position;
      for (let i = 0; i < Math.abs(step); i += 1) steps.push(key(step > 0 ? "down" : "up"));
      steps.push(key("space"));
      position = index;
    }
    steps.push(key("enter"));
    return steps;
  }

  for (let i = 0; i < (indices[0] ?? 0); i += 1) steps.push(key("down"));
  steps.push(key("enter"));
  return steps;
}
