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
 * A single-select question whose options carry `preview` text renders in a
 * second, different layout — option list left, preview pane right — and its
 * grammar is NOT the one above. Verified live against 2.1.250:
 *
 *     ←  ☐ Alpha  ☐ Beta  ✔ Submit  →
 *     Alpha question?
 *     ❯ 1. Alpha One      ┌────────────────────┐
 *       2. Alpha Two      │ ALPHA-ONE-PREVIEW  │
 *                         └────────────────────┘
 *                         Notes: press n to add notes
 *     Enter to select · ↑/↓ to navigate · n to add notes · Tab to switch questions
 *
 *  - A digit only MOVES the cursor. It does not pick and does not advance the
 *    tab — the difference that desynced every preview ask before this branch
 *    existed, because the digits meant for later questions kept re-aiming the
 *    first one and the trailing Enter confirmed whichever option they left the
 *    cursor on (usually the first).
 *  - `Enter` picks the option under the cursor and advances one tab, so the
 *    strip still walks itself; a pick costs `digit` + `Enter` instead of a
 *    lone digit.
 *  - There is no "Type something" entry. Free text goes to the Notes field:
 *    `n` focuses it, and Enter from inside it submits the question as the
 *    literal answer "(notes only)" with the text carried in `annotations`.
 *  - The layout is chosen per QUESTION, not per ask: a preview question and a
 *    plain question in the same ask each use their own grammar.
 *  - Multi-select ignores `preview` entirely and always uses the layout above.
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

/**
 * True when Claude renders this question with the preview pane instead of the
 * plain numbered list. Multi-select ignores `preview`, so only a single-select
 * question with preview text switches layout.
 */
function usesPreviewLayout(question: QuestionEntry): boolean {
  return question.hasPreviews === true && !question.multiSelect;
}

/** Keystrokes that answer one question, with the cursor already on its tab. */
function claudeQuestionSteps(question: QuestionEntry, answer: AskAnswer): ClaudeAnswerStep[] {
  if (usesPreviewLayout(question)) return claudePreviewQuestionSteps(question, answer);
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

/**
 * Keystrokes for one single-select question in the preview layout, with the
 * cursor already on its tab and on the first option.
 *
 * A digit only moves the cursor here, so the pick needs an explicit Enter —
 * which is also what advances to the next tab, exactly like a digit does in
 * the plain layout.
 */
function claudePreviewQuestionSteps(question: QuestionEntry, answer: AskAnswer): ClaudeAnswerStep[] {
  const index = question.options.findIndex((option) => option.label === answer.selectedLabels[0]);
  if (index < 0) {
    // No authored option matches, so this is a custom answer. The preview
    // layout has no "Type something" row; Notes is the only free-text field,
    // and Enter from inside it submits the question as "(notes only)".
    if (!answer.text) throw new Error(`answer for ${question.id} has neither an option nor text`);
    return [text("n"), text(answer.text), key("Enter")];
  }
  return [text(String(index + 1)), key("Enter")];
}
