import { basename } from "node:path";
import { inspectSessionFile, entryText, type Transcript } from "./transcript.js";
import { backendForSessionPath } from "./agents/registry.js";
import type { AgentBackend } from "./agents/types.js";
import type { QuestionEntry } from "./questions.js";

/**
 * Bounded per-agent board detail: the session title, active model, and latest meaningful
 * transcript line. Reads bounded head/tail windows and is memoized
 * by (path, mtime, size) so the 3s board poll does not re-read unchanged files.
 */

/** Entries kept from the tail window — the card shows one, with room to skip noise. */
const TAIL_ENTRIES = 40;
const MAX_ACTIVITY_LENGTH = 160;
const MEMO_CAP = 128;

/** The most an option list can hold and still be a one-tap board decision. */
const MAX_QUICK_ANSWER_OPTIONS = 3;

/**
 * Why a pane is waiting on the user, normalized across backends.
 *
 * `kind: "ask"` carries the newest open structured question group — the same
 * ids and authored options Chat would render, because both read the backend's
 * `extractQuestions`. `kind: "prompt"` is a blocked pane with no structured
 * ask: nothing is invented for it, the card's latest activity stays the
 * preview.
 */
export interface AttentionSummary {
  kind: "ask" | "prompt";
  /** Tool call id grouping the open ask; null for a plain prompt. */
  callId: string | null;
  /** Unanswered questions in the open group; 0 for a plain prompt. */
  questionCount: number;
  currentQuestion: AttentionQuestion | null;
  /** Whether one option tap submits the whole ask (see [canQuickAnswer]). */
  canQuickAnswer: boolean;
}

export interface AttentionQuestion {
  id: string;
  header: string;
  question: string;
  options: Array<{ label: string; description: string }>;
  multiSelect: boolean;
}

export interface BoardDetail {
  /** User-assigned session name, when the transcript has one. */
  title: string | null;
  model: string | null;
  thinkingLevel: string | null;
  /** Latest meaningful line (user/agent text or tool call). */
  latestActivity: string;
  /** Epoch ms of the entry that produced [latestActivity]. */
  latestActivityAtMs: number | null;
  /** The open ask this session is waiting on, when it has one. */
  attention: AttentionSummary | null;
}

interface MemoEntry {
  mtimeMs: number;
  size: number;
  /** Out-of-transcript question state (Claude's sidecar); "" when the backend has none. */
  questionStamp: string;
  backend: AgentBackend;
  detail: BoardDetail;
}

export class BoardDetailCache {
  private readonly memo = new Map<string, MemoEntry>();

  /** Read bounded transcript windows for [path]; unknown or unreadable files return null. */
  async detailFor(path: string): Promise<BoardDetail | null> {
    const info = await inspectSessionFile(path);
    if (!info.exists) return null;
    const cached = this.memo.get(path);
    if (
      cached
      && cached.mtimeMs === info.mtimeMs
      && cached.size === info.size
      // A Claude ask opens and clears without the transcript ever changing, so
      // the transcript stat alone would pin a stale (or missing) attention.
      && cached.questionStamp === questionStamp(cached.backend, path)
    ) {
      return cached.detail;
    }
    const backend = backendForSessionPath(path);
    if (!backend) return null;
    // Stamped before the read: a sidecar that changes mid-derivation then
    // mismatches on the next poll instead of being memoized as current.
    const stamp = questionStamp(backend, path);
    const transcript = await backend.readTranscript(path, { tail: TAIL_ENTRIES }).catch(() => null);
    if (!transcript) return null;
    const metadata = await backend.readTranscript(path, { metadataOnly: true }).catch(() => null);
    // The tail window alone misses a model recorded only near the top of a long
    // session (pi writes one `model_change` at launch), which is why some cards
    // showed no model at all. The metadata read spans both ends, so it answers
    // when the tail cannot.
    const detail = deriveBoardDetail(
      transcript,
      info.mtimeMs,
      metadata?.title,
      metadata?.model ?? null,
      metadata?.thinkingLevel ?? null,
      questionsOf(backend, transcript),
    );
    this.memo.set(path, {
      mtimeMs: info.mtimeMs,
      size: info.size,
      questionStamp: stamp,
      backend,
      detail,
    });
    if (this.memo.size > MEMO_CAP) {
      const oldest = this.memo.keys().next().value;
      if (oldest !== undefined) this.memo.delete(oldest);
    }
    return detail;
  }

  /** Forget memo entries for paths that no longer exist (called on pane close). */
  prune(knownPaths: ReadonlySet<string>): void {
    for (const path of this.memo.keys()) {
      if (!knownPaths.has(path)) this.memo.delete(path);
    }
  }

  get size(): number {
    return this.memo.size;
  }
}

/** Session title, model, and latest meaningful line from a transcript tail. */
export function deriveBoardDetail(
  transcript: Transcript,
  mtimeMs: number,
  title = transcript.title,
  fallbackModel: string | null = null,
  fallbackThinkingLevel: string | null = null,
  questions: readonly QuestionEntry[] = [],
): BoardDetail {
  const attention = currentAttention(questions);
  const model = transcript.model ?? fallbackModel;
  const thinkingLevel = transcript.thinkingLevel ?? fallbackThinkingLevel;
  for (const entry of [...transcript.entries].reverse()) {
    // entryText's own cap is well above MAX_ACTIVITY_LENGTH, so cleanActivity
    // is what actually bounds the card line.
    const text = cleanActivity(entryText(entry));
    if (!isMeaningful(text)) continue;
    const at = Date.parse(entry.timestamp);
    return {
      title,
      model,
      thinkingLevel,
      latestActivity: text,
      latestActivityAtMs: Number.isFinite(at) ? at : null,
      attention,
    };
  }
  // No meaningful entry in the window: fall back to the file mtime so cards
  // still show recency.
  return { title, model, thinkingLevel, latestActivity: "", latestActivityAtMs: mtimeMs, attention };
}

/** Skip control/streaming noise like bare "Enter" or single-char echoes. */
function isMeaningful(text: string): boolean {
  if (!text) return false;
  // Tool calls render as "[name]" and are meaningful however short.
  return text.length >= 4 || text.startsWith("[");
}

/** Clean single-line preview of the activity (also used by the board endpoint). */
export function cleanActivity(text: string, limit = MAX_ACTIVITY_LENGTH): string {
  const cleaned = text.replace(/\s+/g, " ").trim();
  return cleaned.length > limit ? `${cleaned.slice(0, limit - 1)}…` : cleaned;
}

/** Questions of a transcript, or none when the backend's extractor throws. */
function questionsOf(backend: AgentBackend, transcript: Transcript): QuestionEntry[] {
  try {
    return backend.extractQuestions(transcript);
  } catch {
    return [];
  }
}

/** Out-of-transcript question state, for backends that keep any. */
function questionStamp(backend: AgentBackend, path: string): string {
  try {
    return backend.questionStateStamp?.(path) ?? "";
  } catch {
    return "";
  }
}

/**
 * The ask a pane is currently waiting on: the newest call group that still has
 * an unanswered question, summarized for the board. Answered and dismissed
 * asks leave no attention behind, so a card stops asking as soon as the
 * transcript (or Claude's sidecar) says the round is over.
 *
 * Text is passed through verbatim — question text and option labels are the
 * identities `answerAsk` matches against, so truncating here would submit an
 * answer no TUI recognizes. Display truncation belongs to the client.
 */
export function currentAttention(questions: readonly QuestionEntry[]): AttentionSummary | null {
  const open = questions.filter((question) => !question.answered);
  if (open.length === 0) return null;
  let group: QuestionEntry[] = [];
  let groupAt = -Infinity;
  for (const callId of new Set(open.map((question) => question.callId))) {
    const candidate = open.filter((question) => question.callId === callId);
    // Newest wins; equal timestamps (one ask, one call) fall to the later
    // group, which is where a freshly appended pending ask lands.
    const at = Math.max(...candidate.map((question) => Date.parse(question.timestamp) || 0));
    if (at >= groupAt) {
      group = candidate;
      groupAt = at;
    }
  }
  const first = group[0];
  if (!first) return null;
  const currentQuestion: AttentionQuestion = {
    id: first.id,
    header: first.header,
    question: first.question,
    options: first.options.map((option) => ({ label: option.label, description: option.description })),
    multiSelect: first.multiSelect,
  };
  return {
    kind: "ask",
    callId: first.callId,
    questionCount: group.length,
    currentQuestion,
    canQuickAnswer: canQuickAnswer(group),
  };
}

/**
 * One tap may answer only an ask the board can submit whole: a single
 * single-select question with a short authored option list. Everything else —
 * more questions, multi-select, free text, a long menu — opens in Chat.
 */
function canQuickAnswer(group: readonly QuestionEntry[]): boolean {
  const only = group.length === 1 ? group[0] : undefined;
  if (!only || only.multiSelect) return false;
  return only.options.length >= 1 && only.options.length <= MAX_QUICK_ANSWER_OPTIONS;
}

/**
 * Attention for a pane that is blocked with no structured ask. Only a caller
 * that knows the pane's status can say this (the transcript cannot), so the
 * board detail never returns it on its own; nothing about the prompt is
 * invented, the card's latest activity remains the preview.
 */
export function promptAttention(): AttentionSummary {
  return { kind: "prompt", callId: null, questionCount: 0, currentQuestion: null, canQuickAnswer: false };
}
