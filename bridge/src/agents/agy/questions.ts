import type { QuestionEntry, QuestionOption } from "../../questions.js";
import type { ToolCallBlock, TranscriptEntry } from "../../transcript.js";

export const AGY_ASK_QUESTION_TOOL = "ask_question";

interface RawAgyQuestion {
  question: string;
  header?: string;
  options?: Array<string | { label: string; description?: string }>;
  is_multi_select?: boolean;
  multiSelect?: boolean;
}

export function extractAgyQuestions(entries: TranscriptEntry[]): QuestionEntry[] {
  const calls: Array<{ entryId: string; timestamp: string; call: ToolCallBlock }> = [];
  const resultsByCallId = new Map<string, TranscriptEntry>();

  for (const entry of entries) {
    for (const block of entry.content) {
      if (block.type !== "toolCall") continue;
      const call = block as ToolCallBlock;
      if (call.name === AGY_ASK_QUESTION_TOOL || call.name === "ask_user_question") {
        calls.push({ entryId: entry.entryId, timestamp: entry.timestamp, call });
      }
    }
    if (entry.role === "toolResult" && entry.toolCallId) {
      resultsByCallId.set(entry.toolCallId, entry);
    }
  }

  const questions: QuestionEntry[] = [];
  for (const { entryId, timestamp, call } of calls) {
    const rawQuestions = parseAgyQuestions(call.arguments);
    const result = resultsByCallId.get(call.id);
    const resultText = result ? extractResultText(result) : "";
    const parsedAnswers = parseResultAnswers(resultText);

    rawQuestions.forEach((q, index) => {
      const answer = parsedAnswers.get(index + 1) ?? (rawQuestions.length === 1 && resultText ? resultText : undefined);
      const isMulti = q.is_multi_select === true || q.multiSelect === true;
      const isAnswered = Boolean(result);

      const options: QuestionOption[] = (q.options ?? []).map((opt) => {
        if (typeof opt === "string") return { label: opt, description: "" };
        return { label: opt.label, description: opt.description ?? "" };
      });

      questions.push({
        id: `${call.id}#${index}`,
        callId: call.id,
        entryId,
        question: q.question,
        header: q.header ?? `Question ${index + 1}`,
        options,
        multiSelect: isMulti,
        answered: isAnswered,
        answerText: answer ?? (isAnswered ? resultText : null),
        selected: isMulti && answer ? parseSelectedOptions(answer) : [],
        timestamp,
      });
    });
  }

  return questions;
}

function parseAgyQuestions(argsValue: unknown): RawAgyQuestion[] {
  let args = argsValue as { questions?: unknown } | null | undefined;
  if (typeof argsValue === "string") {
    try {
      args = JSON.parse(argsValue) as { questions?: unknown };
    } catch {
      return [];
    }
  }
  if (!args || !Array.isArray(args.questions)) return [];
  const list: RawAgyQuestion[] = [];
  for (const item of args.questions) {
    if (!item || typeof item !== "object") continue;
    const q = item as RawAgyQuestion;
    if (typeof q.question !== "string" || !q.question.trim()) continue;
    list.push(q);
  }
  return list;
}

function extractResultText(entry: TranscriptEntry): string {
  for (const block of entry.content) {
    if (block.type === "text" && "text" in block) return (block as { text: string }).text;
  }
  return "";
}

/** Parses answers like "A1: Option A\nA2: Option B" */
function parseResultAnswers(text: string): Map<number, string> {
  const answers = new Map<number, string>();
  const lines = text.split(/\r?\n/);
  for (const line of lines) {
    const match = line.match(/^A(\d+):\s*(.+)$/);
    if (match && match[1] && match[2]) {
      const idx = parseInt(match[1], 10);
      answers.set(idx, match[2].trim());
    }
  }
  return answers;
}

function parseSelectedOptions(answer: string): string[] {
  return answer.split(/,\s*/).map((s) => s.trim()).filter(Boolean);
}
