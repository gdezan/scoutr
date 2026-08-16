/**
 * Terminal WebSocket adapter — one TerminalConnection per /ws/terminal
 * socket, translating raw ws frames into the broker's TerminalClient seam.
 *
 * The socket is abstracted behind TerminalSocketLike so slow-client and
 * backpressure behavior is testable with a deterministic fake (buffered
 * amount, scripted closes) instead of a real TCP stack.
 *
 * Protocol enforcement lives here:
 *   - hello must be the first message and is accepted once.
 *   - One JSON control message ≤ TERMINAL_JSON_MAX_BYTES; one binary input
 *     frame 1..=TERMINAL_INPUT_MAX_BYTES; violations are
 *     error(protocol_error) followed by a close.
 *   - Binary input is only valid while the generation is writable (control
 *     and ready). It is queued behind process stdin backpressure with a
 *     bounded queue; overflow closes with error(input_backpressure) rather
 *     than silently dropping bytes inside a live generation.
 *   - Output backpressure: while socket bufferedAmount stays above the high
 *     water mark, the process output is paused; after slowClientTimeoutMs
 *     of persistent pressure the connection closes with
 *     error(slow_client) and the session enters grace.
 *   - Every server event carries its generation; ready(reset:true) always
 *     precedes the generation's first binary message. closed/error end the
 *     connection — nothing follows them on the wire.
 */

import { WebSocket } from "ws";
import type { TerminalSessionBroker, TerminalClient } from "./broker.js";
import {
  TERMINAL_JSON_MAX_BYTES,
  TERMINAL_INPUT_MAX_BYTES,
  TERMINAL_PROTOCOL_VERSION,
  parseClientMessage,
  type TerminalHello,
} from "./protocol.js";
import type { TerminalSessionEvent } from "./broker.js";

/** Backpressure bounds (provisional until the slice-8 benchmark). */
export const TERMINAL_WS_BOUNDS = {
  /** Above this many unsent socket bytes the child output pauses. */
  highWaterBytes: 512 * 1024,
  /** Below this many unsent bytes the child output resumes. */
  lowWaterBytes: 128 * 1024,
  /** Persistent over-high-water pressure for this long disconnects. */
  slowClientTimeoutMs: 10_000,
  /** Total queued-but-unflushed client input before input_backpressure. */
  inputQueueMaxBytes: 256 * 1024,
  /** Input drain retry interval while process stdin is backed up. */
  inputDrainMs: 25,
  /** Output pressure re-check interval while paused. */
  pressureCheckMs: 250,
} as const;

/** Abstract socket so slow-client behavior is deterministically testable. */
export interface TerminalSocketLike {
  isOpen(): boolean;
  bufferedAmount(): number;
  sendText(text: string): void;
  sendBinary(bytes: Buffer): void;
  close(code?: number, reason?: string): void;
  terminate(): void;
  onMessage(handler: (data: Buffer, isBinary: boolean) => void): void;
  onClose(handler: () => void): void;
  onError(handler: (error: Error) => void): void;
}

export interface TerminalConnectionOptions {
  broker: TerminalSessionBroker;
  /** Per-connection broker identity (server.ts assigns a fresh UUID). */
  identity: string;
  highWaterBytes?: number;
  lowWaterBytes?: number;
  slowClientTimeoutMs?: number;
  inputQueueMaxBytes?: number;
  now?: () => number;
  log?: (message: string) => void;
}

type HelloState = "pending" | "done" | "failed";

export class TerminalConnection {
  private readonly broker: TerminalSessionBroker;
  private readonly identity: string;
  private readonly highWaterBytes: number;
  private readonly lowWaterBytes: number;
  private readonly slowClientTimeoutMs: number;
  private readonly inputQueueMaxBytes: number;
  private readonly now: () => number;
  private readonly log: (message: string) => void;

  private client: TerminalClient | null = null;
  private unlisten: (() => void) | null = null;
  private helloState: HelloState = "pending";
  /** Locally initiated end (protocol error, closed event, slow client, …). */
  private ended = false;
  /** ready received and mode is control (binary input allowed). */
  private writable = false;
  private paused = false;
  private overWaterSince: number | null = null;
  private pressureTimer: NodeJS.Timeout | null = null;
  private readonly inputQueue: Buffer[] = [];
  private inputQueuedBytes = 0;
  private drainTimer: NodeJS.Timeout | null = null;

  constructor(private readonly socket: TerminalSocketLike, options: TerminalConnectionOptions) {
    this.broker = options.broker;
    this.identity = options.identity;
    this.highWaterBytes = options.highWaterBytes ?? TERMINAL_WS_BOUNDS.highWaterBytes;
    this.lowWaterBytes = options.lowWaterBytes ?? TERMINAL_WS_BOUNDS.lowWaterBytes;
    this.slowClientTimeoutMs = options.slowClientTimeoutMs ?? TERMINAL_WS_BOUNDS.slowClientTimeoutMs;
    this.inputQueueMaxBytes = options.inputQueueMaxBytes ?? TERMINAL_WS_BOUNDS.inputQueueMaxBytes;
    this.now = options.now ?? Date.now;
    this.log = options.log ?? (() => {});
    socket.onMessage((data, isBinary) => this.onMessage(data, isBinary));
    socket.onClose(() => this.onSocketClose());
    socket.onError((error) => {
      this.log(`socket error: ${error.message}`);
      this.onSocketClose();
    });
  }

  private onMessage(data: Buffer, isBinary: boolean): void {
    if (this.ended) return;
    if (isBinary) {
      this.onBinaryInput(data);
      return;
    }
    this.onControlText(data.toString("utf8"));
  }

  private onControlText(text: string): void {
    if (text.length > TERMINAL_JSON_MAX_BYTES) {
      this.protocolError("control message too large");
      return;
    }
    if (this.helloState !== "done") {
      if (this.helloState === "failed") return; // closing already
      // First message must be the hello.
      const parsed = parseClientMessage(text);
      if (!parsed.ok) {
        this.protocolError(parsed.message);
        return;
      }
      if (parsed.message.type !== "hello") {
        this.protocolError("hello must be the first message");
        return;
      }
      this.helloState = "done"; // accepted once; any second hello is a violation
      void this.handleHello(parsed.message);
      return;
    }
    if (!this.client) {
      // hello accepted but not yet settled (capability probe / spawn open).
      this.protocolError("control message before ready");
      return;
    }
    const parsed = parseClientMessage(text);
    if (!parsed.ok) {
      this.protocolError(parsed.message);
      return;
    }
    switch (parsed.message.type) {
      case "hello":
        this.protocolError("hello already received");
        return;
      case "resize":
        if (!this.writable) {
          this.protocolError("resize requires writable control");
          return;
        }
        this.client.resize(parsed.message.cols, parsed.message.rows);
        return;
      case "release":
        // The broker answers closed(released) synchronously; the event
        // handler ends this connection.
        this.client.release();
        return;
    }
  }

  private async handleHello(hello: TerminalHello): Promise<void> {
    const result = await this.broker.openClient(this.identity, hello);
    if (this.ended) {
      // Socket died while the hello was in flight: the session, if any,
      // enters grace exactly like any other socket loss.
      if (result.ok) result.client.close();
      return;
    }
    if (!result.ok) {
      this.helloState = "failed";
      this.sendError(result.error.code, result.error.message, result.error.retryable);
      this.endSocket(1002, "hello rejected");
      return;
    }
    this.client = result.client;
    this.unlisten = this.client.onEvent((event) => this.onClientEvent(event));
  }

  private onClientEvent(event: TerminalSessionEvent): void {
    if (this.ended) return;
    switch (event.type) {
      case "ready":
        this.writable = event.mode === "control";
        this.sendText(
          JSON.stringify({
            type: "ready",
            version: TERMINAL_PROTOCOL_VERSION,
            generation: event.generation,
            paneId: event.paneId,
            mode: event.mode,
            cols: event.cols,
            rows: event.rows,
            reset: true,
          }),
        );
        return;
      case "ownership":
        this.sendText(
          JSON.stringify({
            type: "ownership",
            generation: event.generation,
            mode: event.mode,
            canTakeover: event.canTakeover,
          }),
        );
        return;
      case "bytes":
        this.forwardBytes(event.bytes);
        return;
      case "closed":
        this.writable = false;
        this.sendText(JSON.stringify({ type: "closed", generation: event.generation, reason: event.reason }));
        this.endSocket(1000, `terminal closed: ${event.reason}`);
        return;
      case "error":
        this.writable = false;
        this.sendError(event.code, event.message, event.retryable);
        this.endSocket(1002, `terminal error: ${event.code}`);
        return;
    }
  }

  private onBinaryInput(data: Buffer): void {
    if (!this.client) {
      this.protocolError("binary input before ready");
      return;
    }
    if (!this.writable) {
      this.protocolError("binary input requires writable control");
      return;
    }
    if (data.length === 0 || data.length > TERMINAL_INPUT_MAX_BYTES) {
      this.protocolError("input frame out of bounds");
      return;
    }
    if (this.inputQueuedBytes + data.length > this.inputQueueMaxBytes) {
      this.sendError("input_backpressure", "input queue overflow; reconnect after the backlog drains", false);
      // The live generation stays intact for a reconnect (grace).
      this.client.close();
      this.endSocket(1008, "input queue overflow");
      return;
    }
    this.inputQueue.push(data);
    this.inputQueuedBytes += data.length;
    this.drainInput();
  }

  /** Flush queued input to the child; retry on a poll while stdin backs up. */
  private drainInput(): void {
    while (this.inputQueue.length > 0 && this.client) {
      const head = this.inputQueue[0]!;
      if (!this.client.sendInput(head)) break;
      this.inputQueue.shift();
      this.inputQueuedBytes -= head.length;
    }
    if (this.inputQueue.length > 0 && !this.drainTimer && !this.ended) {
      this.drainTimer = setTimeout(() => {
        this.drainTimer = null;
        if (!this.ended) this.drainInput();
      }, TERMINAL_WS_BOUNDS.inputDrainMs);
      this.drainTimer.unref();
    }
  }

  private forwardBytes(bytes: Buffer): void {
    if (this.ended || !this.socket.isOpen()) return;
    this.checkPressure();
    if (this.ended) return; // slow-client failure ended the connection
    this.socket.sendBinary(bytes);
  }

  /** High/low-water output backpressure with a slow-client disconnect. */
  private checkPressure(): void {
    const buffered = this.socket.bufferedAmount();
    if (buffered > this.highWaterBytes) {
      if (!this.paused) {
        this.paused = true;
        this.client?.setBackpressure(true);
        this.overWaterSince = this.now();
        this.startPressureTimer();
      } else if (this.overWaterSince !== null && this.now() - this.overWaterSince > this.slowClientTimeoutMs) {
        this.failSlowClient();
        return;
      }
    } else if (buffered < this.lowWaterBytes) {
      this.stopPressureTimer();
      this.overWaterSince = null;
      if (this.paused) {
        this.paused = false;
        this.client?.setBackpressure(false);
      }
    }
  }

  private startPressureTimer(): void {
    if (this.pressureTimer) return;
    this.pressureTimer = setInterval(() => this.checkPressure(), TERMINAL_WS_BOUNDS.pressureCheckMs);
    this.pressureTimer.unref();
  }

  private stopPressureTimer(): void {
    if (this.pressureTimer) {
      clearInterval(this.pressureTimer);
      this.pressureTimer = null;
    }
  }

  private failSlowClient(): void {
    this.log("slow client: output pressure persisted past the timeout");
    this.sendError("slow_client", "terminal connection could not keep up", true);
    // Keep the child alive (grace) in case the client reconnects.
    this.client?.close();
    this.endSocket(1001, "slow client");
  }

  private protocolError(message: string): void {
    if (this.ended) return;
    this.sendError("protocol_error", message, false);
    // The generation stays intact for a reconnect (grace) when a session was
    // attached; the broker answers closed events for its own teardowns.
    this.client?.close();
    this.endSocket(1002, "protocol error");
  }

  private sendError(code: string, message: string, retryable: boolean): void {
    this.sendText(JSON.stringify({ type: "error", code, message, retryable }));
  }

  private sendText(text: string): void {
    if (this.ended || !this.socket.isOpen()) return;
    this.socket.sendText(text);
  }

  private endSocket(code: number, reason: string): void {
    this.ended = true;
    this.clearTimers();
    if (this.socket.isOpen()) {
      this.socket.close(code, reason);
    }
    this.unlisten?.();
    this.unlisten = null;
  }

  private clearTimers(): void {
    this.stopPressureTimer();
    if (this.drainTimer) {
      clearTimeout(this.drainTimer);
      this.drainTimer = null;
    }
  }

  private onSocketClose(): void {
    this.clearTimers();
    if (this.ended) return;
    this.ended = true;
    // The per-connection identity dies with its socket: drop the broker's
    // hello sequence so identitySeq stays bounded by live connections.
    this.broker.forgetHello(this.identity);
    // Socket lost without release: the broker keeps the child in grace.
    this.client?.close();
    this.unlisten?.();
    this.unlisten = null;
  }
}

/** Real-ws wrapper wiring a `ws` WebSocket into the adapter seam. */
class WsTerminalSocket implements TerminalSocketLike {
  constructor(private readonly ws: WebSocket) {
    ws.on("message", (data, isBinary) => {
      const buffer = Array.isArray(data) ? Buffer.concat(data) : Buffer.from(data as ArrayBuffer);
      this.handler(buffer, isBinary);
    });
    ws.on("close", () => this.closeHandler());
    ws.on("error", (error) => this.errorHandler(error));
  }

  private handler: (data: Buffer, isBinary: boolean) => void = () => {};
  private closeHandler: () => void = () => {};
  private errorHandler: (error: Error) => void = () => {};

  isOpen(): boolean {
    return this.ws.readyState === WebSocket.OPEN;
  }

  bufferedAmount(): number {
    return this.ws.bufferedAmount;
  }

  sendText(text: string): void {
    this.ws.send(text);
  }

  sendBinary(bytes: Buffer): void {
    this.ws.send(bytes);
  }

  close(code?: number, reason?: string): void {
    this.ws.close(code, reason);
  }

  terminate(): void {
    this.ws.terminate();
  }

  onMessage(handler: (data: Buffer, isBinary: boolean) => void): void {
    this.handler = handler;
  }

  onClose(handler: () => void): void {
    this.closeHandler = handler;
  }

  onError(handler: (error: Error) => void): void {
    this.errorHandler = handler;
  }
}

/** Server wiring: attach one authenticated ws connection to the broker. */
export function attachTerminalSocket(
  ws: WebSocket,
  options: Omit<TerminalConnectionOptions, "socket">,
): TerminalConnection {
  return new TerminalConnection(new WsTerminalSocket(ws), options);
}
