import { MAX_ANSWER_LENGTH, sanitizeAnswerText } from "./questions.js";

/** Upper bound on questions in one ask; the AskUserQuestion tool caps at 4. */
const MAX_ASK_QUESTIONS = 8;
import { answerAsk, dismissAsk, type AnswerDeps } from "./answers.js";
import { CommandError } from "./errors.js";
import { readSession } from "./routes/sessions.js";
import { MAX_PROMPT_LENGTH, promptHasForbiddenChar } from "./sessions.js";
import type { SessionSnapshot } from "./herdr/types.js";
import type { ServerDeps, JsonValue } from "./routes/types.js";

/**
 * Session command semantics, independent of how the command arrived.
 *
 * Each operation below owns the validation and the herdr side effect for one
 * command. The HTTP routes in `routes/session-commands.ts` are the production
 * caller; `handleLegacyWsCommand` at the bottom of this file is the frame
 * adapter old installed APKs still reach through `/ws`. Neither surface
 * repeats a limit or an ask rule — that is the whole point of the split.
 *
 * Validation failures are `CommandError` (400), and a command that no longer
 * matches the pane's live state (an ask that vanished or was already
 * answered) is 409. A pane that is not in the live topology at all is 404, the
 * same answer `POST /api/sessions/:paneId/control` gives. Herdr's own failures
 * keep their `HerdrError` 502: a pane that dies mid-command is a backend
 * fault, not a malformed request.
 */

/**
 * Reject a pane that is not in the live topology before touching herdr.
 *
 * The comparable session-control route answers 404 for a pane with no live
 * backend (`sessions.ts`), so the command routes match that taxonomy instead
 * of letting a missing pane surface as a herdr 502. A pane that dies *after*
 * this check still yields the backend's own 502, which is the documented
 * mid-command behavior.
 *
 * A null snapshot means the feed has not delivered one yet, not that the pane
 * is gone; in that case the command proceeds and herdr remains the judge.
 */
function requireLivePane(deps: ServerDeps, paneId: string): void {
  // SAFETY: the feed snapshot is either a SessionSnapshot or absent; we only
  // read pane/agent ids from it, which the snapshot type guarantees.
  const snapshot = deps.feed.snapshot as SessionSnapshot | null;
  if (!snapshot) return;
  if (!snapshot.panes.some((candidate) => candidate.pane_id === paneId)) {
    throw new CommandError(`no live pane ${paneId}`, 404);
  }
}

/** One question's answer inside a batched ask, as it arrives on the wire. */
export interface AskAnswerInput {
  questionId: string;
  text?: string;
  selectedLabels?: string[];
}

/** A whole ask round, or a plain blocked prompt when `callId` is absent. */
export interface AnswerAskRequest {
  paneId: string;
  /** Tool call id of the ask being answered; omitted for a plain prompt. */
  callId?: string;
  /** One answer per question of the ask, in any order. */
  answers?: AskAnswerInput[];
  /** Free-text typed at a plain blocked prompt; ignored when callId is set. */
  text?: string;
}

/** Steer a running agent with a (possibly multi-line) prompt. */
export async function steerSession(deps: ServerDeps, target: string, text: string): Promise<JsonValue> {
  if (!target) throw new CommandError("steer requires target and text");
  // Newlines are legal (multi-line prompts); anything that could alter
  // submission into a PTY is not.
  if (
    text.length === 0 ||
    text.length > MAX_PROMPT_LENGTH ||
    promptHasForbiddenChar(text)
  ) {
    throw new CommandError("steer text must be plain text without control characters");
  }
  // No `requireLivePane` here on purpose: this target goes to
  // `herdr.agentPrompt`, which accepts targets that are not snapshot pane ids,
  // so a snapshot check would reject valid steers.
  return await deps.herdr.agentPrompt(target, text);
}

/**
 * Deliver a whole ask round. The app sends intent — which questions, what the
 * user picked — and the backend turns the round into keystrokes for its own
 * questionnaire. Returns the callId the answer was recorded against ("" for a
 * plain blocked prompt).
 */
export async function answerSessionAsk(deps: ServerDeps, request: AnswerAskRequest): Promise<string> {
  const { paneId, callId, answers, text } = request;
  if (!paneId) throw new CommandError("answer_ask requires paneId");
  if (callId !== undefined && callId.length > 200) {
    throw new CommandError("answer_ask callId must be a tool call id");
  }
  // MAX_ASK_QUESTIONS bounds the round; the tool itself caps at 4, and a
  // client claiming more is not describing an ask this bridge can deliver.
  if (answers !== undefined && answers.length > MAX_ASK_QUESTIONS) {
    throw new CommandError("answer_ask answers must be a bounded list");
  }
  const parsed = (answers ?? []).map((answer) => {
    if (!answer.questionId || answer.questionId.length > 200) {
      throw new CommandError("answer_ask answer needs a question card id");
    }
    if (
      answer.selectedLabels !== undefined &&
      (answer.selectedLabels.length > 32 ||
        answer.selectedLabels.some((label) => label.length > MAX_ANSWER_LENGTH))
    ) {
      throw new CommandError("answer_ask selectedLabels must be a bounded list of option labels");
    }
    return {
      questionId: answer.questionId,
      text: sanitizeAnswerText(answer.text ?? ""),
      selectedLabels: answer.selectedLabels ?? [],
    };
  });
  // An ask names live state of its own, so a missing pane already surfaces as
  // the "no open ask" 409; only the plain prompt needs the pane checked here.
  if (!callId) requireLivePane(deps, paneId);
  await answerAsk(answerDeps(deps), {
    paneId,
    callId: callId ?? "",
    answers: parsed,
    text: sanitizeAnswerText(text ?? ""),
  });
  return callId ?? "";
}

/** Cancel the ask on screen without answering it. */
export async function dismissSessionAsk(deps: ServerDeps, paneId: string): Promise<void> {
  if (!paneId) throw new CommandError("dismiss_ask requires paneId");
  requireLivePane(deps, paneId);
  await dismissAsk(answerDeps(deps), paneId);
}

/** Type a `/command [args]` into the pane and submit it. Returns the command sent. */
export async function runSlashCommand(deps: ServerDeps, paneId: string, text: string): Promise<string> {
  if (!paneId) throw new CommandError("slash_command requires paneId");
  const slashCommand = validateSlashCommand(text);
  requireLivePane(deps, paneId);
  await deps.herdr.paneSendInput(paneId, slashCommand, ["Enter"]);
  return slashCommand;
}

/** Type raw single-line text into the pane without submitting it. */
export async function sendSessionText(deps: ServerDeps, paneId: string, text: string): Promise<void> {
  if (!paneId) throw new CommandError("send_text requires paneId");
  // Same one-line/no-control-characters contract as answers: the text goes
  // into a PTY, but unlike answer text it must not be silently altered — the
  // caller sees a rejection instead.
  if (
    text.length === 0 ||
    text.length > MAX_ANSWER_LENGTH ||
    sanitizeAnswerText(text) !== text
  ) {
    throw new CommandError("send_text requires plain single-line text (max 4000 chars)");
  }
  requireLivePane(deps, paneId);
  await deps.herdr.paneSendText(paneId, text);
}

/**
 * Validate a `/command [args]` string for pane-native entry. Both known
 * backends share the slash grammar, so this is a wire-level rule.
 *
 * Newlines in the argument become spaces. The composer treats Enter as a
 * newline, and skill commands are filled with a trailing space so the user
 * can type a request — often after Enter. Flattening keeps one PTY submit
 * without rejecting the request.
 */
export function validateSlashCommand(text: string): string {
  if (text.length === 0 || text.length > 10_000) {
    throw new CommandError("slash command text must be 1 to 10000 characters");
  }
  const normalized = text.replace(/\r\n|\r|\n/g, " ");
  if (!normalized.startsWith("/") || !/^\/[^\s\p{Cc}]+(?:[ \t][^\r\n\p{Cc}]*)?$/u.test(normalized)) {
    throw new CommandError("invalid slash command text");
  }
  return normalized;
}

/**
 * Answering needs the question the card id refers to, so it reads the pane's
 * own transcript — the same memoized read the chat poll uses, so an answer
 * costs a stat in the steady state.
 */
function answerDeps(deps: ServerDeps): AnswerDeps {
  // SAFETY: feed snapshot is a SessionSnapshot or absent; we read only the
  // pane/agent ids it carries, which the snapshot type guarantees.
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

// ── Legacy /ws command compatibility ──────────────────────────────────
//
// Everything below is the pre-`commands.http.v1` frame vocabulary, kept only
// so installed APKs built before the HTTP command routes keep working during
// the rollout window. Current app builds must never reach it (see
// `routes/session-commands.ts`); do not add a mutation verb here.

/** The legacy mutation frames, and nothing else. */
export type LegacyCommandMessage =
  | { type: "steer"; target: string; text: string }
  | {
      type: "answer_ask";
      paneId: string;
      callId?: string;
      answers?: AskAnswerInput[];
      text?: string;
    }
  | { type: "dismiss_ask"; paneId: string }
  | { type: "slash_command"; paneId: string; text: string }
  | { type: "send_text"; paneId: string; text: string };

/**
 * Every frame a `/ws` client may send: the live feed vocabulary, which
 * `server.ts` answers on the connection, plus the legacy mutations below.
 */
export type CommandMessage = LegacyCommandMessage | { type: "ping" } | { type: "subscribe"; filter?: string[] };

export type CommandResult =
  | { type: "steered"; target: string; result: unknown }
  | { type: "answered"; paneId: string; callId: string }
  | { type: "dismissed"; paneId: string }
  | { type: "command_sent"; paneId: string; text: string }
  | { type: "sent"; paneId: string };

/**
 * Legacy WS frame adapter: parses one command frame and calls the shared
 * operation above, then shapes the ack frame old clients expect. It holds no
 * validation of its own, so the two surfaces cannot drift.
 *
 * `ping` and `subscribe` never arrive here: they are the live feed
 * vocabulary, not mutations, and `server.ts` answers them on the connection
 * (that is where the subscribe filter set lives anyway). This switch is
 * reachable only for the frozen legacy mutation frames.
 */
export async function handleLegacyWsCommand(
  command: LegacyCommandMessage,
  deps: ServerDeps,
): Promise<CommandResult> {
  switch (command.type) {
    case "steer": {
      const result = await steerSession(deps, command.target, command.text);
      return { type: "steered", target: command.target, result };
    }
    case "answer_ask": {
      const callId = await answerSessionAsk(deps, command);
      return { type: "answered", paneId: command.paneId, callId };
    }
    case "dismiss_ask":
      await dismissSessionAsk(deps, command.paneId);
      return { type: "dismissed", paneId: command.paneId };
    case "slash_command": {
      const text = await runSlashCommand(deps, command.paneId, command.text);
      return { type: "command_sent", paneId: command.paneId, text };
    }
    case "send_text":
      await sendSessionText(deps, command.paneId, command.text);
      return { type: "sent", paneId: command.paneId };
    default: {
      const exhaustive: never = command;
      throw new CommandError(`unknown command ${JSON.stringify(exhaustive)}`);
    }
  }
}
