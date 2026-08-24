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

/** One question's answer inside a batched ask. */
export interface AskAnswer {
  /** Card id of the question this answers; must name a question in the group. */
  questionId: string;
  /** Free-text answer; "" when the user picked authored options. */
  text: string;
  /** Option labels the user picked, in the card's order. */
  selectedLabels: string[];
}

/**
 * A whole ask, answered in one delivery.
 *
 * Neither TUI commits a questionnaire until its submit tab, so the bridge does
 * not either: the app buffers the round and sends every answer at once, and the
 * backend walks the tab strip start to finish in a single plan. That keeps the
 * in-flight tab position a local variable inside one delivery instead of state
 * the daemon has to carry between requests (see `answers.ts`, ADR 0011).
 */
export interface AskRequest {
  paneId: string;
  /** Every question of the ask, in ask order; empty when the pane is blocked on a plain prompt. */
  group: QuestionEntry[];
  /** One answer per question in `group`, in the same order. */
  answers: AskAnswer[];
  /** Free-text typed at a plain blocked prompt; "" when answering a questionnaire. */
  text: string;
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
  /** Read only model/thinking metadata, exactly once or from an append byte range. */
  readTranscriptState(path: string, fromByte?: number): Promise<Transcript>;
  renameStoredSession?(path: string, title: string): Promise<void>;

  extractQuestions(transcript: Transcript): QuestionEntry[];
  /**
   * Every question card of the session at [path], read straight from the file
   * without normalizing every entry (see `scanAskQuestions`). A bounded Chat
   * page cannot derive authoritative question state from the entries it
   * displays, so it asks the backend for it separately.
   */
  readQuestions(path: string): Promise<QuestionEntry[]>;
  /**
   * Fingerprint of question state this backend keeps *outside* the transcript
   * file, cheap enough to call on every board poll. Claude's open ask is a
   * hook-written sidecar that appears and disappears while the transcript stat
   * never moves, so a cache keyed on the transcript alone would serve a stale
   * ask; it includes this stamp instead. Backends whose questions live only in
   * the transcript leave it undefined.
   */
  questionStateStamp?(path: string): string;
  /**
   * Deliver a whole ask into the pane in one pass. The backend owns its TUI's
   * grammar — which keys move between questions, how an option is picked, how
   * a custom answer is typed, how the ask is submitted. It either lands the
   * complete ask or throws; there is no partial-success return, because a
   * half-walked tab strip is not a state any caller can resume from.
   */
  answerAsk(herdr: HerdrPort, request: AskRequest): Promise<void>;

  /**
   * Cancel the questionnaire on screen without answering it. Sends whatever
   * key the agent's TUI reads as "escape this prompt"; the pane is left at
   * whatever the agent does next.
   */
  dismissAsk(herdr: HerdrPort, paneId: string): Promise<void>;
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
