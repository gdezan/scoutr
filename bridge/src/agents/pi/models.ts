import { readFileSync } from "node:fs";
import { join } from "node:path";
import * as v from "valibot";

/** One selectable model from pi's models-store.json. */
export interface PiModelInfo {
  id: string;
  name: string;
  provider: string;
  reasoning: boolean;
  /** Thinking levels the model supports, e.g. ["low", "high", "xhigh"]. */
  thinkingLevels: string[];
  contextWindow: number | null;
}

export interface ProviderModels {
  name: string;
  models: PiModelInfo[];
}

export interface ModelsCatalog {
  providers: ProviderModels[];
}

/** pi's model catalog file location, honoring PI_CODING_AGENT_DIR. */
export function modelsStorePath(piAgentDir = process.env.PI_CODING_AGENT_DIR ?? "~/.pi/agent"): string {
  const dir = piAgentDir === "~/.pi/agent" ? join(process.env.HOME ?? "", ".pi", "agent") : piAgentDir;
  return join(dir, "models-store.json");
}

const THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"] as const;

const thinkingLevelMapSchema = v.optional(v.record(v.string(), v.unknown()));
type ThinkingLevelMap = v.InferOutput<typeof thinkingLevelMapSchema>;

/** Mirror pi-ai's getSupportedThinkingLevels so the mobile controls match the active TUI. */
export function supportedThinkingLevels(model: { reasoning?: boolean; thinkingLevelMap?: ThinkingLevelMap }): string[] {
  if (model.reasoning !== true) return ["off"];
  const map = model.thinkingLevelMap;
  return THINKING_LEVELS.filter((level) => {
    const mapped = map?.[level];
    if (mapped === null) return false;
    if (level === "xhigh" || level === "max") return mapped !== undefined;
    return true;
  });
}

/** Best-effort display name for a model id when `name` is missing. */
function displayName(model: { name?: string; id?: string }): string {
  return model.name ?? model.id ?? "";
}

/**
 * Read pi's models-store.json (read-only) and flatten it into a provider-grouped
 * catalog for the app's model picker. Throws on a missing/unreadable file so
 * the HTTP layer can report it distinctly from an empty catalog.
 */
const modelSchema = v.looseObject({
  id: v.optional(v.string()),
  name: v.optional(v.string()),
  reasoning: v.optional(v.boolean()),
  contextWindow: v.optional(v.union([v.number(), v.null()])),
  thinkingLevelMap: thinkingLevelMapSchema,
});

const modelsStoreSchema = v.record(
  v.string(),
  v.looseObject({ models: v.optional(v.array(modelSchema)) }),
);

export function readModelsCatalog(piAgentDir?: string): ModelsCatalog {
  const raw = readFileSync(modelsStorePath(piAgentDir), "utf8");
  const parsed = v.parse(modelsStoreSchema, JSON.parse(raw));
  const providers: ProviderModels[] = [];
  for (const [provider, value] of Object.entries(parsed)) {
    const models = value.models ?? [];
    const catalog: ProviderModels = {
      name: provider,
      models: models.map((m) => ({
        id: m.id ?? "",
        name: displayName(m),
        provider,
        reasoning: m.reasoning === true,
        thinkingLevels: supportedThinkingLevels(m),
        contextWindow: m.contextWindow ?? null,
      })),
    };
    providers.push(catalog);
  }
  return { providers };
}
