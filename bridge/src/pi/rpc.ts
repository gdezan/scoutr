import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import type { PiMessageEntry } from "./session.js";

/**
 * Bridge-owned `pi --mode rpc` sessions (objective layer: app-owned pi chat).
 *
 * Speaks pi's documented RPC JSONL protocol over stdin/stdout:
 *   - commands go to stdin, one JSON object per line (LF only; no readline)
 *   - responses are {type:"response", id?, command, success, data?|error?}
 *   - events stream on stdout; dialog UIs arrive as extension_ui_request and
 *     are answered with extension_ui_response (programmatic ask_user_question)
 *
 * Transcripts are read with get_entries (stable entry-id cursor), so the chat
 * view polls with ?since= like the pane-JSONL path.
 */

const COMMAND_TIMEOUT_MS = 60_000;

/** extension_ui_request methods that block until an extension_ui_response. */
const DIALOG_METHODS = new Set(["select", "confirm", "input", "editor"]);

/** Locate the pi script. The daemon runs under systemd without a mise PATH, so
 * prefer real paths over PATH resolution. */
export function resolvePiBin(): string {
  if (process.env.PI_BIN) return process.env.PI_BIN;
  const candidates = [
    join(process.env.HOME ?? "", ".local", "bin", "pi"),
    "/home/gdezan/.local/share/mise/installs/node/26/bin/pi",
    "/home/gdezan/.local/share/mise/installs/node/26.5.0/bin/pi",
  ];
  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate;
  }
  return "pi";
}

/** Locate the node binary that runs the pi script (its shebang needs it). */
function resolveNodeBin(piBin: string): string {
  if (process.env.PI_NODE_BIN) return process.env.PI_NODE_BIN;
  // The node next to the pi script is a safe bet (mise installs both).
  const sibling = join(dirname(piBin), "node");
  if (existsSync(sibling)) return sibling;
  const candidates = [
    "/home/gdezan/.local/share/mise/installs/node/26.5.0/bin/node",
    "/home/gdezan/.local/share/mise/installs/node/26/bin/node",
  ];
  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate;
  }
  return "node";
}

/** A pending dialog the agent wants answered (select/confirm/input/editor). */
export interface RpcUiRequest {
  id: string;
  method: "select" | "confirm" | "input" | "editor" | string;
  title?: string;
  message?: string;
  options?: string[];
  placeholder?: string;
  prefill?: string;
  timeout?: number;
}

export interface PiRpcSessionInfo {
  id: string;
  status: "starting" | "running" | "exited";
  name: string;
  lastEntryId: string | null;
  uiRequests: RpcUiRequest[];
  error?: string;
  createdAt: number;
}

interface Pending {
  resolve: (data: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

/** One spawned pi --mode rpc process. */
export class PiRpcSession {
  readonly id: string;
  private readonly name: string;
  private proc: ChildProcessWithoutNullStreams | null = null;
  private buffer = "";
  private pending = new Map<string, Pending>();
  private ui = new Map<string, RpcUiRequest>();
  private lastEntryId: string | null = null;
  private exited = false;
  status: "starting" | "running" | "exited" = "starting";
  error: string | undefined;
  readonly createdAt = Date.now();

  /** Called when a dialog UI request arrives (the app answers it via respondToUi). */
  onUiRequest: ((request: RpcUiRequest) => void) | null = null;

  constructor(id: string, name: string) {
    this.id = id;
    this.name = name;
  }

  /** Spawn pi and wait for the first response (get_state round trip). */
  async start(): Promise<void> {
    const piBin = resolvePiBin();
    // Run the script through node explicitly: pi's shebang uses env node,
    // which the systemd unit cannot resolve.
    const proc = spawn(resolveNodeBin(piBin), [piBin, "--mode", "rpc", "--name", this.name], {
      stdio: ["pipe", "pipe", "pipe"],
    });
    this.proc = proc;
    proc.stdout.setEncoding("utf8");
    proc.stdout.on("data", (chunk: string) => this.onData(chunk));
    proc.stderr.on("data", () => { /* pi logs to stderr; swallow */ });
    proc.on("error", (error) => {
      this.exited = true;
      this.status = "exited";
      this.error = error.message;
      this.rejectAll(new Error(`pi failed to start: ${error.message}`));
    });
    proc.on("exit", (code) => {
      this.exited = true;
      this.status = "exited";
      this.error = `pi exited (code ${code})`;
      this.rejectAll(new Error(this.error));
    });
    // Readiness: the get_state response proves the RPC loop is up.
    await this.request<{ sessionId?: string }>("get_state");
    this.status = "running";
  }

  get info(): PiRpcSessionInfo {
    return {
      id: this.id,
      status: this.status,
      name: this.name,
      lastEntryId: this.lastEntryId,
      uiRequests: [...this.ui.values()],
      error: this.error,
      createdAt: this.createdAt,
    };
  }

  /** Prompt the agent. `steer` queues while it is busy. */
  async prompt(message: string, opts: { steer?: boolean } = {}): Promise<void> {
    const params: Record<string, unknown> = { message };
    if (opts.steer) params.streamingBehavior = "steer";
    await this.request("prompt", params);
  }

  async steer(message: string): Promise<void> {
    await this.request("steer", { message });
  }

  /** Full transcript after the cursor, mapped to the shared entry shape. */
  async getEntries(since: string | null = null): Promise<{ entries: PiMessageEntry[]; leafId: string | null }> {
    const data = await this.request<{ entries?: Array<Record<string, unknown>>; leafId?: string | null }>(
      "get_entries",
      since ? { since } : {},
    );
    const entries = (data.entries ?? []).map(toEntry).filter((entry) => entry !== null);
    this.lastEntryId = data.leafId ?? this.lastEntryId;
    return { entries, leafId: data.leafId ?? null };
  }

  /** Answer a dialog UI request programmatically (select/input/editor value,
   * confirm confirmed, or cancelled). No response line is emitted for this. */
  respondToUi(id: string, response: { value?: string; confirmed?: boolean; cancelled?: boolean }): void {
    if (!this.proc || this.exited) return;
    this.proc.stdin.write(JSON.stringify({ type: "extension_ui_response", id, ...response }) + "\n");
    this.ui.delete(id);
  }

  async abort(): Promise<void> {
    try {
      await this.request("abort");
    } catch {
      // abort races the agent stopping; ignore failures.
    }
  }

  /** Stop the process. */
  stop(): void {
    if (this.proc && !this.exited) {
      this.proc.kill("SIGTERM");
      // Force-kill if graceful shutdown hangs.
      setTimeout(() => {
        if (this.proc && !this.exited) this.proc.kill("SIGKILL");
      }, 3000).unref();
    }
  }

  private onData(chunk: string): void {
    this.buffer += chunk;
    let idx: number;
    while ((idx = this.buffer.indexOf("\n")) >= 0) {
      let line = this.buffer.slice(0, idx);
      this.buffer = this.buffer.slice(idx + 1);
      if (line.endsWith("\r")) line = line.slice(0, -1);
      if (!line.trim()) continue;
      this.handleLine(line);
    }
  }

  private handleLine(line: string): void {
    let obj: Record<string, unknown>;
    try {
      obj = JSON.parse(line);
    } catch {
      return;
    }
    if (obj.type === "response") {
      const rawId = typeof obj.id === "string" ? obj.id : (typeof obj.command === "string" ? obj.command : null);
      const pending = rawId ? this.pending.get(rawId) : undefined;
      if (pending) {
        const id = rawId as string;
        clearTimeout(pending.timer);
        this.pending.delete(id);
        if (obj.success === true) pending.resolve(obj.data ?? {});
        else pending.reject(new Error(typeof obj.error === "string" ? obj.error : "pi command failed"));
      }
      return;
    }
    if (obj.type === "extension_ui_request") {
      const method = String(obj.method ?? "unknown");
      // notify/setStatus/setWidget/setTitle are fire-and-forget; only dialog
      // methods wait for an extension_ui_response.
      if (!DIALOG_METHODS.has(method)) return;
      const request: RpcUiRequest = {
        id: String(obj.id),
        method,
        title: typeof obj.title === "string" ? obj.title : undefined,
        message: typeof obj.message === "string" ? obj.message : undefined,
        options: Array.isArray(obj.options) ? obj.options.map(String) : undefined,
        placeholder: typeof obj.placeholder === "string" ? obj.placeholder : undefined,
        prefill: typeof obj.prefill === "string" ? obj.prefill : undefined,
        timeout: typeof obj.timeout === "number" ? obj.timeout : undefined,
      };
      this.ui.set(request.id, request);
      this.onUiRequest?.(request);
      return;
    }
    // Other event types (agent_start, message_*, tool_*) are consumed by
    // get_entries polling; nothing to do here.
  }

  private request<T>(command: string, params: Record<string, unknown> = {}): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      if (!this.proc || this.exited) {
        reject(new Error(this.error ?? "pi session is not running"));
        return;
      }
      const id = randomUUID();
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`pi command timed out: ${command}`));
      }, COMMAND_TIMEOUT_MS);
      this.pending.set(id, {
        resolve: (data) => resolve(data as T),
        reject,
        timer,
      });
      this.proc.stdin.write(JSON.stringify({ id, type: command, ...params }) + "\n");
    });
  }

  private rejectAll(error: Error): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }
}

/** Map a get_entries record to the shared PiMessageEntry shape. */
function toEntry(record: Record<string, unknown>): PiMessageEntry | null {
  if (record.type !== "message") return null;
  const message = (record.message ?? {}) as Record<string, unknown>;
  return {
    entryId: String(record.id),
    parentId: record.parentId == null ? null : String(record.parentId),
    timestamp: typeof record.timestamp === "string" ? record.timestamp : "",
    role: typeof message.role === "string" ? message.role : "unknown",
    content: (Array.isArray(message.content) ? message.content : []) as PiMessageEntry["content"],
    toolCallId: typeof message.toolCallId === "string" ? message.toolCallId : undefined,
    toolName: typeof message.toolName === "string" ? message.toolName : undefined,
    isError: typeof message.isError === "boolean" ? message.isError : undefined,
    stopReason: typeof message.stopReason === "string" ? message.stopReason : undefined,
    model: typeof message.model === "string" ? message.model : undefined,
    usage: message.usage as PiMessageEntry["usage"] | undefined,
  };
}

/** Registry of app-owned RPC sessions for the HTTP API. */
export class PiRpcManager {
  private readonly sessions = new Map<string, PiRpcSession>();

  list(): PiRpcSessionInfo[] {
    return [...this.sessions.values()].map((session) => session.info);
  }

  get(id: string): PiRpcSession | undefined {
    return this.sessions.get(id);
  }

  async create(name = `cockpit-${Date.now()}`): Promise<PiRpcSession> {
    const id = randomUUID();
    const session = new PiRpcSession(id, name);
    this.sessions.set(id, session);
    try {
      await session.start();
    } catch (error) {
      this.sessions.delete(id);
      throw error;
    }
    return session;
  }

  /** Remove a session and stop its process. */
  dispose(id: string): void {
    const session = this.sessions.get(id);
    if (session) {
      session.stop();
      this.sessions.delete(id);
    }
  }

  disposeAll(): void {
    for (const session of this.sessions.values()) session.stop();
    this.sessions.clear();
  }
}
