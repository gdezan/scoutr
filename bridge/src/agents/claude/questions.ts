import type { QuestionEntry } from "../../questions.js";
import type { ToolCallBlock, Transcript, TranscriptEntry } from "../../transcript.js";
import { clearPendingAsk, readPendingAsk, type PendingAsk } from "./pending-asks.js";

/**
 * Claude Code's `AskUserQuestion` tool, read out of the session JSONL.
 *
 * The call carries the questions (`input.questions`, the same shape pi uses:
 * question/header/options/multiSelect). The answers land on the matching
 * tool-result record's `toolUseResult.answers`, a map keyed by the question
 * *text* — Claude records no question index — with the chosen option labels
 * as the value, comma-joined for a multi-select answer:
 *
 *   "answers": { "Which color?": "Green", "Which sizes?": "Small, Large" }
 *
 * The whole ask is answered at once, so every question of a call flips to
 * answered together.
 */
export const CLAUDE_ASK_TOOL = "AskUserQuestion";

interface RawQuestion {
  question: string;
  header: string;
  options: Array<{ label: string; description?: string }>;
  multiSelect?: boolean;
}

/**
 * Every question card of a session: the asks the transcript records, plus the
 * one the hook says is open right now (see `pending-asks.ts` — Claude writes a
 * pending ask nowhere else). The sidecar is dropped as soon as the transcript
 * carries the same call, so an ask is never shown twice and a sidecar the
 * PostToolUse hook missed heals itself.
 */
export function claudeQuestions(transcript: Transcript): QuestionEntry[] {
  const questions = extractClaudeQuestions(transcript.entries);
  const pending = readPendingAsk(transcript.id);
  if (!pending) return questions;
  if (questions.some((question) => question.callId === pending.toolUseId)) {
    clearPendingAsk(pending.sessionId);
    return questions;
  }
  return [...questions, ...pendingQuestions(pending)];
}

/** The open ask as cards: unanswered, and anchored after the last entry. */
function pendingQuestions(pending: PendingAsk): QuestionEntry[] {
  const entryId = `pending:${pending.toolUseId}`;
  return parseQuestions({ questions: pending.questions }).map((question, index) => ({
    id: `${pending.toolUseId}#${index}`,
    callId: pending.toolUseId,
    entryId,
    question: question.question,
    header: question.header,
    options: question.options.map((option) => ({
      label: option.label,
      description: option.description ?? "",
    })),
    multiSelect: question.multiSelect === true,
    answered: false,
    answerText: null,
    selected: [],
    timestamp: pending.timestamp,
  }));
}

export function extractClaudeQuestions(entries: TranscriptEntry[]): QuestionEntry[] {
  const calls: Array<{ entryId: string; timestamp: string; call: ToolCallBlock }> = [];
  const answersByCallId = new Map<string, Record<string, string>>();

  for (const entry of entries) {
    for (const block of entry.content) {
      if (block.type !== "toolCall") continue;
      const call = block as ToolCallBlock;
      if (call.name !== CLAUDE_ASK_TOOL) continue;
      calls.push({ entryId: entry.entryId, timestamp: entry.timestamp, call });
    }
    if (entry.role === "toolResult" && entry.toolCallId) {
      const answers = readAnswers(entry.details);
      if (answers) answersByCallId.set(entry.toolCallId, answers);
    }
  }

  const questions: QuestionEntry[] = [];
  for (const { entryId, timestamp, call } of calls) {
    const raw = parseQuestions(call.arguments);
    const answers = answersByCallId.get(call.id);
    raw.forEach((question, index) => {
      const answer = answers?.[question.question];
      const options = question.options.map((option) => ({
        label: option.label,
        description: option.description ?? "",
      }));
      const multiSelect = question.multiSelect === true;
      questions.push({
        id: `${call.id}#${index}`,
        callId: call.id,
        entryId,
        question: question.question,
        header: question.header,
        options,
        multiSelect,
        answered: answer !== undefined,
        answerText: answer !== undefined && !multiSelect ? answer : null,
        selected: answer !== undefined && multiSelect ? splitMultiAnswer(answer, options) : [],
        timestamp,
      });
    });
  }
  return questions;
}

/**
 * A multi-select answer is one comma-joined string. An authored label may
 * itself contain ", ", so labels are matched against the whole string first
 * and only the remainder is split — a custom answer survives as its own part.
 */
function splitMultiAnswer(answer: string, options: Array<{ label: string }>): string[] {
  // Longest label first, so "Extra, roomy" is claimed before "Extra" can be.
  const labels = options
    .map((option) => option.label)
    .filter(Boolean)
    .sort((a, b) => b.length - a.length);
  const parts: string[] = [];
  let at = 0;
  while (at < answer.length) {
    const label = labels.find(
      (candidate) => answer.startsWith(candidate, at) && endsPart(answer, at + candidate.length),
    );
    if (label) {
      parts.push(label);
      at += label.length;
    } else {
      const end = nextSeparator(answer, at);
      const part = answer.slice(at, end).trim();
      if (part) parts.push(part);
      at = end;
    }
    at = skipSeparator(answer, at);
  }
  return parts;
}

/** A part ends at the end of the list or at the next ", " separator. */
function endsPart(list: string, at: number): boolean {
  return at >= list.length || /^,\s/.test(list.slice(at));
}

function nextSeparator(list: string, from: number): number {
  const match = /,\s/.exec(list.slice(from));
  return match ? from + match.index : list.length;
}

function skipSeparator(list: string, at: number): number {
  const match = /^,\s+/.exec(list.slice(at));
  return match ? at + match[0].length : at;
}

function parseQuestions(argumentsValue: unknown): RawQuestion[] {
  const args = argumentsValue as { questions?: unknown } | null | undefined;
  if (!args || !Array.isArray(args.questions)) return [];
  const questions: RawQuestion[] = [];
  for (const item of args.questions) {
    const q = item as Partial<RawQuestion> | null | undefined;
    if (!q || typeof q !== "object") continue;
    if (typeof q.question !== "string" || typeof q.header !== "string") continue;
    const options: RawQuestion["options"] = [];
    if (Array.isArray(q.options)) {
      for (const rawOption of q.options) {
        const option = rawOption as { label?: unknown; description?: unknown } | null | undefined;
        if (!option || typeof option.label !== "string") continue;
        options.push({
          label: option.label,
          description: typeof option.description === "string" ? option.description : "",
        });
      }
    }
    questions.push({
      question: q.question,
      header: q.header,
      options,
      multiSelect: q.multiSelect === true,
    });
  }
  return questions;
}

/** `details` carries only what [claudeQuestionAnswers] kept: `{ answers }`. */
function readAnswers(details: unknown): Record<string, string> | null {
  const value = (details as { answers?: unknown } | null | undefined)?.answers;
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const answers: Record<string, string> = {};
  for (const [question, answer] of Object.entries(value as Record<string, unknown>)) {
    if (typeof answer === "string") answers[question] = answer;
  }
  return Object.keys(answers).length > 0 ? answers : null;
}

/**
 * The AskUserQuestion answers of a Claude tool-result record, or null for
 * every other tool. Only this slice of `toolUseResult` is kept on the entry:
 * the rest (file contents, command output) would bloat every transcript poll.
 */
export function claudeQuestionAnswers(toolUseResult: unknown): { answers: Record<string, string> } | null {
  const answers = readAnswers(toolUseResult);
  return answers ? { answers } : null;
}
