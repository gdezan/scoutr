import { sanitizeAnswerText } from "./questions.js";
import { backendForAgentSessionInfo, getBackendOrNull } from "./agents/registry.js";
import type { SessionSnapshot } from "./herdr/types.js";
import type { ServerDeps } from "./routes/types.js";

export type CommandMessage =
  | { type: "steer"; target: string; text: string }
  | { type: "answer_question"; paneId: string; text: string; keys?: string[]; trailingKeys?: string[] }
  | { type: "slash_command"; paneId: string; text: string }
  | { type: "send_text"; paneId: string; text: string }
  | { type: "ping" }
  | { type: "subscribe"; filter?: string[] };

export type CommandResult =
  | { type: "pong"; ts: number }
  | { type: "subscribed"; filters: string[] }
  | { type: "steered"; target: string; result: unknown }
  | { type: "answered"; paneId: string; text: string }
  | { type: "command_sent"; paneId: string; text: string }
  | { type: "sent"; paneId: string };

/**
 * WS command dispatch. Pure: takes a parsed command and returns the reply
 * frame, so the command vocabulary is testable without a socket. The server
 * layer serializes the result and maps thrown errors to error frames.
 */
export async function handleCommand(command: CommandMessage, deps: ServerDeps): Promise<CommandResult> {
  switch (command.type) {
    case "ping":
      return { type: "pong", ts: Math.round(Date.now()) };
    case "subscribe": {
      // Intentional no-op placeholder: filter wiring lives on the connection.
      return { type: "subscribed", filters: command.filter ?? [] };
    }
    case "steer": {
      const { target, text } = command;
      if (!target || !text) throw new Error("steer requires target and text");
      return { type: "steered", target, result: await deps.herdr.agentPrompt(target, text) };
    }
    case "answer_question": {
      const { paneId, text, keys, trailingKeys } = command;
      if (!paneId) throw new Error("answer_question requires paneId");
      const ALLOWED_KEYS = new Set(["up", "down", "left", "right", "enter", "space", "tab", "esc"]);
      const checkKeys = (seq: string[] | undefined, name: string) => {
        if (seq === undefined) return;
        if (!Array.isArray(seq) || seq.length > 32 || seq.some((k) => !ALLOWED_KEYS.has(k))) {
          throw new Error(`answer_question ${name} must be a bounded sequence of navigation keys`);
        }
      };
      checkKeys(keys, "keys");
      checkKeys(trailingKeys, "trailingKeys");
      const hasKeys = (keys?.length ?? 0) > 0;
      const safe = sanitizeAnswerText(text);
      if (!safe && !hasKeys) throw new Error("answer_question requires text or keys");
      const backend = backendForPane(deps, paneId);
      if (backend) {
        // Each backend knows how an answer is delivered into its own UI
        // (pi's questionnaire vs claude's input prompt). `keys` is the
        // navigation sequence into pi's questionnaire (arrow/space/enter);
        // `trailingKeys` are sent after the text (editor submit + review).
        await backend.answerQuestion(deps.herdr, paneId, safe, keys ?? [], trailingKeys);
      } else {
        // Unknown agents still get the generic type-then-submit treatment.
        if (hasKeys) {
          await deps.herdr.paneSendKeys(paneId, keys ?? []);
          if (safe) {
            await deps.herdr.paneSendText(paneId, safe);
            await deps.herdr.paneSendKeys(paneId, trailingKeys ?? ["Enter"]);
          }
        } else {
          await deps.herdr.paneSendText(paneId, safe);
          await deps.herdr.paneSendKeys(paneId, ["Enter"]);
        }
      }
      return { type: "answered", paneId, text: safe };
    }
    case "slash_command": {
      const { paneId, text } = command;
      if (!paneId) throw new Error("slash_command requires paneId");
      const slashCommand = validateSlashCommand(text);
      await deps.herdr.paneSendInput(paneId, slashCommand, ["Enter"]);
      return { type: "command_sent", paneId, text: slashCommand };
    }
    case "send_text": {
      const { paneId, text } = command;
      if (!paneId || !text) throw new Error("send_text requires paneId and text");
      await deps.herdr.paneSendText(paneId, text);
      return { type: "sent", paneId };
    }
    default: {
      const exhaustive: never = command as never;
      throw new Error(`unknown command ${JSON.stringify(exhaustive)}`);
    }
  }
}

/**
 * Validate a `/command [args]` string for pane-native entry. Both known
 * backends share the slash grammar, so this is a wire-level rule.
 */
export function validateSlashCommand(text: unknown): string {
  if (typeof text !== "string") throw new Error("slash command text must be a string");
  if (text.length === 0 || text.length > 10_000) throw new Error("slash command text must be 1 to 10000 characters");
  if (!text.startsWith("/") || !/^\/[^\s\u0000-\u001f\u007f]+(?:[ \t][^\r\n\u0000-\u001f\u007f]*)?$/.test(text)) {
    throw new Error("invalid slash command text");
  }
  return text;
}

function backendForPane(deps: ServerDeps, paneId: string) {
  const snapshot = deps.feed.snapshot as SessionSnapshot | null;
  const pane = snapshot?.panes.find((candidate) => candidate.pane_id === paneId);
  if (pane) {
    return backendForAgentSessionInfo(pane.agent_session) ?? getBackendOrNull(pane.agent ?? "");
  }
  const agent = snapshot?.agents.find((candidate) => candidate.pane_id === paneId);
  if (agent) {
    return backendForAgentSessionInfo(agent.agent_session) ?? getBackendOrNull(agent.agent);
  }
  return null;
}
