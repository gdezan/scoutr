import * as v from "valibot";
import { linesMentioning, readTranscriptText, type ContentBlock, type TranscriptEntry, type ToolCallBlock } from "./transcript.js";

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
  /**
   * Background the agent wrote above this ask, when the transcript could not
   * carry it yet. Present only on an open Claude ask, whose introducing prose
   * is buffered with the tool call until the round resolves — the app shows
   * it above the card, and the transcript's own entry takes over the moment
   * the round lands (see `agents/claude/ask-preamble.ts`, ADR 0012).
   */
  preamble?: string;
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

const questionContainerSchema = v.looseObject({ questions: v.optional(v.unknown()) });
const questionSchema = v.looseObject({
  id: v.optional(v.string()),
  question: v.string(),
  header: v.string(),
  multiSelect: v.optional(v.boolean()),
  options: v.optional(v.unknown()),
});
const optionSchema = v.looseObject({
  label: v.string(),
  description: v.optional(v.string()),
});
const answersContainerSchema = v.looseObject({ answers: v.optional(v.unknown()) });
const answerSchema = v.looseObject({
  questionIndex: v.optional(v.number()),
  question: v.optional(v.string()),
  kind: v.optional(v.string()),
  answer: v.optional(v.union([v.string(), v.null()])),
  selected: v.optional(v.array(v.string())),
});

type QuestionContainer = v.InferOutput<typeof questionContainerSchema>;

/** Extract all pending/answered questions from a session's message entries. */
export function extractQuestions(entries: TranscriptEntry[]): QuestionEntry[] {
  const calls: Array<{ entryId: string; timestamp: string; call: ToolCallBlock }> = [];
  const answersByCallId = new Map<string, RawAnswer[]>();

  for (const entry of entries) {
    for (const block of entry.content) {
      if (!isToolCallBlock(block)) continue;
      if (block.name !== ASK_USER_QUESTION_TOOL) continue;
      calls.push({ entryId: entry.entryId, timestamp: entry.timestamp, call: block });
    }
    if (entry.role === "toolResult" && entry.toolCallId) {
      const answers = readToolResultAnswers(entry);
      if (answers) answersByCallId.set(entry.toolCallId, answers);
    }
  }

  const questions: QuestionEntry[] = [];
  for (const { entryId, timestamp, call } of calls) {
    const parsedArgs = v.safeParse(questionContainerSchema, call.arguments);
    const raw = parseQuestions(parsedArgs.success ? parsedArgs.output : undefined);
    const answers = answersByCallId.get(call.id) ?? [];
    // Answers carry their position (`questionIndex`) when the transcript
    // records it; positional pairing is only a fallback for old transcripts
    // where no answer in the call has an index (a partially-indexed call
    // must not misalign — unindexed positions stay unanswered).
    const byIndex = new Map<number, RawAnswer>();
    for (const a of answers) {
      if (a.questionIndex !== undefined) byIndex.set(a.questionIndex, a);
    }
    raw.forEach((question, index) => {
      const match =
        byIndex.get(index) ??
        (byIndex.size === 0 && index < answers.length ? answers[index] : undefined);
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

function parseQuestions(container: QuestionContainer | undefined): RawQuestion[] {
  const questionsValue = container?.questions;
  if (!Array.isArray(questionsValue)) return [];
  const questions: RawQuestion[] = [];
  for (const item of questionsValue) {
    const parsed = v.safeParse(questionSchema, item);
    if (!parsed.success) continue;
    const question = parsed.output;
    if (!Array.isArray(question.options)) continue;
    const options: RawOption[] = [];
    for (const raw of question.options) {
      const option = v.safeParse(optionSchema, raw);
      if (!option.success) continue;
      options.push({ label: option.output.label, description: option.output.description ?? "" });
    }
    if (options.length === 0 && !question.multiSelect) continue; // free-text needs no options
    questions.push({
      id: question.id,
      question: question.question,
      header: question.header,
      options,
      multiSelect: question.multiSelect === true,
    });
  }
  return questions;
}

function readToolResultAnswers(entry: TranscriptEntry): RawAnswer[] | null {
  const parsed = v.safeParse(answersContainerSchema, entry.details);
  const answersValue = parsed.success ? parsed.output.answers : undefined;
  if (!Array.isArray(answersValue)) return null;
  const answers: RawAnswer[] = [];
  for (const item of answersValue) {
    const answer = v.safeParse(answerSchema, item);
    if (!answer.success) continue;
    const output = answer.output;
    answers.push({
      questionIndex: output.questionIndex,
      question: output.question,
      kind: output.kind,
      answer: output.answer ?? null,
      selected: output.selected,
    });
  }
  return answers;
}

function isToolCallBlock(block: ContentBlock): block is ToolCallBlock {
  return block.type === "toolCall";
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

/**
 * Every question card of a session, read without normalizing the whole
 * transcript.
 *
 * Question state has to be authoritative over the entire file: an ask the
 * user escaped in the terminal is never written back as answered, and Chat
 * keeps its composer locked on it — so a page-sized tail read cannot decide
 * it. Almost none of a transcript is about questions, though. The file text
 * is scanned for the few lines that can carry an ask (the tool call, then any
 * record naming one of its call ids) and only those reach the JSONL parser,
 * so a multi-megabyte session costs one read plus a handful of JSON.parse
 * calls instead of a full entry normalization.
 */
export async function scanAskQuestions(
  path: string,
  toolNames: readonly string[],
  parse: (text: string) => TranscriptEntry[],
  extract: (entries: TranscriptEntry[]) => QuestionEntry[],
): Promise<QuestionEntry[]> {
  const text = await readTranscriptText(path);
  const callText = linesMentioning(text, toolNames);
  if (!callText) return [];
  // The call ids come from the calls themselves: an answer record names the
  // id but not always the tool, so it can only be found on a second pass.
  const callIds = new Set(extract(parse(callText)).map((question) => question.callId));
  if (callIds.size === 0) return [];
  return extract(parse(linesMentioning(text, [...toolNames, ...callIds])));
}
