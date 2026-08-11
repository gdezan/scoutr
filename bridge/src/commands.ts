import { sanitizeAnswerText } from "./questions.js";
import { validateSlashCommand } from "./pi/commands.js";
import type { ServerDeps } from "./routes/types.js";

export type CommandMessage =
  | { type: "steer"; target: string; text: string }
  | { type: "answer_question"; paneId: string; text: string }
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
      const { paneId, text } = command;
      if (!paneId || !text) throw new Error("answer_question requires paneId and text");
      const safe = sanitizeAnswerText(text);
      if (!safe) throw new Error("answer_question text is empty after sanitization");
      // Type the answer, then Enter to submit it in pi's questionnaire UI.
      await deps.herdr.paneSendText(paneId, safe);
      await deps.herdr.paneSendKeys(paneId, ["Enter"]);
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
