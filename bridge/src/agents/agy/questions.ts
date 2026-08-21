import * as v from "valibot";
import type { QuestionEntry, QuestionOption } from "../../questions.js";
import type { ContentBlock, TextBlock, ToolCallBlock, TranscriptEntry } from "../../transcript.js";

export const AGY_ASK_QUESTION_TOOL = "ask_question";

const rawQuestionSchema = v.looseObject({
  question: v.string(),
  header: v.optional(v.string()),
  options: v.optional(
    v.array(v.union([v.string(), v.looseObject({ label: v.string(), description: v.optional(v.string()) })])),
  ),
  is_multi_select: v.optional(v.boolean()),
  multiSelect: v.optional(v.boolean()),
});
type RawAgyQuestion = v.InferOutput<typeof rawQuestionSchema>;

const rawArgsSchema = v.union([
  v.pipe(
    v.string(),
    v.transform((raw): { questions?: unknown } | undefined => {
      try {
        return JSON.parse(raw);
      } catch {
        return undefined;
      }
    }),
  ),
  v.looseObject({ questions: v.optional(v.unknown()) }),
]);

function isToolCallBlock(block: ContentBlock): block is ToolCallBlock {
  return block.type === "toolCall";
}

function isTextBlock(block: ContentBlock): block is TextBlock {
  return block.type === "text";
}

export function extractAgyQuestions(entries: TranscriptEntry[]): QuestionEntry[] {
  const calls: Array<{ entryId: string; timestamp: string; call: ToolCallBlock }> = [];
  const resultsByCallId = new Map<string, TranscriptEntry>();

  for (const entry of entries) {
    for (const block of entry.content) {
      if (!isToolCallBlock(block)) continue;
      if (block.name === AGY_ASK_QUESTION_TOOL || block.name === "ask_user_question") {
        calls.push({ entryId: entry.entryId, timestamp: entry.timestamp, call: block });
      }
    }
    if (entry.role === "toolResult" && entry.toolCallId) {
      resultsByCallId.set(entry.toolCallId, entry);
    }
  }

  const questions: QuestionEntry[] = [];
  for (const { entryId, timestamp, call } of calls) {
    const argsParsed = v.safeParse(rawArgsSchema, call.arguments);
    let rawQuestions: RawAgyQuestion[] = [];
    if (argsParsed.success && argsParsed.output !== undefined) {
      const nested = argsParsed.output.questions;
      if (nested !== undefined && Array.isArray(nested)) {
        rawQuestions = decodeRawQuestions(nested);
      }
    }
    const result = resultsByCallId.get(call.id);
    const resultText = result ? extractResultText(result) : "";
    const parsedAnswers = parseResultAnswers(resultText);

    rawQuestions.forEach((q, index) => {
      const answer =
        parsedAnswers.get(index + 1) ?? (rawQuestions.length === 1 && resultText ? resultText : undefined);
      const isMulti = q.is_multi_select === true || q.multiSelect === true;
      const isAnswered = Boolean(result);

      const options: QuestionOption[] = (q.options ?? []).map((opt) => {
        if (!(opt instanceof Object)) return { label: opt, description: "" };
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

function decodeRawQuestions(questions: unknown[]): RawAgyQuestion[] {
  const list: RawAgyQuestion[] = [];
  for (const item of questions) {
    const parsed = v.safeParse(rawQuestionSchema, item);
    if (!parsed.success) continue;
    if (!parsed.output.question.trim()) continue;
    list.push(parsed.output);
  }
  return list;
}

function extractResultText(entry: TranscriptEntry): string {
  for (const block of entry.content) {
    if (isTextBlock(block)) return block.text;
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
