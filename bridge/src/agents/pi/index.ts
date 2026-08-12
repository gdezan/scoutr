import { realpathSync } from "node:fs";
import { isAbsolute, relative, resolve } from "node:path";
import { BridgeError } from "../../errors.js";
import type { HerdrPort } from "../../herdr/port.js";
import type { AgentSessionInfo } from "../../herdr/types.js";
import type { QuestionEntry } from "../../questions.js";
import { extractQuestions, sanitizeAnswerText } from "../../questions.js";
import {
  MAX_SESSION_TITLE_LENGTH,
  readTranscriptText,
  type Transcript,
  type TranscriptReadOpts,
} from "../../transcript.js";
import { findPaneSessionPath, findPaneWorkspace } from "../../herdr/panes.js";
import { shellQuote } from "../../shell.js";
import type {
  AgentBackend,
  ControlAction,
  ControlParams,
  LaunchParams,
  ModelsCatalog,
} from "../types.js";
import { parsePiTranscript, writePiSessionTitle } from "./transcript.js";
import { readModelsCatalog } from "./models.js";
import { readCommandsCatalog } from "./commands.js";

const MAX_MODEL_LENGTH = 200;
const CONTROL_CHAR = /[\u0000-\u001f\u007f]/;

/** pi's documented `--thinking` levels (README: Model Options). */
export const THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"] as const;
export type ThinkingLevel = (typeof THINKING_LEVELS)[number];

/** Keys needed to cycle from the active thinking level to an explicit target. */
export function thinkingLevelKeys(current: string, target: string, available: string[]): string[] {
  const currentIndex = available.indexOf(current);
  const targetIndex = available.indexOf(target);
  if (targetIndex === -1) throw new Error(`${target} is not supported by the active model`);
  if (currentIndex === -1) throw new Error(`active thinking level is unknown: ${current}`);
  const count = (targetIndex - currentIndex + available.length) % available.length;
  return Array.from({ length: count }, () => "shift+tab");
}

/** POSIX single-quote escaping: the value survives any shell as one literal argument. */
export function piLaunchCommand(params: LaunchParams): string {
  const parts = ["pi", "--model", shellQuote(params.model ?? "")];
  if (params.thinkingLevel) parts.push("--thinking", shellQuote(params.thinkingLevel));
  if (params.name) parts.push("--name", shellQuote(params.name));
  return parts.join(" ");
}

export function piResumeCommand(path: string, mode: "resume" | "fork"): string {
  return `pi --${mode === "fork" ? "fork" : "session"} ${shellQuote(path)}`;
}

export function piSessionRoot(): string {
  const sessionRoot = process.env.PI_CODING_AGENT_SESSION_DIR?.trim();
  if (sessionRoot) return resolve(sessionRoot);
  const agentRoot = process.env.PI_CODING_AGENT_DIR?.trim() || `${process.env.HOME ?? ""}/.pi/agent`;
  return resolve(agentRoot, "sessions");
}

/**
 * Canonical containment check: both sides are realpath'd so a symlinked
 * session root (e.g. ~/.pi -> /data/.pi) still claims its own files, which
 * the catalog canonicalizes before dispatch.
 */
export function piOwnsSessionPath(path: string): boolean {
  let root = resolve(piSessionRoot());
  try {
    root = realpathSync(root);
  } catch {
    // keep the lexical root when the store does not exist yet
  }
  let target = resolve(path);
  try {
    target = realpathSync(target);
  } catch {
    // a live pane may not have created its session file yet
  }
  const rel = relative(root, target);
  return rel !== "" && !rel.startsWith("..") && !isAbsolute(rel) && target.endsWith(".jsonl");
}

/** pi panes report a path directly; an id-kind reference has no pi meaning. */
export async function piResolveSessionPath(ref: AgentSessionInfo): Promise<string | null> {
  return ref.kind === "path" ? ref.value : null;
}

export async function piReadTranscript(path: string, opts?: TranscriptReadOpts): Promise<Transcript> {
  return parsePiTranscript(await readTranscriptText(path, opts), opts ?? {});
}

export async function piRenameStoredSession(path: string, title: string): Promise<void> {
  await writePiSessionTitle(path, title);
}

export function piExtractQuestions(transcript: Transcript): QuestionEntry[] {
  return extractQuestions(transcript.entries);
}

export async function piAnswerQuestion(
  herdr: HerdrPort,
  paneId: string,
  answer: string,
  keys: string[] = [],
  trailingKeys?: string[],
): Promise<void> {
  const safe = sanitizeAnswerText(answer);
  if (keys.length > 0) {
    // pi's questionnaire is keyboard-only: arrows move, space toggles,
    // enter chooses/submits. Typed text is dropped while an option list is
    // focused (and enter would pick the first option), so option/skip
    // answers travel entirely as keys; custom answers open the "Type
    // something" editor with keys, then type the text and submit it with
    // the trailing keys (editor enter, plus a review-tab enter for the last
    // question of a multi-question ask).
    await herdr.paneSendKeys(paneId, keys);
    if (safe) {
      await herdr.paneSendText(paneId, safe);
      await herdr.paneSendKeys(paneId, trailingKeys ?? ["Enter"]);
    }
    return;
  }
  // No questionnaire known (e.g. a permission prompt): plain type+enter.
  await herdr.paneSendText(paneId, safe);
  await herdr.paneSendKeys(paneId, ["Enter"]);
}

export async function piControl(herdr: HerdrPort, params: ControlParams): Promise<void> {
  const { paneId, action, text } = params;
  switch (action) {
    case "abort":
      await herdr.paneSendKeys(paneId, ["escape"]);
      return;
    case "retry": {
      if (!text) throw new Error("retry needs the last user message");
      await herdr.agentPrompt(paneId, text);
      return;
    }
    case "compact":
      await herdr.paneSendInput(paneId, "/compact", ["Enter"]);
      return;
    case "fork":
      await herdr.paneSendInput(paneId, "/fork", ["Enter"]);
      return;
    case "rename": {
      const name = text?.trim() ?? "";
      if (!name) throw new Error("rename needs a label");
      if (name.length > MAX_SESSION_TITLE_LENGTH) {
        throw new Error(`name is too long (max ${MAX_SESSION_TITLE_LENGTH} characters)`);
      }
      if (CONTROL_CHAR.test(name)) throw new Error("name must not contain control characters");
      const workspaceId = await findPaneWorkspace(herdr, paneId);
      if (!workspaceId) throw new BridgeError("pane not found in the snapshot", 404);
      await herdr.paneSendInput(paneId, `/name ${name}`, ["Enter"]);
      await herdr.workspaceRename(workspaceId, name);
      return;
    }
    case "close": {
      const workspaceId = await findPaneWorkspace(herdr, paneId);
      if (!workspaceId) throw new BridgeError("pane not found in the snapshot", 404);
      await herdr.workspaceClose(workspaceId);
      return;
    }
    case "set_model": {
      const model = requireCatalogModel(readModelsCatalog(), text);
      await herdr.paneSendInput(paneId, `/model ${model.provider}/${model.id}`, ["Enter"]);
      return;
    }
    case "set_thinking": {
      if (!text || !(THINKING_LEVELS as readonly string[]).includes(text)) {
        throw new Error(`unknown thinking level: ${String(text)}`);
      }
      const path = await findPaneSessionPath(herdr, paneId);
      if (!path) throw new BridgeError("active pi session path is unavailable", 409);
      const session = await piReadTranscript(path);
      if (!session.model || !session.thinkingLevel) {
        throw new BridgeError("active model or thinking level is unavailable", 409);
      }
      const model = requireCatalogModel(readModelsCatalog(), session.model);
      const keys = thinkingLevelKeys(session.thinkingLevel, text, model.thinkingLevels);
      if (keys.length > 0) await herdr.paneSendKeys(paneId, keys);
      return;
    }
    default: {
      const exhaustive: never = action as never;
      throw new Error(`unsupported control action: ${String(exhaustive)}`);
    }
  }
}

function requireCatalogModel(catalog: ModelsCatalog, key: string | undefined) {
  if (!key || key.length > MAX_MODEL_LENGTH || CONTROL_CHAR.test(key)) throw new Error("valid model is required");
  const model = catalog.providers
    .flatMap((provider) => provider.models)
    .find((candidate) => `${candidate.provider}/${candidate.id}` === key);
  if (!model) throw new Error(`model is not available: ${key}`);
  return model;
}

export const PI_CAPABILITIES: ReadonlySet<ControlAction> = new Set([
  "abort",
  "retry",
  "compact",
  "fork",
  "rename",
  "close",
  "set_model",
  "set_thinking",
]);

export const piBackend: AgentBackend = {
  id: "pi",
  displayName: "Pi",
  capabilities: PI_CAPABILITIES,
  hasModelCatalog: true,
  hasSlashCommands: true,

  launchCommand: piLaunchCommand,
  resumeCommand: piResumeCommand,
  sessionRoot: piSessionRoot,
  ownsSessionPath: piOwnsSessionPath,
  resolveSessionPath: piResolveSessionPath,
  readTranscript: piReadTranscript,
  renameStoredSession: piRenameStoredSession,
  extractQuestions: piExtractQuestions,
  answerQuestion: piAnswerQuestion,
  control: piControl,
  models: readModelsCatalog,
  commands: (cwd?: string) => readCommandsCatalog(cwd),
};
