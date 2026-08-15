import type { ModelInfo, ModelsCatalog } from "../types.js";

/**
 * Claude Code has no machine-readable model catalog: `claude --model` takes an
 * alias or a full model name and the CLI exposes no listing command, so the
 * catalog is authored here the way the agy backend authors its own.
 *
 * Ids are full model names rather than aliases (`opus`, `sonnet`, ...) so a
 * session pins the model it was launched with instead of drifting when the
 * alias moves to a newer release.
 */
export const CLAUDE_PROVIDER = "anthropic";

/** Effort levels `claude --effort` accepts (verified against the installed CLI). */
export const CLAUDE_EFFORT_LEVELS = ["low", "medium", "high", "xhigh", "max"];
/** Haiku 4.5 supports only low, medium, and high effort. */
export const CLAUDE_HAIKU_EFFORT_LEVELS = ["low", "medium", "high"];

export const CLAUDE_MODELS: ModelInfo[] = [
  {
    id: "claude-opus-5",
    name: "Claude Opus 5",
    provider: CLAUDE_PROVIDER,
    reasoning: true,
    thinkingLevels: CLAUDE_EFFORT_LEVELS,
    contextWindow: 200_000,
  },
  {
    id: "claude-sonnet-5",
    name: "Claude Sonnet 5",
    provider: CLAUDE_PROVIDER,
    reasoning: true,
    thinkingLevels: CLAUDE_EFFORT_LEVELS,
    contextWindow: 200_000,
  },
  {
    id: "claude-fable-5",
    name: "Claude Fable 5",
    provider: CLAUDE_PROVIDER,
    reasoning: true,
    thinkingLevels: CLAUDE_EFFORT_LEVELS,
    contextWindow: 200_000,
  },
  {
    id: "claude-haiku-4-5-20251001",
    name: "Claude Haiku 4.5",
    provider: CLAUDE_PROVIDER,
    reasoning: true,
    thinkingLevels: CLAUDE_HAIKU_EFFORT_LEVELS,
    contextWindow: 200_000,
  },
];

export function readClaudeModelsCatalog(): ModelsCatalog {
  return { providers: [{ name: CLAUDE_PROVIDER, models: CLAUDE_MODELS }] };
}

/**
 * The app addresses a model by its picker key — `<provider>/<id>` — which is
 * pi's native model syntax but not Claude's. Strip our own provider prefix so
 * `anthropic/claude-opus-5` reaches the CLI as `claude-opus-5`; anything else
 * (a bare id, an alias like `opus`) passes through untouched.
 */
export function claudeModelArg(model: string): string {
  const trimmed = model.trim();
  const prefix = `${CLAUDE_PROVIDER}/`;
  return trimmed.startsWith(prefix) ? trimmed.slice(prefix.length) : trimmed;
}

/** Return the authored effort levels for a known Claude model; unknown models use the CLI-wide set. */
function claudeModelEffortLevels(model: string | undefined): readonly string[] {
  if (!model) return CLAUDE_EFFORT_LEVELS;
  const normalized = claudeModelArg(model).trim().toLowerCase();
  if (normalized === "haiku") return CLAUDE_HAIKU_EFFORT_LEVELS;
  return CLAUDE_MODELS.find((candidate) => candidate.id.toLowerCase() === normalized)?.thinkingLevels ?? CLAUDE_EFFORT_LEVELS;
}

/** A level `claude --effort` understands for the selected model, or null when unsupported. */
export function claudeEffortArg(level: string | undefined, model?: string): string | null {
  const trimmed = level?.trim().toLowerCase();
  const allowed = claudeModelEffortLevels(model);
  return trimmed && allowed.includes(trimmed) ? trimmed : null;
}
