import { readdir } from "node:fs/promises";
import { realpathSync } from "node:fs";
import { isAbsolute, join, relative, resolve } from "node:path";
import type { HerdrPort } from "../../herdr/port.js";
import type { AgentSessionInfo } from "../../herdr/types.js";
import {
  readTranscriptText,
  type Transcript,
  type TranscriptReadOpts,
} from "../../transcript.js";
import { closeSessionPane } from "../../herdr/panes.js";
import { shellQuote } from "../../shell.js";
import { scanAskQuestions, type QuestionEntry } from "../../questions.js";
import type {
  AgentBackend,
  AskRequest,
  ControlAction,
  ControlParams,
  LaunchParams,
} from "../types.js";
import { parseClaudeTranscript } from "./transcript.js";
import { claudeEffortArg, claudeModelArg, readClaudeModelsCatalog } from "./models.js";
import { readClaudeCommandsCatalog } from "./commands.js";
import { CLAUDE_ASK_TOOL, claudeQuestions, extractClaudeQuestions, mergePendingAsk } from "./questions.js";
import {
  attachPendingAskPreamble,
  clearPendingAsk,
  pendingAskStamp,
  readPendingAsk,
} from "./pending-asks.js";
import { extractAskPreamble } from "./ask-preamble.js";
import { claudeAskPlan } from "./questionnaire.js";
import { extractClaudeAgentTasks } from "./tasks.js";

/** Claude config dir honors CLAUDECONFIGDIR (default ~/.claude), like the herdr hook. */
export function claudeConfigDir(): string {
  return process.env.CLAUDECONFIGDIR?.trim() || `${process.env.HOME ?? ""}/.claude`;
}

export function claudeSessionRoot(): string {
  return resolve(claudeConfigDir(), "projects");
}

export function claudeLaunchCommand(params: LaunchParams): string {
  const parts = ["claude"];
  if (params.model) parts.push("--model", shellQuote(claudeModelArg(params.model)));
  const effort = claudeEffortArg(params.thinkingLevel, params.model);
  if (effort) parts.push("--effort", shellQuote(effort));
  if (params.name) parts.push("--name", shellQuote(params.name));
  if (params.scoutrContext) parts.push("--append-system-prompt", shellQuote(params.scoutrContext));
  return parts.join(" ");
}

/** Claude resumes by session **id**; the path's basename is the session uuid. */
export function claudeResumeCommand(path: string, mode: "resume" | "fork", scoutrContext?: string): string {
  if (mode === "fork") throw new Error("claude has no fork-at-path launch; resume the session and use /fork");
  const id = path.replace(/\.jsonl$/, "").split(/[\\/]/).pop() ?? path;
  // System-prompt flags are per-invocation for Claude (not persisted in the
  // session), so the resume command must re-carry the Scoutr context itself.
  const parts = [`claude --resume ${shellQuote(id)}`];
  if (scoutrContext) parts.push("--append-system-prompt", shellQuote(scoutrContext));
  return parts.join(" ");
}

/** Canonical containment check, symmetric with the pi adapter (see piOwnsSessionPath). */
export function claudeOwnsSessionPath(path: string): boolean {
  let root = resolve(claudeSessionRoot());
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

const MAX_SCAN_FILES = 5_000;
const MAX_SCAN_DEPTH = 3;

/**
 * Claude's on-disk project directory name for a cwd: every character outside
 * [a-zA-Z0-9] becomes "-" (verified empirically against 2.1.228: `/tmp/claude
 * enc.dir/α space` -> `-tmp-claude-enc-dir---space`). The bridge must derive
 * it because Claude Code 2.1.228 writes the transcript JSONL lazily — only
 * after the first exchange — so a fresh idle session has no file to walk to.
 */
export function claudeProjectDir(cwd: string): string {
  return cwd.replace(/[^a-zA-Z0-9]/g, "-");
}

/**
 * Resolve a herdr agent_session reference to a transcript path. Claude reports
 * `kind: "id"` (the hook sends agent_session_id, not a path), so an id is
 * matched against `~/.claude/projects/<project>/<uuid>.jsonl` filenames. When
 * the file does not exist yet (fresh session, transcript written lazily on
 * the first exchange), the deterministic path is derived from the pane's cwd
 * so the live descriptor can converge on its canonical session key.
 */
export async function claudeResolveSessionPath(ref: AgentSessionInfo, cwd?: string): Promise<string | null> {
  if (ref.kind === "path") return ref.value;
  const root = claudeSessionRoot();
  const wanted = `${ref.value}.jsonl`;
  let visited = 0;
  const walk = async (dir: string, depth: number): Promise<string | null> => {
    if (depth > MAX_SCAN_DEPTH) return null;
    let entries;
    try {
      entries = await readdir(dir, { withFileTypes: true });
    } catch {
      return null;
    }
    for (const entry of entries) {
      if (visited++ >= MAX_SCAN_FILES) return null;
      if (entry.name.startsWith(".")) continue;
      const full = join(dir, entry.name);
      if (entry.isFile() && entry.name === wanted) return full;
      if (entry.isDirectory()) {
        const found = await walk(full, depth + 1);
        if (found) return found;
      }
    }
    return null;
  };
  const found = await walk(root, 0);
  if (found) return found;
  if (!cwd) return null;
  return join(root, claudeProjectDir(cwd), wanted);
}

export async function claudeReadTranscript(path: string, opts?: TranscriptReadOpts): Promise<Transcript> {
  return parseClaudeTranscript(await readTranscriptText(path, opts), opts ?? {});
}

export async function claudeReadTranscriptState(path: string, fromByte?: number): Promise<Transcript> {
  if (fromByte !== undefined) {
    const opts: TranscriptReadOpts = { metadataOnly: true, fromByte };
    return parseClaudeTranscript(await readTranscriptText(path, opts), opts);
  }
  // Claude stamps the active model on every assistant record, so the newest
  // one inside the tail window *is* the newest one in the file — exact,
  // without reading the rest of it. Only a session whose last 64 KiB holds no
  // assistant record at all has to fall back to the whole-file scan.
  const tail = parseClaudeTranscript(await readTranscriptText(path, { tail: 1 }), { metadataOnly: true });
  if (tail.modelObservationSeen) return tail;
  const opts: TranscriptReadOpts = { metadataOnly: true, exactMetadata: true };
  return parseClaudeTranscript(await readTranscriptText(path, opts), opts);
}

export async function claudeReadTasks(path: string) {
  return extractClaudeAgentTasks(await claudeReadTranscript(path));
}

export function claudeExtractQuestions(transcript: Transcript): QuestionEntry[] {
  return claudeQuestions(transcript);
}

/**
 * Claude's open ask lives in a hook-written sidecar, not in the transcript, so
 * a caller memoizing on the transcript file alone cannot see it open or clear.
 * The path's basename is the session uuid (see [claudeResumeCommand]).
 */
export function claudeQuestionStateStamp(path: string): string {
  return pendingAskStamp(claudeSessionId(path));
}

/** The session uuid a transcript path names (see [claudeResumeCommand]). */
export function claudeSessionId(path: string): string {
  return path.replace(/\.jsonl$/, "").split(/[\\/]/).pop() ?? "";
}

/**
 * Question cards of the whole session, without parsing every entry. The
 * sidecar is keyed by session id, which the path carries — a scan that finds
 * no ask records has no transcript to read it from.
 */
export async function claudeReadQuestions(path: string): Promise<QuestionEntry[]> {
  const recorded = await scanAskQuestions(
    path,
    [CLAUDE_ASK_TOOL],
    (text) => parseClaudeTranscript(text).entries,
    extractClaudeQuestions,
  );
  return mergePendingAsk(claudeSessionId(path), recorded);
}

/**
 * How long a preamble read may hold up the agents poll it rides on. One ask
 * pays it once; a pane that cannot answer in this window leaves the card
 * without background rather than delaying every other card on the Board.
 */
const PREAMBLE_READ_TIMEOUT_MS = 2_000;

/**
 * Read the prose Claude wrote above the open ask off the pane, once.
 *
 * The transcript cannot supply it while the questionnaire is up — the whole
 * assistant turn is buffered until the round resolves — so the pane is the
 * only place it exists (see `ask-preamble.ts`). `visible` is the only
 * snapshot herdr will take of a pane that is not idle, which a blocked pane
 * is not: `recent_unwrapped` refuses with `agent_not_idle`.
 *
 * Best effort throughout. A failed read, an unreadable layout and a pane that
 * never had a preamble all end the same way — the ask is marked read and the
 * card renders exactly as it does today.
 */
export async function claudeCaptureAskContext(
  herdr: HerdrPort,
  paneId: string,
  path: string,
): Promise<void> {
  const sessionId = claudeSessionId(path);
  const pending = readPendingAsk(sessionId);
  if (!pending || pending.preambleCaptured) return;
  const read = await herdr
    .agentRead(paneId, "visible", { stripAnsi: true, requestTimeoutMs: PREAMBLE_READ_TIMEOUT_MS })
    .catch(() => null);
  attachPendingAskPreamble(sessionId, extractAskPreamble(read?.read?.text ?? ""));
}

/**
 * Gap between the individual key/text sends of one answer. Claude's TUI reads
 * stdin in chunks and misparses a burst that arrives as one chunk (a batched
 * `down down space` toggled the row the cursor had already left), so every
 * step is its own send, spaced far enough apart to land in its own read.
 */
export const CLAUDE_STEP_DELAY_MS = 60;

/**
 * Deliver a whole ask. With questions in hand the answers travel through the
 * AskUserQuestion questionnaire (see `questionnaire.ts`); without them the pane
 * is blocked on an ordinary prompt, so the text is typed and submitted.
 *
 * Steps are sent one at a time \u2014 batching them into a single chunk is misread
 * by the TUI \u2014 so a throw part-way leaves the questionnaire on an unknown tab.
 * That is deliberate and reported as-is: nothing here retries, because a replay
 * from tab 0 would re-toggle checkboxes the first pass already set.
 */
export async function claudeAnswerAsk(
  herdr: HerdrPort,
  request: AskRequest,
  stepDelayMs = CLAUDE_STEP_DELAY_MS,
): Promise<void> {
  const { paneId, group, answers, text } = request;
  if (group.length === 0) {
    const singleLine = text.replace(/[\r\n\u2028\u2029]+/g, " ");
    if (!singleLine.trim()) throw new Error("answer text is empty");
    await herdr.paneSendText(paneId, singleLine);
    await herdr.paneSendKeys(paneId, ["Enter"]);
    return;
  }
  const steps = claudeAskPlan(group, answers);
  for (const [index, step] of steps.entries()) {
    if (index > 0 && stepDelayMs > 0) await sleep(stepDelayMs);
    if (step.kind === "key") await herdr.paneSendKeys(paneId, [step.value]);
    else await herdr.paneSendText(paneId, step.value);
  }
}

/**
 * Cancel the questionnaire on screen. Escape closes the ask in the TUI, but
 * the card is served from the `PreToolUse` sidecar, which only `PostToolUse`
 * clears — and a cancelled tool call never reaches `PostToolUse`. Left alone
 * the sidecar outlives the ask and the card returns on the next poll, so the
 * bridge clears the file it wrote (see `pending-asks.ts`).
 */
export async function claudeDismissAsk(herdr: HerdrPort, paneId: string): Promise<void> {
  await herdr.paneSendKeys(paneId, ["escape"]);
  const sessionId = await claudePaneSessionId(herdr, paneId);
  if (sessionId) clearPendingAsk(sessionId);
}

/** Claude panes report `kind: "id"` — the session uuid the sidecar is keyed by. */
async function claudePaneSessionId(herdr: HerdrPort, paneId: string): Promise<string> {
  try {
    const snapshot = await herdr.snapshot();
    const pane = snapshot.panes.find((candidate) => candidate.pane_id === paneId);
    const agent = snapshot.agents.find((candidate) => candidate.pane_id === paneId);
    const session = pane?.agent_session ?? agent?.agent_session;
    if (!session) return "";
    if (session.kind === "id") return session.value;
    // A path-kind reference still names the session: Claude's transcript file
    // is `<session uuid>.jsonl`.
    return session.value.replace(/\.jsonl$/, "").split(/[\\/]/).pop() ?? "";
  } catch {
    return ""; // no snapshot: the escape still landed, the sidecar ages out
  }
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Deliver the initial prompt of a freshly launched session. Claude's TUI is
 * not ready to accept typed input for the first ~1-2s after launch, and a
 * prompt sent in that window is silently dropped by herdr (verified live:
 * a prompt at +950ms vanished, the retry at +10s landed). Send, verify the
 * pane actually shows the typed text, and re-send with backoff until it does.
 * `delays` is injectable for tests.
 */
export async function claudeDeliverInitialPrompt(
  herdr: HerdrPort,
  paneId: string,
  text: string,
  delays: number[] = [3_000, 5_000, 8_000],
): Promise<void> {
  // The pane renders the typed line; the first line of the prompt is the
  // delivery marker (long prompts may wrap, so only the head is matched).
  const marker = (text.split(/\r?\n/, 1)[0] ?? "").slice(0, 24);
  for (let attempt = 0; ; attempt += 1) {
    // Every attempt after the first re-reads the pane BEFORE re-sending: a
    // prompt that already landed (its echo scrolled off the 80-line window or
    // the post-send verification read failed) must not be delivered twice.
    if (attempt > 0) {
      const check = await herdr
        .agentRead(paneId, "recent_unwrapped", {
          lines: 200, // wider read: resistant to the echo scrolling away
          stripAnsi: true,
          requestTimeoutMs: 4_000,
        })
        .catch(() => null);
      if (check?.read?.text?.includes(marker)) return; // already delivered; do not double-send
      if (check === null) return; // cannot verify — never blind-resend
    }
    await herdr.agentPrompt(paneId, text);
    await sleep(2_500);
    const read = await herdr
      .agentRead(paneId, "recent_unwrapped", {
        lines: 80,
        stripAnsi: true,
        requestTimeoutMs: 4_000,
      })
      .catch(() => null);
    if (read?.read?.text?.includes(marker)) return;
    if (attempt >= delays.length) return; // give up quietly; the session is still usable manually
    await sleep(delays[attempt] ?? 3_000);
  }
}

export async function claudeControl(herdr: HerdrPort, params: ControlParams): Promise<void> {
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
      // Same control-character guard as the pi adapter: the text goes into a
      // PTY, so anything that could alter submission is rejected outright.
      if (!text || text.length > 200 || /\p{Cc}/u.test(text)) {
        throw new Error("valid model is required");
      }
      // The app addresses a model by its picker key (`anthropic/claude-opus-5`);
      // /model wants the model name alone.
      await herdr.paneSendInput(paneId, `/model ${claudeModelArg(text)}`, ["Enter"]);
      return;
    }
    case "set_thinking": {
      const effort = claudeEffortArg(text, params.model);
      if (!effort) throw new Error("valid effort level is required");
      await herdr.paneSendInput(paneId, `/effort ${effort}`, ["Enter"]);
      return;
    }
    default: {
      // SAFETY: exhaustive switch — every other ControlAction is handled above;
      // the default branch is unreachable, so narrowing `action` to never is sound.
      const exhaustive: never = action as never;
      throw new Error(`unsupported control action for claude: ${String(exhaustive)}`);
    }
  }
}

export const CLAUDE_CAPABILITIES: ReadonlySet<ControlAction> = new Set([
  "abort",
  "compact",
  "close",
  "set_model",
  "set_thinking",
]);

export const claudeBackend: AgentBackend = {
  id: "claude",
  displayName: "Claude Code",
  capabilities: CLAUDE_CAPABILITIES,
  hasModelCatalog: true,
  hasSlashCommands: true,

  launchCommand: claudeLaunchCommand,
  resumeCommand: claudeResumeCommand,
  sessionRoot: claudeSessionRoot,
  ownsSessionPath: claudeOwnsSessionPath,
  resolveSessionPath: claudeResolveSessionPath,
  readTranscript: claudeReadTranscript,
  readTranscriptState: claudeReadTranscriptState,
  extractTasks: extractClaudeAgentTasks,
  readTasks: claudeReadTasks,
  extractQuestions: claudeExtractQuestions,
  readQuestions: claudeReadQuestions,
  questionStateStamp: claudeQuestionStateStamp,
  captureAskContext: claudeCaptureAskContext,
  answerAsk: claudeAnswerAsk,
  dismissAsk: claudeDismissAsk,
  control: claudeControl,
  deliverInitialPrompt: claudeDeliverInitialPrompt,
  models: readClaudeModelsCatalog,
  commands: (cwd) => readClaudeCommandsCatalog(cwd, claudeConfigDir()),
};
