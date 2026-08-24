import { realpathSync } from "node:fs";
import { isAbsolute, join, relative, resolve } from "node:path";
import type { HerdrPort } from "../../herdr/port.js";
import type { AgentSessionInfo } from "../../herdr/types.js";
import {
  readRecordLines,
  readTranscriptText,
  type Transcript,
  type TranscriptReadOpts,
} from "../../transcript.js";
import { closeSessionPane } from "../../herdr/panes.js";
import { shellQuote } from "../../shell.js";
import type {
  AgentBackend,
  AskRequest,
  ControlAction,
  ControlParams,
  LaunchParams,
} from "../types.js";
import { parseAgyTranscript } from "./transcript.js";
import { readAgyModelsCatalog } from "./models.js";
import { readAgyCommandsCatalog } from "./commands.js";
import { AGY_ASK_QUESTION_TOOL, extractAgyQuestions } from "./questions.js";
import { scanAskQuestions, ASK_USER_QUESTION_TOOL, type QuestionEntry } from "../../questions.js";

/** Antigravity / Gemini config dir honors ANTIGRAVITY_CONFIG_DIR or GEMINI_CONFIG_DIR. */
export function agyConfigDir(): string {
  return (
    process.env.ANTIGRAVITY_CONFIG_DIR?.trim() ||
    process.env.GEMINI_CONFIG_DIR?.trim() ||
    `${process.env.HOME ?? ""}/.gemini/antigravity-cli`
  );
}

export function agySessionRoot(): string {
  return resolve(agyConfigDir(), "brain");
}

export function agyLaunchCommand(params: LaunchParams): string {
  const parts = ["agy"];
  if (params.model) parts.push("--model", shellQuote(params.model));
  if (params.thinkingLevel) parts.push("--effort", shellQuote(params.thinkingLevel));
  return parts.join(" ");
}

/** Extract conversation ID from a brain transcript path or return the string as id. */
export function agyResumeCommand(path: string, mode: "resume" | "fork"): string {
  if (mode === "fork") {
    throw new Error("agy has no fork-at-path launch; resume the session and continue");
  }
  const match = path.match(/brain\/([^/\\]+)/);
  const id = match && match[1] ? match[1] : path.replace(/\.jsonl$/, "").split(/[\\/]/).pop() ?? path;
  return `agy --conversation ${shellQuote(id)}`;
}

/** Containment check for AGY transcripts in the brain store. */
export function agyOwnsSessionPath(path: string): boolean {
  let root = resolve(agySessionRoot());
  try {
    root = realpathSync(root);
  } catch {
    // keep lexical root
  }
  let target = resolve(path);
  try {
    target = realpathSync(target);
  } catch {
    // live pane may not have created its session file yet
  }
  const rel = relative(root, target);
  return rel !== "" && !rel.startsWith("..") && !isAbsolute(rel) && target.endsWith(".jsonl");
}

export async function agyResolveSessionPath(ref: AgentSessionInfo, _cwd?: string): Promise<string | null> {
  if (ref.kind === "path") return ref.value;
  return join(agySessionRoot(), ref.value, ".system_generated", "logs", "transcript.jsonl");
}

/** The phrase [agyReadTranscriptState] exists to find (see `extractModelFromSettings`). */
const AGY_STATE_RECORDS = ["Model Selection"] as const;

export async function agyReadTranscript(path: string, opts?: TranscriptReadOpts): Promise<Transcript> {
  const transcript = parseAgyTranscript(await readTranscriptText(path, opts), opts ?? {});
  if (!transcript.id) {
    const match = path.match(/brain\/([^/\\]+)/);
    if (match && match[1]) transcript.id = match[1];
  }
  return transcript;
}

export async function agyReadTranscriptState(path: string, fromByte?: number): Promise<Transcript> {
  const opts: TranscriptReadOpts = fromByte === undefined
    ? { metadataOnly: true, exactMetadata: true }
    : { metadataOnly: true, fromByte };
  // Exact, and still cheap: agy announces a model or effort change inside a
  // user record that always names it, so the scan can reach every one of them
  // without parsing an agent step.
  const text = fromByte === undefined
    ? await readRecordLines(path, AGY_STATE_RECORDS)
    : await readTranscriptText(path, opts);
  const transcript = parseAgyTranscript(text, opts);
  if (!transcript.id) {
    const match = path.match(/brain\/([^/\\]+)/);
    if (match && match[1]) transcript.id = match[1];
  }
  return transcript;
}

/**
 * agy answers as plain text at its prompt — it has no keyboard-navigated
 * questionnaire — so an option pick is delivered as the option label itself.
 *
 * A multi-question ask therefore has no tab strip to walk: the whole round is
 * formatted as one prompt, one line per question, and sent once. That keeps
 * "one submit, one delivery" true across all three backends, rather than
 * dribbling each answer in as its own turn at agy's prompt.
 */
export async function agyAnswerAsk(herdr: HerdrPort, request: AskRequest): Promise<void> {
  const { paneId, group, answers } = request;
  const parts: string[] = [];
  if (group.length === 0) {
    parts.push(request.text);
  } else {
    for (const question of group) {
      const answer = answers.find((candidate) => candidate.questionId === question.id);
      if (!answer) throw new Error(`no answer for question ${question.id}`);
      const value = answer.text || answer.selectedLabels.join(", ");
      if (!value.trim()) throw new Error(`answer for ${question.id} is empty`);
      // A lone question needs no labelling; a round does, or the agent cannot
      // tell which answer belongs to which question.
      parts.push(group.length === 1 ? value : `${question.header || question.question}: ${value}`);
    }
  }
  const singleLine = parts.join("; ").replace(/[\r\n\u2028\u2029]+/g, " ");
  if (!singleLine.trim()) throw new Error("answer text is empty");
  await herdr.paneSendText(paneId, singleLine);
  await herdr.paneSendKeys(paneId, ["Enter"]);
}

/**
 * agy has no questionnaire to close, so escape here is its ordinary abort —
 * it cancels the agent's turn, not just the question on screen.
 */
export async function agyDismissAsk(herdr: HerdrPort, paneId: string): Promise<void> {
  await herdr.paneSendKeys(paneId, ["escape"]);
}

export async function agyControl(herdr: HerdrPort, params: ControlParams): Promise<void> {
  const { paneId, action, text } = params;
  switch (action) {
    case "abort":
      await herdr.paneSendKeys(paneId, ["escape"]);
      return;
    case "compact":
      await herdr.paneSendInput(paneId, "/compact", ["Enter"]);
      return;
    case "close": {
      await closeSessionPane(herdr, paneId);
      return;
    }
    case "set_model": {
      if (!text || text.length > 200 || /[\u0000-\u001f\u007f]/.test(text)) {
        throw new Error("valid model is required");
      }
      await herdr.paneSendInput(paneId, `/model ${text}`, ["Enter"]);
      return;
    }
    case "set_thinking": {
      if (!text || text.length > 200 || /[\u0000-\u001f\u007f]/.test(text)) {
        throw new Error("valid effort level is required");
      }
      await herdr.paneSendInput(paneId, `/effort ${text}`, ["Enter"]);
      return;
    }
    default: {
      // SAFETY: exhaustive switch — every other ControlAction is handled above;
      // the default branch is unreachable, so narrowing `action` to never is sound.
      const exhaustive: never = action as never;
      throw new Error(`unsupported control action for agy: ${String(exhaustive)}`);
    }
  }
}

export const AGY_CAPABILITIES: ReadonlySet<ControlAction> = new Set([
  "abort",
  "compact",
  "close",
  "set_model",
  "set_thinking",
]);

/** Question cards of the whole session, without parsing every entry. */
export async function agyReadQuestions(path: string): Promise<QuestionEntry[]> {
  return scanAskQuestions(
    path,
    [AGY_ASK_QUESTION_TOOL, ASK_USER_QUESTION_TOOL],
    (text) => parseAgyTranscript(text).entries,
    extractAgyQuestions,
  );
}

export const agyBackend: AgentBackend = {
  id: "agy",
  displayName: "Antigravity",
  capabilities: AGY_CAPABILITIES,
  hasModelCatalog: true,
  hasSlashCommands: true,

  launchCommand: agyLaunchCommand,
  resumeCommand: agyResumeCommand,
  sessionRoot: agySessionRoot,
  ownsSessionPath: agyOwnsSessionPath,
  resolveSessionPath: agyResolveSessionPath,
  readTranscript: agyReadTranscript,
  readTranscriptState: agyReadTranscriptState,
  extractQuestions: (transcript) => extractAgyQuestions(transcript.entries),
  readQuestions: agyReadQuestions,
  answerAsk: agyAnswerAsk,
  dismissAsk: agyDismissAsk,
  control: agyControl,
  models: readAgyModelsCatalog,
  commands: (cwd?: string) => readAgyCommandsCatalog(cwd),
};
