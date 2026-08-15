import { MAX_ANSWER_LENGTH, sanitizeAnswerText } from "./questions.js";

/** Upper bound on questions in one ask; the AskUserQuestion tool caps at 4. */
const MAX_ASK_QUESTIONS = 8;
import { answerAsk, dismissAsk, type AnswerDeps } from "./answers.js";
import { readSession } from "./routes/sessions.js";
import { MAX_PROMPT_LENGTH, PROMPT_FORBIDDEN_CHAR } from "./sessions.js";
import type { SessionSnapshot } from "./herdr/types.js";
import type { ServerDeps } from "./routes/types.js";

export type CommandMessage =
  | { type: "steer"; target: string; text: string }
  | {
      type: "answer_ask";
      paneId: string;
      /** Tool call id of the ask being answered; omitted for a plain prompt. */
      callId?: string;
      /** One answer per question of the ask, in any order. */
      answers?: Array<{ questionId: string; text?: string; selectedLabels?: string[] }>;
      /** Free-text typed at a plain blocked prompt; ignored when callId is set. */
      text?: string;
    }
  | { type: "dismiss_ask"; paneId: string }
  | { type: "slash_command"; paneId: string; text: string }
  | { type: "send_text"; paneId: string; text: string }
  | { type: "ping" }
  | { type: "subscribe"; filter?: string[] };

export type CommandResult =
  | { type: "pong"; ts: number }
  | { type: "subscribed"; filters: string[] }
  | { type: "steered"; target: string; result: unknown }
  | { type: "answered"; paneId: string; callId: string }
  | { type: "dismissed"; paneId: string }
  | { type: "command_sent"; paneId: string; text: string }
  | { type: "sent"; paneId: string };

/**
 * WS command dispatch. Pure: takes a parsed command and returns the reply
 * frame, so the command vocabulary is testable without a socket. The server
 * layer serializes the result and maps thrown errors to error frames.
 */
export async function handleCommand(command: CommandMessage, deps: ServerDeps): Promise<CommandResult> {
  switch (command.type) {
    case "ping":
      return { type: "pong", ts: Math.round(Date.now()) };
    case "subscribe": {
      // Intentional no-op placeholder: filter wiring lives on the connection.
      return { type: "subscribed", filters: command.filter ?? [] };
    }
    case "steer": {
      const { target, text } = command;
      if (!target) throw new Error("steer requires target and text");
      // Newlines are legal (multi-line prompts); anything that could alter
      // submission into a PTY is not.
      if (
        typeof text !== "string" ||
        text.length === 0 ||
        text.length > MAX_PROMPT_LENGTH ||
        PROMPT_FORBIDDEN_CHAR.test(text)
      ) {
        throw new Error("steer text must be plain text without control characters");
      }
      return { type: "steered", target, result: await deps.herdr.agentPrompt(target, text) };
    }
    case "answer_ask": {
      const { paneId, callId, answers, text } = command;
      if (!paneId) throw new Error("answer_ask requires paneId");
      if (callId !== undefined && (typeof callId !== "string" || callId.length > 200)) {
        throw new Error("answer_ask callId must be a tool call id");
      }
      // MAX_ASK_QUESTIONS bounds the round; the tool itself caps at 4, and a
      // client claiming more is not describing an ask this bridge can deliver.
      if (answers !== undefined && (!Array.isArray(answers) || answers.length > MAX_ASK_QUESTIONS)) {
        throw new Error("answer_ask answers must be a bounded list");
      }
      const parsed = (answers ?? []).map((answer) => {
        if (!answer || typeof answer !== "object") throw new Error("answer_ask answer must be an object");
        const { questionId, selectedLabels } = answer;
        if (typeof questionId !== "string" || !questionId || questionId.length > 200) {
          throw new Error("answer_ask answer needs a question card id");
        }
        if (
          selectedLabels !== undefined &&
          (!Array.isArray(selectedLabels) ||
            selectedLabels.length > 32 ||
            selectedLabels.some((label) => typeof label !== "string" || label.length > MAX_ANSWER_LENGTH))
        ) {
          throw new Error("answer_ask selectedLabels must be a bounded list of option labels");
        }
        return {
          questionId,
          text: sanitizeAnswerText(answer.text ?? ""),
          selectedLabels: selectedLabels ?? [],
        };
      });
      // The app sends intent — which questions, what the user picked — and
      // the backend turns the round into keystrokes for its own questionnaire.
      await answerAsk(answerDeps(deps), {
        paneId,
        callId: callId ?? "",
        answers: parsed,
        text: sanitizeAnswerText(text ?? ""),
      });
      return { type: "answered", paneId, callId: callId ?? "" };
    }
    case "dismiss_ask": {
      const { paneId } = command;
      if (!paneId) throw new Error("dismiss_ask requires paneId");
      await dismissAsk(answerDeps(deps), paneId);
      return { type: "dismissed", paneId };
    }
    case "slash_command": {
      const { paneId, text } = command;
      if (!paneId) throw new Error("slash_command requires paneId");
      const slashCommand = validateSlashCommand(text);
      await deps.herdr.paneSendInput(paneId, slashCommand, ["Enter"]);
      return { type: "command_sent", paneId, text: slashCommand };
    }
    case "send_text": {
      const { paneId, text } = command;
      if (!paneId) throw new Error("send_text requires paneId");
      // Same one-line/no-control-characters contract as answers: the text
      // goes into a PTY, but unlike answer text it must not be silently
      // altered — the caller sees a rejection instead.
      if (
        typeof text !== "string" ||
        text.length === 0 ||
        text.length > MAX_ANSWER_LENGTH ||
        sanitizeAnswerText(text) !== text
      ) {
        throw new Error("send_text requires plain single-line text (max 4000 chars)");
      }
      await deps.herdr.paneSendText(paneId, text);
      return { type: "sent", paneId };
    }
    default: {
      const exhaustive: never = command as never;
      throw new Error(`unknown command ${JSON.stringify(exhaustive)}`);
    }
  }
}

/**
 * Validate a `/command [args]` string for pane-native entry. Both known
 * backends share the slash grammar, so this is a wire-level rule.
 */
export function validateSlashCommand(text: unknown): string {
  if (typeof text !== "string") throw new Error("slash command text must be a string");
  if (text.length === 0 || text.length > 10_000) throw new Error("slash command text must be 1 to 10000 characters");
  if (!text.startsWith("/") || !/^\/[^\s\u0000-\u001f\u007f]+(?:[ \t][^\r\n\u0000-\u001f\u007f]*)?$/.test(text)) {
    throw new Error("invalid slash command text");
  }
  return text;
}

/**
 * Answering needs the question the card id refers to, so it reads the pane's
 * own transcript — the same memoized read the chat poll uses, so an answer
 * costs a stat in the steady state.
 */
function answerDeps(deps: ServerDeps): AnswerDeps {
  const snapshot = deps.feed.snapshot as SessionSnapshot | null;
  return {
    herdr: deps.herdr,
    snapshot,
    async readQuestions(backend, paneId) {
      const pane = snapshot?.panes.find((candidate) => candidate.pane_id === paneId);
      const agent = snapshot?.agents.find((candidate) => candidate.pane_id === paneId);
      const ref = pane?.agent_session ?? agent?.agent_session ?? null;
      if (!ref) return [];
      const cwd = pane?.cwd ?? agent?.cwd ?? undefined;
      const path = await backend.resolveSessionPath(ref, cwd ?? undefined);
      if (!path) return [];
      const session = await readSession(path, null);
      return session.questions;
    },
  };
}
