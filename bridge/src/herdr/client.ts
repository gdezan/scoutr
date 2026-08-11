import net from "node:net";
import { homedir } from "node:os";
import { BridgeError } from "../errors.js";
import { join } from "node:path";
import type { HerdrPort } from "./port.js";
import type { HerdrPong, SessionSnapshot, Subscription, SubscriptionEventEnvelope } from "./types.js";

/**
 * Minimal JSONL-RPC client for the herdr socket.
 *
 * Wire contract (verified live against herdr 0.8.0 / protocol 19):
 *  - Requests are one JSON object per line: {"id": string, "method": "dot.name", "params": {...}}
 *  - One-shot requests: the server sends one response then closes the connection.
 *  - events.subscribe is the only long-lived method: ack `{"result":{"type":"subscription_started"}}`
 *    followed by streamed `{"event": "kind", "data": {...}}` envelopes.
 */

export class HerdrError extends BridgeError {
  readonly code?: string;

  constructor(message: string, code?: string) {
    super(message, 502);
    this.name = "HerdrError";
    this.code = code;
  }
}

export interface HerdrClientOptions {
  socketPath?: string;
  /** Per-request timeout for one-shot calls. Default 10s. */
  requestTimeoutMs?: number;
}

export function defaultSocketPath(): string {
  return (
    process.env.HERDR_SOCKET_PATH?.trim() ||
    join(homedir(), ".config", "herdr", "herdr.sock")
  );
}

interface RpcEnvelope {
  id?: string;
  result?: unknown;
  error?: { code?: string; message?: string };
}

function splitLines(buffer: string): string[] {
  return buffer.split("\n").map((line) => (line.endsWith("\r") ? line.slice(0, -1) : line));
}

/**
 * One-shot JSONL-RPC request. Resolves with the parsed result object.
 * The server closes the connection after responding; we also accept a complete
 * response line before close for servers that keep the socket open.
 */
export async function herdrRequest(
  socketPath: string,
  method: string,
  params: unknown,
  timeoutMs = 10_000,
): Promise<unknown> {
  const id = `bridge:${method}:${Date.now()}:${Math.random().toString(36).slice(2, 10)}`;
  const request = JSON.stringify({ id, method, params }) + "\n";

  return new Promise((resolve, reject) => {
    const sock = net.createConnection(socketPath);
    let buffer = "";
    let settled = false;
    let timer: NodeJS.Timeout | undefined;

    const fail = (error: Error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      sock.destroy();
      reject(error);
    };

    const finish = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      sock.destroy();
      try {
        const lines = splitLines(buffer).filter((line) => line.trim().length > 0);
        const envelope = JSON.parse(lines[0] ?? "{}") as RpcEnvelope;
        if (envelope.error) {
          reject(new HerdrError(envelope.error.message ?? "herdr request failed", envelope.error.code));
          return;
        }
        resolve(envelope.result);
      } catch {
        reject(new HerdrError(`invalid JSON response from herdr socket: ${buffer.slice(0, 300)}`));
      }
    };

    timer = setTimeout(() => {
      fail(new HerdrError(`herdr request ${method} timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    sock.on("connect", () => sock.write(request));
    sock.on("data", (chunk) => {
      buffer += chunk.toString("utf8");
      // Response may arrive as a full line before the server closes; resolve early
      // when the first complete line parses as the response envelope.
      const newlineIndex = buffer.indexOf("\n");
      if (newlineIndex !== -1 && !method.startsWith("events.")) {
        const first = buffer.slice(0, newlineIndex);
        try {
          const parsed = JSON.parse(first) as RpcEnvelope;
          if (parsed.id === id || parsed.error) {
            finish();
          }
        } catch {
          // Not a complete line yet; keep buffering.
        }
      }
    });
    sock.on("end", finish);
    sock.on("close", finish);
    sock.on("error", (error) => {
      fail(new HerdrError(`herdr socket error: ${error.message}`));
    });
  });
}

export interface SubscriptionHandle {
  /** Stop the subscription and close the socket. */
  close(): void;
}

export interface SubscribeCallbacks {
  onEvent?: (event: string, data: SubscriptionEventEnvelope["data"]) => void;
  onStarted?: () => void;
  onError?: (error: Error) => void;
  onClose?: () => void;
}

/**
 * Long-lived events.subscribe connection. The connection stays open and streams
 * `{"event": ..., "data": ...}` envelopes. If the socket dies, `onError`/`onClose`
 * fire and the caller decides whether to re-subscribe.
 */
export async function herdrSubscribe(
  socketPath: string,
  subscriptions: Subscription[],
  callbacks: SubscribeCallbacks,
): Promise<SubscriptionHandle> {
  const id = `bridge:subscribe:${Date.now()}:${Math.random().toString(36).slice(2, 10)}`;
  const request = JSON.stringify({ id, method: "events.subscribe", params: { subscriptions } }) + "\n";

  return new Promise((resolve, reject) => {
    const sock = net.createConnection(socketPath);
    let buffer = "";
    let started = false;
    let closed = false;

    const handle = {
      close() {
        if (closed) return;
        closed = true;
        sock.destroy();
      },
    };

    sock.on("connect", () => sock.write(request));
    sock.on("data", (chunk) => {
      buffer += chunk.toString("utf8");
      let newlineIndex: number;
      while ((newlineIndex = buffer.indexOf("\n")) !== -1) {
        const line = buffer.slice(0, newlineIndex);
        buffer = buffer.slice(newlineIndex + 1);
        if (line.endsWith("\r")) {
          void line; // line already trimmed below
        }
        const trimmed = line.endsWith("\r") ? line.slice(0, -1) : line;
        if (trimmed.trim().length === 0) continue;
        let envelope: unknown;
        try {
          envelope = JSON.parse(trimmed);
        } catch {
          callbacks.onError?.(new HerdrError(`invalid JSON in event stream: ${trimmed.slice(0, 200)}`));
          continue;
        }
        if (!started) {
          const ack = envelope as { id?: string; result?: { type?: string }; error?: { message?: string } };
          if (ack.error) {
            callbacks.onError?.(new HerdrError(ack.error.message ?? "subscribe failed"));
            handle.close();
            return;
          }
          started = true;
          callbacks.onStarted?.();
          continue;
        }
        const event = envelope as SubscriptionEventEnvelope;
        callbacks.onEvent?.(event.event, event.data);
      }
    });
    sock.on("error", (error) => {
      callbacks.onError?.(new HerdrError(`herdr socket error: ${error.message}`));
    });
    sock.on("close", () => {
      if (!closed) closed = true;
      callbacks.onClose?.();
    });

    // Give the ack a moment to arrive; resolve the handle so the caller can
    // start using it immediately after subscribing.
    const ackTimeout = setTimeout(() => {
      if (!started) {
        callbacks.onError?.(new HerdrError("no subscription ack from herdr"));
      }
    }, 3000);
    ackTimeout.unref?.();

    sock.once("data", () => {
      clearTimeout(ackTimeout);
      resolve(handle);
    });
  });
}

/** Typed wrapper over the one-shot RPC surface. */
export interface AgentReadResponse {
  type: "pane_read";
  read: {
    pane_id: string;
    workspace_id: string;
    tab_id: string;
    source: string;
    format: string;
    text: string;
    revision: number;
    truncated: boolean;
  };
}
export class HerdrClient implements HerdrPort {
  private readonly socketPath: string;
  private readonly timeoutMs: number;

  constructor(options: HerdrClientOptions = {}) {
    this.socketPath = options.socketPath ?? defaultSocketPath();
    this.timeoutMs = options.requestTimeoutMs ?? 10_000;
  }

  get path(): string {
    return this.socketPath;
  }

  async request<T>(method: string, params: unknown = {}, timeoutMs = this.timeoutMs): Promise<T> {
    return (await herdrRequest(this.socketPath, method, params, timeoutMs)) as T;
  }

  async ping(): Promise<HerdrPong> {
    return this.request<HerdrPong>("ping");
  }

  async snapshot(): Promise<SessionSnapshot> {
    const result = await this.request<{ type: string; snapshot: SessionSnapshot }>("session.snapshot");
    return result.snapshot;
  }

  async subscribe(subscriptions: Subscription[], callbacks: SubscribeCallbacks): Promise<SubscriptionHandle> {
    return herdrSubscribe(this.socketPath, subscriptions, callbacks);
  }

  /** Inject a prompt into an agent's pane (types the text + Enter). */
  async agentPrompt(target: string, text: string): Promise<unknown> {
    return this.request("agent.prompt", { target, text });
  }

  /** Verify and return a live agent target. */
  async agentGet(target: string, timeoutMs = this.timeoutMs): Promise<unknown> {
    return this.request("agent.get", { target }, timeoutMs);
  }

  /** Read a bounded terminal snapshot for an agent pane. */
  async agentRead(
    target: string,
    source: string,
    options: { lines?: number; format?: string; stripAnsi?: boolean; requestTimeoutMs?: number } = {},
  ): Promise<AgentReadResponse> {
    const params: Record<string, unknown> = { target, source };
    if (options.lines !== undefined) params.lines = options.lines;
    if (options.format) params.format = options.format;
    if (options.stripAnsi !== undefined) params.strip_ansi = options.stripAnsi;
    return this.request<AgentReadResponse>("agent.read", params, options.requestTimeoutMs);
  }

  async paneSendText(pane_id: string, text: string): Promise<unknown> {
    return this.request("pane.send_text", { pane_id, text });
  }

  async paneSendKeys(pane_id: string, keys: string[]): Promise<unknown> {
    return this.request("pane.send_keys", { pane_id, keys });
  }

  async paneSendInput(pane_id: string, text: string, keys: string[] = []): Promise<unknown> {
    return this.request("pane.send_input", { pane_id, text, keys });
  }

  /** Create a workspace. herdr pre-creates one root pane in it. */
  async workspaceCreate(params: { cwd?: string | null; label?: string | null; focus?: boolean }): Promise<{
    workspace: { workspace_id?: string };
    root_pane?: { pane_id?: string };
  }> {
    return this.request("workspace.create", params);
  }

  async workspaceClose(workspace_id: string): Promise<unknown> {
    return this.request("workspace.close", { workspace_id });
  }

  async workspaceRename(workspace_id: string, label: string): Promise<unknown> {
    return this.request("workspace.rename", { workspace_id, label });
  }
}
