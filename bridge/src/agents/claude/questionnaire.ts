import type { QuestionEntry } from "../../questions.js";
import type { AskAnswer } from "../types.js";

/**
 * Claude Code's AskUserQuestion TUI grammar, mapped against 2.1.x live.
 *
 * One ask renders as a tab strip — one tab per question, plus a trailing
 * "Submit" review tab — over a numbered option list:
 *
 *     ←  ☒ Color  ☐ Size  ✔ Submit  →
 *     Which sizes?
 *     ❯ 1. [ ] Small
 *       2. [ ] Medium
 *       3. [ ] Large
 *       4. [ ] Type something
 *          Next
 *
 * The rules the plan below encodes, each verified in a live pane:
 *
 *  - `right`/`left` move one tab and do NOT wrap: from the submit tab, right
 *    stays put. So navigation is signed, never modular.
 *  - A digit acts on the option with that number, wherever the cursor is:
 *    single-select picks it and advances one tab; multi-select toggles it and
 *    stays. Answering tab `k` always lands on tab `k + 1`, answered or not.
 *  - "Type something" is the entry after the authored options. Once its text
 *    field has focus, digits and arrows go into the field — so digits are
 *    always sent before any text, never after.
 *  - Multi-select does not submit on a digit; the "Next"/"Submit" row under
 *    the option list does, and reaching it costs one `down` per option row
 *    plus one for "Type something" (or a single `down` when the text field
 *    holds the cursor). Pressing enter there advances one tab, exactly like a
 *    single-select pick.
 *  - The submit tab exists for every ask except a lone single-select question,
 *    which submits the moment its option is picked.
 *  - Keys are delivered one at a time: a batched `down down space` arrives as
 *    one chunk and is misread (the space toggled the row the cursor had left).
 *
 * Because answering tab `k` lands on tab `k + 1`, a plan that answers every
 * question in ask order starting from tab 0 never needs a single `Left` or
 * `Right`: the questionnaire walks itself. That is the whole reason the ask is
 * delivered as one batch — see `claudeAskPlan`.
 */
export interface ClaudeAnswerStep {
  kind: "key" | "text";
  value: string;
}

const key = (value: string): ClaudeAnswerStep => ({ kind: "key", value });
const text = (value: string): ClaudeAnswerStep => ({ kind: "text", value });

/** True when the ask ends on a review tab instead of submitting on the pick. */
export function claudeHasSubmitTab(group: QuestionEntry[]): boolean {
  return !(group.length === 1 && group[0]?.multiSelect !== true);
}

/**
 * Keystrokes for a whole ask, answered in ask order from a freshly opened
 * questionnaire (tab 0, nothing picked).
 *
 * Every question must have an answer: the review tab will not submit an
 * incomplete ask, and the app disables Submit until the round is complete, so
 * a gap here is a programming error rather than a user state.
 */
export function claudeAskPlan(group: QuestionEntry[], answers: AskAnswer[]): ClaudeAnswerStep[] {
  const steps: ClaudeAnswerStep[] = [];
  for (const question of group) {
    const answer = answers.find((candidate) => candidate.questionId === question.id);
    if (!answer) throw new Error(`no answer for question ${question.id}`);
    steps.push(...claudeQuestionSteps(question, answer));
  }
  // Answering the last question lands on the review tab, so submitting is a
  // single Enter — no walk, because the batch never left the strip's order.
  if (claudeHasSubmitTab(group)) steps.push(key("Enter"));
  return steps;
}

/** Keystrokes that answer one question, with the cursor already on its tab. */
function claudeQuestionSteps(question: QuestionEntry, answer: AskAnswer): ClaudeAnswerStep[] {
  const steps: ClaudeAnswerStep[] = [];
  const indices = answer.selectedLabels
    .map((label) => question.options.findIndex((option) => option.label === label))
    .filter((index) => index >= 0);
  const optionCount = question.options.length;
  // Options and text are mutually exclusive: "Type something" is the entry
  // after the authored options, not a field beside them, so an answer is
  // either a pick or a typed one — never both.
  const custom = indices.length === 0;
  const answerText = answer.text;

  if (question.multiSelect) {
    if (custom) {
      if (!answerText) throw new Error(`answer for ${question.id} has neither an option nor text`);
      // No digit focuses the text field in multi-select (a digit only toggles
      // its checkbox), so the cursor walks down to it from the first row.
      for (let i = 0; i < optionCount; i += 1) steps.push(key("Down"));
      steps.push(text(answerText));
      steps.push(key("Down")); // out of the field, onto the Next/Submit row
    } else {
      for (const index of indices) steps.push(text(String(index + 1)));
      // The cursor never left the first row, so the Next/Submit row is one
      // step past "Type something".
      for (let i = 0; i < optionCount + 1; i += 1) steps.push(key("Down"));
    }
    steps.push(key("Enter"));
  } else if (custom) {
    if (!answerText) throw new Error(`answer for ${question.id} has neither an option nor text`);
    steps.push(text(String(optionCount + 1))); // focus "Type something"
    steps.push(text(answerText));
    steps.push(key("Enter"));
  } else {
    steps.push(text(String((indices[0] ?? 0) + 1)));
  }
  return steps;
}
