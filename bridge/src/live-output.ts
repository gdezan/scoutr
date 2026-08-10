import type { HerdrClient } from "./herdr/client.js";

export const LIVE_OUTPUT_DEFAULT_LINES = 80;
export const LIVE_OUTPUT_MAX_LINES = 120;
export const LIVE_OUTPUT_MAX_BYTES = 48 * 1024;
export const LIVE_OUTPUT_TIMEOUT_MS = 3_000;

export interface LiveOutputResult {
  paneId: string;
  text: string;
  revision: number;
  truncated: boolean;
  lineLimit: number;
}

export class LiveOutputError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
  }
}

/** Read one capped, plain-text snapshot from a target Herdr still recognizes as a live agent. */
export async function readLiveOutput(
  herdr: HerdrClient,
  paneId: string,
  requestedLines: string | null,
  timeoutMs = LIVE_OUTPUT_TIMEOUT_MS,
): Promise<LiveOutputResult> {
  if (!paneId || paneId.length > 128) throw new LiveOutputError("invalid pane id", 400);
  const lineLimit = parseLineLimit(requestedLines);
  const deadline = Date.now() + timeoutMs;

  try {
    await herdr.agentGet(paneId, remainingTime(deadline));
    const response = await herdr.agentRead(paneId, "visible", {
      lines: lineLimit,
      format: "text",
      stripAnsi: true,
      requestTimeoutMs: remainingTime(deadline),
    });
    const clean = sanitizeTerminalText(response.read.text);
    const capped = capUtf8(clean, LIVE_OUTPUT_MAX_BYTES);
    return {
      paneId,
      text: capped.text,
      revision: response.read.revision,
      truncated: response.read.truncated || capped.truncated,
      lineLimit,
    };
  } catch (error) {
    if (error instanceof LiveOutputError) throw error;
    const message = error instanceof Error ? error.message : String(error);
    if (/timed out/i.test(message)) throw new LiveOutputError("live output read timed out", 504);
    if (/not found|unknown agent|no agent|not an agent/i.test(message)) {
      throw new LiveOutputError("agent pane not found", 404);
    }
    throw new LiveOutputError(message, 502);
  }
}

function remainingTime(deadline: number): number {
  const remaining = deadline - Date.now();
  if (remaining <= 0) throw new LiveOutputError("live output read timed out", 504);
  return remaining;
}

export function parseLineLimit(value: string | null): number {
  if (value === null || value === "") return LIVE_OUTPUT_DEFAULT_LINES;
  if (!/^\d+$/.test(value)) throw new LiveOutputError("lines must be an integer", 400);
  const lines = Number(value);
  if (lines < 1 || lines > LIVE_OUTPUT_MAX_LINES) {
    throw new LiveOutputError(`lines must be between 1 and ${LIVE_OUTPUT_MAX_LINES}`, 400);
  }
  return lines;
}

export function sanitizeTerminalText(text: string): string {
  return text
    .replace(/\r\n?/g, "\n")
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, "");
}

function capUtf8(text: string, maxBytes: number): { text: string; truncated: boolean } {
  const bytes = Buffer.from(text, "utf8");
  if (bytes.length <= maxBytes) return { text, truncated: false };
  return { text: bytes.subarray(bytes.length - maxBytes).toString("utf8").replace(/^\uFFFD/, ""), truncated: true };
}
