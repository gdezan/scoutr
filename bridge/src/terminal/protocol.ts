/**
 * /ws/terminal wire protocol (v1).
 *
 * The terminal WebSocket is a separate route from /ws: it carries one reset
 * generation of one herdr terminal per socket (ADR 0001). Binary frames are
 * raw terminal bytes, never JSON; control frames are small JSON objects.
 *
 * Client → server:
 *   hello  {type:"hello", version:1, paneId, cols, rows, intent:"auto"|"takeover"}
 *          must be the first message and is accepted once; every other
 *          message before hello, a second hello, or an unsupported version
 *          is a protocol_error close.
 *   resize {type:"resize", cols, rows}   — writable controller only
 *   release {type:"release"}             — explicit end; server answers
 *          closed(released) and closes.
 *   binary frame                         — raw input bytes, writable
 *          controller only, 1..=maxInputBytes, never silently dropped.
 *
 * Server → client (each message carries the generation it belongs to):
 *   ready     {type:"ready", version, generation, paneId, mode, cols, rows, reset:true}
 *             always precedes the generation's first binary message.
 *   ownership {type:"ownership", generation, mode, canTakeover}
 *             sent right after ready when the settled mode is observe.
 *   closed    {type:"closed", generation, reason}
 *             reason ∈ released|replaced|pane_closed|taken_over|shutdown.
 *             The socket ends after closed; nothing else follows.
 *   error     {type:"error", code, message, retryable}
 *             code ∈ protocol_error|pane_not_found|no_snapshot|unsupported|
 *             ownership_conflict|startup_error|slow_client|input_backpressure|
 *             child_failed|replaced|shutdown. The socket ends after error.
 *
 * The bridge discards terminal output while a socket is in grace (no
 * retention), and never mixes terminal traffic into the /ws feed route.
 */

import { TERMINAL_LIMITS } from "./process.js";
import type { TerminalMode } from "./types.js";
import * as v from "valibot";
/** Wire protocol version; the only version this server speaks. */
export const TERMINAL_PROTOCOL_VERSION = 1;
/** One client JSON control message (hello/resize/release), bytes on the wire. */
export const TERMINAL_JSON_MAX_BYTES = 64 * 1024;
/** One binary input frame; must fit the process stdin bound. */
export const TERMINAL_INPUT_MAX_BYTES = TERMINAL_LIMITS.maxInputBytes;
/** paneId sanity bound (snapshot validation is the real check). */
export const TERMINAL_PANE_ID_MAX_LENGTH = 256;

export type TerminalIntent = "auto" | "takeover";

export interface TerminalHello {
  type: "hello";
  version: number;
  paneId: string;
  cols: number;
  rows: number;
  intent: TerminalIntent;
}

export type TerminalClientMessage =
  | TerminalHello
  | { type: "resize"; cols: number; rows: number }
  | { type: "release" };

export type TerminalClosedReason = "released" | "replaced" | "pane_closed" | "taken_over" | "shutdown";

export interface TerminalReadyMessage {
  type: "ready";
  version: number;
  generation: number;
  paneId: string;
  mode: TerminalMode;
  cols: number;
  rows: number;
  reset: true;
}

export interface TerminalOwnershipMessage {
  type: "ownership";
  generation: number;
  mode: TerminalMode;
  canTakeover: boolean;
}

export interface TerminalClosedMessage {
  type: "closed";
  generation: number;
  reason: TerminalClosedReason;
}

export interface TerminalErrorMessage {
  type: "error";
  code: string;
  message: string;
  retryable: boolean;
}

export type TerminalServerMessage =
  | TerminalReadyMessage
  | TerminalOwnershipMessage
  | TerminalClosedMessage
  | TerminalErrorMessage;
function gridInBounds(cols: number, rows: number): boolean {
  return (
    Number.isInteger(cols) &&
    Number.isInteger(rows) &&
    cols >= TERMINAL_LIMITS.minCols &&
    cols <= TERMINAL_LIMITS.maxCols &&
    rows >= TERMINAL_LIMITS.minRows &&
    rows <= TERMINAL_LIMITS.maxRows
  );
}

/**
 * Validate one client JSON control message. Returns a human-readable
 * protocol violation message on failure; the caller closes with
 * error(protocol_error).
 */
const clientMessageSchema = v.variant("type", [
  v.looseObject({
    type: v.literal("hello"),
    version: v.number(),
    paneId: v.string(),
    cols: v.number(),
    rows: v.number(),
    intent: v.picklist(["auto", "takeover"]),
  }),
  v.looseObject({
    type: v.literal("resize"),
    cols: v.number(),
    rows: v.number(),
  }),
  v.looseObject({
    type: v.literal("release"),
  }),
]);

export function parseClientMessage(
  text: string,
): { ok: true; message: TerminalClientMessage } | { ok: false; message: string } {
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    return { ok: false, message: "invalid JSON" };
  }
  const parsed = v.safeParse(clientMessageSchema, json);
  if (!parsed.success) return { ok: false, message: "invalid terminal message" };
  const message = parsed.output;
  switch (message.type) {
    case "hello": {
      if (message.version !== TERMINAL_PROTOCOL_VERSION) {
        return { ok: false, message: `unsupported protocol version ${String(message.version)}` };
      }
      if (
        message.paneId.length === 0 ||
        message.paneId.length > TERMINAL_PANE_ID_MAX_LENGTH ||
        message.paneId.includes("\n")
      ) {
        return { ok: false, message: "invalid paneId" };
      }
      if (!gridInBounds(message.cols, message.rows)) {
        return { ok: false, message: "grid out of bounds" };
      }
      return { ok: true, message };
    }
    case "resize": {
      if (!gridInBounds(message.cols, message.rows)) {
        return { ok: false, message: "grid out of bounds" };
      }
      return { ok: true, message };
    }
    case "release":
      return { ok: true, message };
  }
}
