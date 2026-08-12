import type { TranscriptEntry, ToolCallBlock } from "./transcript.js";

/**
 * Structured questions from pi session events.
 *
 * pi renders `ask_user_question` tool calls as an interactive questionnaire in
 * the TUI; the pane blocks until the user answers. The question itself lives in
 * the assistant message's toolCall arguments, and the answer in the matching
 * toolResult's `details.answers`. Both are session events — never terminal text
 * — so the app can render native cards and recover the answered state.
 */

export interface QuestionOption {
  label: string;
  description: string;
}

export interface QuestionEntry {
  /** Stable card id: the model's question id, or `${toolCallId}#${index}`. */
  id: string;
  /** The tool call id this question came from; groups a multi-question ask. */
  callId: string;
  /** The transcript entry id that made the call; the card's list position. */
  entryId: string;
  question: string;
  header: string;
  options: QuestionOption[];
  multiSelect: boolean;
  /** Whether a matching toolResult with answers has landed. */
  answered: boolean;
  /** Answer text for option/custom kinds, null for multi-select. */
  answerText: string | null;
  /** Selected option labels for multi-select answers. */
  selected: string[];
  timestamp: string;
}

export const ASK_USER_QUESTION_TOOL = "ask_user_question";

interface RawQuestion {
  id?: string;
  question: string;
  header: string;
  options: RawOption[];
  multiSelect?: boolean;
}

interface RawOption {
  label: string;
  description?: string;
}

interface RawAnswer {
  questionIndex?: number;
  question?: string;
  kind?: string;
  answer?: string | null;
  selected?: string[];
}

/** Extract all pending/answered questions from a session's message entries. */
export function extractQuestions(entries: TranscriptEntry[]): QuestionEntry[] {
  const calls: Array<{ entryId: string; timestamp: string; call: ToolCallBlock }> = [];
  const answersByCallId = new Map<string, RawAnswer[]>();

  for (const entry of entries) {
    for (const block of entry.content) {
      if (block.type !== "toolCall") continue;
      const call = block as ToolCallBlock;
      if (call.name !== ASK_USER_QUESTION_TOOL) continue;
      calls.push({ entryId: entry.entryId, timestamp: entry.timestamp, call });
    }
    if (entry.role === "toolResult" && entry.toolCallId) {
      const answers = readToolResultAnswers(entry);
      if (answers) answersByCallId.set(entry.toolCallId, answers);
    }
  }

  const questions: QuestionEntry[] = [];
  for (const { entryId, timestamp, call } of calls) {
    const raw = parseQuestions(call.arguments);
    const answers = answersByCallId.get(call.id) ?? [];
    raw.forEach((question, index) => {
      const match = index < answers.length ? answers[index] : undefined;
      const answer = match && kindIsAnswer(match.kind) ? match : undefined;
      questions.push({
        id: question.id || `${call.id}#${index}`,
        callId: call.id,
        entryId,
        question: question.question,
        header: question.header,
        options: question.options.map((option) => ({
          label: option.label,
          description: option.description ?? "",
        })),
        multiSelect: question.multiSelect === true,
        answered: !!answer,
        answerText: answer && answer.kind !== "multi" ? (answer.answer ?? null) : null,
        selected: answer?.selected ?? [],
        timestamp,
      });
    });
  }
  return questions;
}

function parseQuestions(argumentsValue: unknown): RawQuestion[] {
  const args = argumentsValue as { questions?: unknown } | null | undefined;
  if (!args || !Array.isArray(args.questions)) return [];
  const questions: RawQuestion[] = [];
  for (const item of args.questions) {
    const q = item as Partial<RawQuestion> | null | undefined;
    if (!q || typeof q !== "object") continue;
    if (typeof q.question !== "string" || typeof q.header !== "string") continue;
    if (!Array.isArray(q.options)) continue;
    const options: RawOption[] = [];
    for (const raw of q.options) {
      const option = raw as Partial<RawOption> | null | undefined;
      if (!option || typeof option.label !== "string") continue;
      options.push({ label: option.label, description: option.description ?? "" });
    }
    if (options.length === 0 && !q.multiSelect) continue; // free-text needs no options
    questions.push({
      id: typeof q.id === "string" ? q.id : undefined,
      question: q.question,
      header: q.header,
      options,
      multiSelect: q.multiSelect === true,
    });
  }
  return questions;
}

function readToolResultAnswers(entry: TranscriptEntry): RawAnswer[] | null {
  const details = entry.details as { answers?: unknown } | null | undefined;
  if (!details || !Array.isArray(details.answers)) return null;
  const answers: RawAnswer[] = [];
  for (const item of details.answers) {
    const a = item as Partial<RawAnswer> | null | undefined;
    if (!a || typeof a !== "object") continue;
    answers.push({
      questionIndex: typeof a.questionIndex === "number" ? a.questionIndex : undefined,
      question: typeof a.question === "string" ? a.question : undefined,
      kind: typeof a.kind === "string" ? a.kind : undefined,
      answer: typeof a.answer === "string" ? a.answer : null,
      selected: Array.isArray(a.selected) ? a.selected.filter((s): s is string => typeof s === "string") : undefined,
    });
  }
  return answers;
}

function kindIsAnswer(kind: string | undefined): boolean {
  return kind === "option" || kind === "custom" || kind === "multi";
}

/**
 * Answer safety: one line (a newline would submit pi's questionnaire early),
 * no control characters, capped at pi's MAX_FIELD_LENGTH. Applied on the
 * bridge as defense in depth; the app sanitizes before sending too.
 */
export const MAX_ANSWER_LENGTH = 4000;

export function sanitizeAnswerText(text: string): string {
  const singleLine = text.replace(/[\r\n\u2028\u2029]+/g, " ");
  const clean = singleLine.replace(/[\u0000-\u001f\u007f]/g, "").trim();
  return clean.length > MAX_ANSWER_LENGTH ? clean.slice(0, MAX_ANSWER_LENGTH) : clean;
}
