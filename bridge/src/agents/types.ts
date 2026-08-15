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
  /** Active model, when the session controller can resolve it for model-specific controls. */
  model?: string;
}

/**
 * Where an agent's questionnaire stands part-way through a multi-question ask.
 *
 * Both TUIs keep every question of one ask on screen as a tab strip and only
 * write the answers to the transcript when the whole ask is submitted, so the
 * in-flight state cannot be recovered from the session file — the bridge
 * carries it between answers instead (see `answers.ts`).
 */
export interface AnswerProgress {
  /** Question ids already answered in this ask. */
  answered: string[];
  /** Tab the questionnaire is showing now; index `n` is the submit tab. */
  cursorTab: number;
}

/** One answer to deliver into a pane, in the agent's own questionnaire. */
export interface AnswerRequest {
  paneId: string;
  /** The question being answered; null when the pane shows no questionnaire. */
  question: QuestionEntry | null;
  /** Every question of the same ask, in ask order; empty when question is null. */
  group: QuestionEntry[];
  /** Progress left by earlier answers to this ask. */
  progress: AnswerProgress | null;
  /** Free-text answer; "" when the user picked authored options. */
  text: string;
  /** Option labels the user picked, in the card's order. */
  selectedLabels: string[];
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
 * One coding-agent implementation behind Scoutr's backend seam. Herdr owns
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
  /**
   * Deliver one answer into the pane. The backend owns its TUI's grammar —
   * which keys move between questions, how an option is picked, how a custom
   * answer is typed, how the ask is submitted — and returns the progress the
   * next answer to the same ask starts from (null when there is none).
   */
  answerQuestion(herdr: HerdrPort, request: AnswerRequest): Promise<AnswerProgress | null>;
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
