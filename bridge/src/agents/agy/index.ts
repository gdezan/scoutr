import { realpathSync } from "node:fs";
import { isAbsolute, join, relative, resolve } from "node:path";
import { BridgeError } from "../../errors.js";
import type { HerdrPort } from "../../herdr/port.js";
import type { AgentSessionInfo } from "../../herdr/types.js";
import {
  readTranscriptText,
  type Transcript,
  type TranscriptReadOpts,
} from "../../transcript.js";
import { findPaneWorkspace } from "../../herdr/panes.js";
import { shellQuote } from "../../shell.js";
import type {
  AgentBackend,
  ControlAction,
  ControlParams,
  LaunchParams,
} from "../types.js";
import { parseAgyTranscript } from "./transcript.js";
import { readAgyModelsCatalog } from "./models.js";
import { readAgyCommandsCatalog } from "./commands.js";
import { extractAgyQuestions } from "./questions.js";

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

export async function agyReadTranscript(path: string, opts?: TranscriptReadOpts): Promise<Transcript> {
  const transcript = parseAgyTranscript(await readTranscriptText(path, opts), opts ?? {});
  if (!transcript.id) {
    const match = path.match(/brain\/([^/\\]+)/);
    if (match && match[1]) transcript.id = match[1];
  }
  return transcript;
}

export async function agyAnswerQuestion(
  herdr: HerdrPort,
  paneId: string,
  answer: string,
  keys?: string[],
  trailingKeys?: string[],
): Promise<void> {
  if (keys && keys.length > 0) {
    await herdr.paneSendKeys(paneId, keys);
  }
  const singleLine = answer.replace(/[\r\n\u2028\u2029]+/g, " ");
  if (singleLine.trim()) {
    await herdr.paneSendText(paneId, singleLine);
    await herdr.paneSendKeys(paneId, trailingKeys ?? ["Enter"]);
    return;
  }
  if (!keys || keys.length === 0) {
    throw new Error("answer text is empty");
  }
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
      const workspaceId = await findPaneWorkspace(herdr, paneId);
      if (!workspaceId) throw new BridgeError("pane not found in the snapshot", 404);
      await herdr.workspaceClose(workspaceId);
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
  extractQuestions: (transcript) => extractAgyQuestions(transcript.entries),
  answerQuestion: agyAnswerQuestion,
  control: agyControl,
  models: readAgyModelsCatalog,
  commands: (cwd?: string) => readAgyCommandsCatalog(cwd),
};
