import { resolveBackendForPane } from "./agents/registry.js";
import type { AgentBackend, AskAnswer } from "./agents/types.js";
import type { HerdrPort } from "./herdr/port.js";
import type { SessionSnapshot } from "./herdr/types.js";
import type { QuestionEntry } from "./questions.js";
import { sanitizeAnswerText } from "./questions.js";
import { CommandError } from "./errors.js";

/**
 * Answering an ask, end to end.
 *
 * The app sends intent only — which questions, which option labels, what text
 * — and the bridge turns that into keystrokes, because the questionnaire
 * grammar belongs to the agent adapter (`AgentBackend.answerAsk`), not to a
 * client.
 *
 * A whole ask arrives in one command. Neither TUI commits a questionnaire
 * before its submit tab, so the app buffers the round and the bridge delivers
 * it in a single pass: there is no partly-answered ask to remember between
 * requests, and therefore no cross-request state here at all (ADR 0011).
 */
export interface AnswerCommand {
  paneId: string;
  /** The ask being answered; "" when the pane is blocked on a plain prompt. */
  callId: string;
  /** One answer per question of the ask. Empty when answering a plain prompt. */
  answers: AskAnswer[];
  /** Free-text for a plain blocked prompt; ignored when `callId` is set. */
  text: string;
}

export interface AnswerDeps {
  herdr: HerdrPort;
  snapshot: SessionSnapshot | null;
  /** Questions currently visible in the pane's session; [] when unknown. */
  readQuestions(backend: AgentBackend, paneId: string): Promise<QuestionEntry[]>;
}

export async function answerAsk(deps: AnswerDeps, command: AnswerCommand): Promise<void> {
  const { paneId, callId } = command;
  const text = sanitizeAnswerText(command.text);
  const backend = resolveBackendForPane(deps.snapshot, paneId);
  if (!backend) {
    // Unknown agent: no questionnaire grammar to speak, so the answer is
    // typed at whatever prompt the pane is showing.
    if (!text) throw new CommandError("answer_ask requires text for unknown agents");
    await deps.herdr.paneSendText(paneId, text);
    await deps.herdr.paneSendKeys(paneId, ["Enter"]);
    return;
  }

  if (!callId) {
    if (!text) throw new CommandError("answer_ask requires text or an ask");
    await backend.answerAsk(deps.herdr, { paneId, group: [], answers: [], text });
    return;
  }

  const questions = await deps.readQuestions(backend, paneId);
  const group = questions.filter((candidate) => candidate.callId === callId);
  // The ask went away (answered or escaped in the terminal) between the app
  // showing the card and this request: a conflict with live state, not bad input.
  if (group.length === 0) throw new CommandError(`no open ask ${callId} in this session`, 409);
  if (group.some((question) => question.answered)) throw new CommandError("ask is already answered", 409);

  // The submit tab will not accept an incomplete round, so a missing answer is
  // rejected here rather than discovered as a stuck questionnaire.
  const answers = group.map((question) => {
    const answer = command.answers.find((candidate) => candidate.questionId === question.id);
    if (!answer) throw new CommandError(`missing an answer for question ${question.id}`);
    const safe = sanitizeAnswerText(answer.text);
    if (!safe && answer.selectedLabels.length === 0) {
      throw new CommandError(`answer for ${question.id} has neither an option nor text`);
    }
    return { questionId: question.id, text: safe, selectedLabels: answer.selectedLabels };
  });

  await backend.answerAsk(deps.herdr, { paneId, group, answers, text: "" });
}

/**
 * Cancel the ask on screen without answering it. Each backend sends whatever
 * its TUI reads as "escape this prompt" and cleans up any state it keeps for
 * the open ask — for Claude that includes the `PreToolUse` sidecar, which no
 * hook would otherwise clear on a cancelled call.
 */
export async function dismissAsk(deps: AnswerDeps, paneId: string): Promise<void> {
  const backend = resolveBackendForPane(deps.snapshot, paneId);
  if (!backend) {
    await deps.herdr.paneSendKeys(paneId, ["escape"]);
    return;
  }
  await backend.dismissAsk(deps.herdr, paneId);
}
