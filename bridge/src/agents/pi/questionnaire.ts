import type { QuestionEntry } from "../../questions.js";
import type { AnswerProgress } from "../types.js";

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
 */
export interface PiAnswerPlan {
  /** Keys to send before any text. */
  keys: string[];
  /** Keys to send after the text; empty when the answer types nothing. */
  trailingKeys: string[];
  /** Whether the answer is typed into the "Type something" editor. */
  custom: boolean;
  progress: AnswerProgress;
}

export function piAnswerPlan(
  question: QuestionEntry,
  group: QuestionEntry[],
  progress: AnswerProgress | null,
  text: string,
  selectedLabels: string[],
): PiAnswerPlan {
  const n = group.length;
  const k = group.findIndex((candidate) => candidate.id === question.id);
  const answered = [...new Set([...(progress?.answered ?? []), question.id])];
  if (k < 0) {
    return { keys: [], trailingKeys: [], custom: true, progress: { answered, cursorTab: 0 } };
  }
  const tabCount = n + 1; // question tabs plus the review tab
  const currentTab = Math.min(progress?.cursorTab ?? 0, n);
  let delta = k - currentTab;
  if (delta < 0) delta += tabCount; // tab wraps around the review tab
  const keys: string[] = [];
  for (let i = 0; i < delta; i += 1) keys.push("tab");

  const labels = selectedLabels.length > 0 ? selectedLabels : text ? [text] : [];
  const indices = labels
    .map((label) => question.options.findIndex((option) => option.label === label))
    .filter((index) => index >= 0);
  const custom = question.options.length === 0 || indices.length === 0;

  if (custom) {
    // "Type something" is the first entry after the authored options.
    for (let i = 0; i < question.options.length; i += 1) keys.push("down");
    keys.push("enter");
  } else if (question.multiSelect) {
    let pos = 0;
    for (const index of [...indices].sort((a, b) => a - b)) {
      const step = index - pos;
      for (let i = 0; i < Math.abs(step); i += 1) keys.push(step > 0 ? "down" : "up");
      keys.push("space");
      pos = index;
    }
    keys.push("enter");
  } else {
    for (let i = 0; i < (indices[0] ?? 0); i += 1) keys.push("down");
    keys.push("enter");
  }

  const last = n > 1 && k === n - 1;
  const cursorTab = Math.min(k + 1, n);
  if (custom) {
    // The editor's enter submits the answer; on the last question of a
    // multi-question ask that lands on the review tab, and a second enter
    // submits the whole questionnaire.
    return {
      keys,
      trailingKeys: last ? ["enter", "enter"] : ["enter"],
      custom: true,
      progress: { answered, cursorTab },
    };
  }
  if (last) keys.push("enter"); // review-tab submit
  return { keys, trailingKeys: [], custom: false, progress: { answered, cursorTab } };
}
