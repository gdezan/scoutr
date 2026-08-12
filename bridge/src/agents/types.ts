import type { HerdrPort } from "../herdr/port.js";
import type { AgentSessionInfo } from "../herdr/types.js";
import type { QuestionEntry } from "../questions.js";
import type { Transcript, TranscriptReadOpts } from "../transcript.js";

export type ControlAction =
  | "abort"
  | "retry"
  | "compact"
  | "fork"
  | "rename"
  | "close"
  | "set_model"
  | "set_thinking";

export interface LaunchParams {
  model?: string;
  thinkingLevel?: string;
  name?: string;
}

export interface ControlParams {
  paneId: string;
  action: ControlAction;
  text?: string;
}

export interface ModelInfo {
  id: string;
  name: string;
  provider: string;
  reasoning: boolean;
  thinkingLevels: string[];
  contextWindow: number | null;
}

export interface ProviderModels {
  name: string;
  models: ModelInfo[];
}

export interface ModelsCatalog {
  providers: ProviderModels[];
}

export interface CommandInfo {
  name: string;
  description: string;
  source: "builtin" | "extension" | "prompt" | "skill";
  argumentHint?: string;
}

export interface CommandsCatalog {
  commands: CommandInfo[];
}

/**
 * One coding-agent implementation behind Cockpit's backend seam. Herdr owns
 * detection and lifecycle state; this adapter owns the agent's CLI grammar,
 * transcript format, filesystem boundary, catalogs, questions, and controls.
 */
export interface AgentBackend {
  readonly id: string;
  readonly displayName: string;
  readonly capabilities: ReadonlySet<ControlAction>;
  readonly hasModelCatalog: boolean;
  readonly hasSlashCommands: boolean;

  launchCommand(params: LaunchParams): string;
  resumeCommand(path: string, mode: "resume" | "fork"): string;

  sessionRoot(): string;
  ownsSessionPath(path: string): boolean;
  /** Resolve a herdr agent_session ref to a transcript path; `cwd` lets id-kind backends predict not-yet-written paths. */
  resolveSessionPath(ref: AgentSessionInfo, cwd?: string): Promise<string | null>;
  readTranscript(path: string, opts?: TranscriptReadOpts): Promise<Transcript>;
  renameStoredSession?(path: string, title: string): Promise<void>;

  extractQuestions(transcript: Transcript): QuestionEntry[];
  answerQuestion(herdr: HerdrPort, paneId: string, answer: string, keys?: string[], trailingKeys?: string[]): Promise<void>;
  control(herdr: HerdrPort, params: ControlParams): Promise<void>;

  /**
   * Deliver the initial prompt into a freshly launched pane. Defaults to
   * `herdr.agentPrompt`; agents whose TUI is not ready to accept input at
   * launch (claude drops prompts typed in the first ~2s) override this with
   * a verify-and-retry loop.
   */
  deliverInitialPrompt?(herdr: HerdrPort, paneId: string, text: string): Promise<void>;

  models(): ModelsCatalog;
  commands(cwd?: string): Promise<CommandsCatalog>;
}

export interface AgentKindInfo {
  id: string;
  displayName: string;
  capabilities: ControlAction[];
  hasModelCatalog: boolean;
  hasSlashCommands: boolean;
}
