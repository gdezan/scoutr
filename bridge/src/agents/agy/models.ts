import type { ModelInfo, ModelsCatalog, ProviderModels } from "../types.js";

export const AGY_MODELS: ModelInfo[] = [
  // Google / Gemini
  {
    id: "gemini-3.7-flash-high",
    name: "Gemini 3.7 Flash (High)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 1_048_576,
  },
  {
    id: "gemini-3.7-flash-medium",
    name: "Gemini 3.7 Flash (Medium)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 1_048_576,
  },
  {
    id: "gemini-3.7-flash-low",
    name: "Gemini 3.7 Flash (Low)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 1_048_576,
  },
  {
    id: "gemini-3.6-flash-high",
    name: "Gemini 3.6 Flash (High)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 1_048_576,
  },
  {
    id: "gemini-3.6-flash-medium",
    name: "Gemini 3.6 Flash (Medium)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 1_048_576,
  },
  {
    id: "gemini-3.6-flash-low",
    name: "Gemini 3.6 Flash (Low)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 1_048_576,
  },
  {
    id: "gemini-3.5-flash-high",
    name: "Gemini 3.5 Flash (High)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 1_048_576,
  },
  {
    id: "gemini-3.5-flash-medium",
    name: "Gemini 3.5 Flash (Medium)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 1_048_576,
  },
  {
    id: "gemini-3.5-flash-low",
    name: "Gemini 3.5 Flash (Low)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 1_048_576,
  },
  {
    id: "gemini-3.1-pro-high",
    name: "Gemini 3.1 Pro (High)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 2_097_152,
  },
  {
    id: "gemini-3.1-pro-low",
    name: "Gemini 3.1 Pro (Low)",
    provider: "google",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 2_097_152,
  },
  // Anthropic
  {
    id: "claude-sonnet-4-6",
    name: "Claude Sonnet 4.6 (Thinking)",
    provider: "anthropic",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 200_000,
  },
  {
    id: "claude-opus-4-6-thinking",
    name: "Claude Opus 4.6 (Thinking)",
    provider: "anthropic",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 200_000,
  },
  // OpenAI
  {
    id: "gpt-oss-120b-medium",
    name: "GPT-OSS 120B (Medium)",
    provider: "openai",
    reasoning: true,
    thinkingLevels: ["off", "low", "medium", "high"],
    contextWindow: 128_000,
  },
];

export function readAgyModelsCatalog(): ModelsCatalog {
  const byProvider = new Map<string, ModelInfo[]>();
  for (const model of AGY_MODELS) {
    const list = byProvider.get(model.provider) ?? [];
    list.push(model);
    byProvider.set(model.provider, list);
  }
  const providers: ProviderModels[] = Array.from(byProvider.entries()).map(([name, models]) => ({
    name,
    models,
  }));
  return { providers };
}
