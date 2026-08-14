import type { QuestionEntry } from "../../questions.js";
import type { AnswerProgress } from "../types.js";

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
 */
export interface ClaudeAnswerStep {
  kind: "key" | "text";
  value: string;
}

export interface ClaudeAnswerPlan {
  steps: ClaudeAnswerStep[];
  progress: AnswerProgress;
}

const key = (value: string): ClaudeAnswerStep => ({ kind: "key", value });
const text = (value: string): ClaudeAnswerStep => ({ kind: "text", value });

/** True when the ask ends on a review tab instead of submitting on the pick. */
export function claudeHasSubmitTab(group: QuestionEntry[]): boolean {
  return !(group.length === 1 && group[0]?.multiSelect !== true);
}

export function claudeAnswerPlan(
  question: QuestionEntry,
  group: QuestionEntry[],
  progress: AnswerProgress | null,
  answerText: string,
  selectedLabels: string[],
): ClaudeAnswerPlan {
  const n = group.length;
  const k = group.findIndex((candidate) => candidate.id === question.id);
  const answered = [...new Set([...(progress?.answered ?? []), question.id])];
  if (k < 0) {
    return { steps: [], progress: { answered, cursorTab: progress?.cursorTab ?? 0 } };
  }
  const submitTab = claudeHasSubmitTab(group);
  const lastTab = submitTab ? n : n - 1;
  const steps: ClaudeAnswerStep[] = [];

  // Walk the tab strip to this question. Tabs do not wrap, so the walk is
  // signed and bounded by the strip.
  const currentTab = Math.min(Math.max(progress?.cursorTab ?? 0, 0), lastTab);
  const delta = k - currentTab;
  for (let i = 0; i < Math.abs(delta); i += 1) steps.push(key(delta > 0 ? "Right" : "Left"));

  const indices = selectedLabels
    .map((label) => question.options.findIndex((option) => option.label === label))
    .filter((index) => index >= 0);
  const optionCount = question.options.length;
  const custom = indices.length === 0;

  if (question.multiSelect) {
    for (const index of indices) steps.push(text(String(index + 1)));
    if (custom && answerText) {
      // No digit focuses the text field in multi-select (a digit only toggles
      // its checkbox), so the cursor walks down to it from the first row.
      for (let i = 0; i < optionCount; i += 1) steps.push(key("Down"));
      steps.push(text(answerText));
      steps.push(key("Down")); // out of the field, onto the Next/Submit row
    } else {
      // The cursor never left the first row, so the Next/Submit row is one
      // step past "Type something".
      for (let i = 0; i < optionCount + 1; i += 1) steps.push(key("Down"));
    }
    steps.push(key("Enter"));
  } else if (custom) {
    if (!answerText) {
      return { steps: [], progress: { answered: progress?.answered ?? [], cursorTab: currentTab } };
    }
    steps.push(text(String(optionCount + 1))); // focus "Type something"
    steps.push(text(answerText));
    steps.push(key("Enter"));
  } else {
    steps.push(text(String((indices[0] ?? 0) + 1)));
  }

  const cursorTab = Math.min(k + 1, lastTab);
  // The ask is submitted from the review tab once every question is answered.
  const complete = group.every((candidate) => answered.includes(candidate.id));
  if (complete && submitTab) {
    for (let i = 0; i < n - cursorTab; i += 1) steps.push(key("Right"));
    steps.push(key("Enter"));
  }
  return { steps, progress: { answered, cursorTab } };
}
