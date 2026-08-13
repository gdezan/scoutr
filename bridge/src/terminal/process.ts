/**
 * Herdr terminal child-process adapter — the only place that knows the CLI
 * command and the NDJSON vocabulary. Independent of HerdrPort: the bridge
 * routes terminal traffic through this seam, never through the herdr socket
 * port.
 *
 * Wire contract (captured live against herdr 0.8.0 / protocol 19, see
 * bridge/reference/terminal-contract-0.8.0.md):
 *   stdout: NDJSON lines, `terminal.frame` {bytes: base64, encoding:"ansi",
 *           full, height, seq, width} then `terminal.closed` {reason}.
 *   stdin (control only): `terminal.input` {text|bytes}, `terminal.resize`
 *           {cols, rows}, `terminal.release`.
 *   closed reasons: "detached", "terminal attach taken over",
 *           "terminal attach failed: ... already has an attached client;
 *            retry with --takeover", "terminal attach ended: terminal <id>
 *            not found".
 */
import { spawn, type ChildProcess } from "node:child_process";
import type {
  TerminalCapability,
  TerminalClosedCode,
  TerminalLauncher,
  TerminalMode,
  TerminalOpenOptions,
  TerminalProcess,
  TerminalRecord,
} from "./types.js";
import { probeTerminalCapability } from "./capability.js";

/** Finite bounds for every unbounded-looking input. */
export const TERMINAL_LIMITS = {
  /** One NDJSON stdout record, base64 included. Full frames can be ~75 KB. */
  maxLineBytes: 2 * 1024 * 1024,
  /** One decoded terminal.frame payload. */
  maxDecodedBytes: 2 * 1024 * 1024,
  /** One sendInput call. The broker slices larger Android input. */
  maxInputBytes: 64 * 1024,
  /** Cap on buffered-but-unflushed stdin before sendInput reports backpressure. */
  maxStdinBufferedBytes: 1024 * 1024,
  /** Grid bounds for resize and spawn arguments. */
  minCols: 1,
  maxCols: 500,
  minRows: 1,
  maxRows: 300,
  /** First frame must arrive within this; otherwise kill and fail. */
  handshakeTimeoutMs: 10_000,
  /** Wait for a clean exit after `terminal.release` before escalating. */
  releaseGraceMs: 2_000,
  /** Wait after SIGTERM before SIGKILL. */
  termGraceMs: 1_000,
} as const;

export class TerminalError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.name = "TerminalError";
    this.code = code;
  }
}

/** Spawn failure, stderr startup failure, exit before handshake, handshake timeout, malformed records. */
export class TerminalStartupError extends TerminalError {
  constructor(code: string, message: string) {
    super(code, message);
    this.name = "TerminalStartupError";
  }
}

/** Only the verified ownership-conflict record maps here; the broker falls back to observe. */
export class TerminalOwnershipConflictError extends TerminalError {
  constructor(message: string) {
    super("ownership-conflict", message);
    this.name = "TerminalOwnershipConflictError";
  }
}

/** Caller-supplied args that violate the bounds (grid, input size, target). */
export class TerminalBoundsError extends TerminalError {
  constructor(message: string) {
    super("bounds", message);
    this.name = "TerminalBoundsError";
  }
}

export interface HerdrTerminalLauncherOptions {
  /** Executable path or bare name resolved on PATH (default: HERDR_BIN ?? "herdr"). */
  bin?: string;
  /** Socket path forwarded to the child via HERDR_SOCKET_PATH. */
  socketPath?: string;
  /** Extra environment for the child (merged over process.env; used by tests). */
  env?: NodeJS.ProcessEnv;
  handshakeTimeoutMs?: number;
  releaseGraceMs?: number;
  termGraceMs?: number;
}

const BASE64_RE = /^[A-Za-z0-9+/]*={0,2}$/;

/** Strict base64: regex + canonical length so lenient Buffer decoding cannot mask garbage. */
function decodeBase64Strict(text: string, what: string): Buffer {
  if (text.length % 4 !== 0 || !BASE64_RE.test(text)) {
    throw new TerminalStartupError("invalid-base64", `${what} is not valid base64`);
  }
  const bytes = Buffer.from(text, "base64");
  // Re-encoding must reproduce the input byte-for-byte; Buffer.from skips
  // invalid characters, so this catches every non-canonical string.
  if (bytes.toString("base64") !== text) {
    throw new TerminalStartupError("invalid-base64", `${what} is not canonical base64`);
  }
  return bytes;
}

function classifyClosedReason(reason: string): TerminalClosedCode {
  if (reason === "detached") return "released";
  if (reason === "terminal attach taken over") return "taken-over";
  if (reason.includes("already has an attached client")) return "ownership-conflict";
  if (reason.startsWith("terminal attach ended:") && reason.includes("not found")) return "terminal-gone";
  return "unknown";
}

function isFrame(v: unknown): v is {
  type: "terminal.frame";
  bytes: string;
  encoding: string;
  full: boolean;
  height: number;
  seq: number;
  width: number;
} {
  if (typeof v !== "object" || v === null) return false;
  const r = v as Record<string, unknown>;
  return (
    r.type === "terminal.frame" &&
    typeof r.bytes === "string" &&
    r.encoding === "ansi" &&
    typeof r.full === "boolean" &&
    typeof r.height === "number" &&
    typeof r.seq === "number" &&
    typeof r.width === "number"
  );
}

function isClosed(v: unknown): v is { type: "terminal.closed"; reason?: string } {
  if (typeof v !== "object" || v === null) return false;
  const r = v as Record<string, unknown>;
  return r.type === "terminal.closed" && (r.reason === undefined || typeof r.reason === "string");
}

export class HerdrTerminalLauncher implements TerminalLauncher {
  private readonly bin: string;
  private readonly socketPath?: string;
  private readonly childEnv: NodeJS.ProcessEnv;
  private readonly limits: typeof TERMINAL_LIMITS;

  constructor(options: HerdrTerminalLauncherOptions = {}) {
    this.bin = options.bin?.trim() || process.env.HERDR_BIN?.trim() || "herdr";
    this.socketPath = options.socketPath?.trim() || undefined;
    this.childEnv = {
      ...process.env,
      ...(this.socketPath ? { HERDR_SOCKET_PATH: this.socketPath } : {}),
      ...options.env,
    };
    this.limits = {
      ...TERMINAL_LIMITS,
      ...(options.handshakeTimeoutMs !== undefined ? { handshakeTimeoutMs: options.handshakeTimeoutMs } : {}),
      ...(options.releaseGraceMs !== undefined ? { releaseGraceMs: options.releaseGraceMs } : {}),
      ...(options.termGraceMs !== undefined ? { termGraceMs: options.termGraceMs } : {}),
    } as typeof TERMINAL_LIMITS;
  }

  probe(target?: string): Promise<TerminalCapability> {
    return probeTerminalCapability(
      { bin: this.bin, socketPath: this.socketPath, handshakeTimeoutMs: this.limits.handshakeTimeoutMs, env: this.childEnv },
      target,
    );
  }

  open(options: TerminalOpenOptions): Promise<TerminalProcess> {
    return openTerminalProcess(this.bin, this.childEnv, this.limits, options);
  }
}

function spawnArgs(mode: TerminalMode, options: TerminalOpenOptions): string[] {
  const args = ["terminal", "session", mode, options.target, "--cols", String(options.cols), "--rows", String(options.rows)];
  if (options.takeover) args.push("--takeover");
  return args;
}

export function openTerminalProcess(
  bin: string,
  childEnv: NodeJS.ProcessEnv,
  limits: typeof TERMINAL_LIMITS,
  options: TerminalOpenOptions,
): Promise<TerminalProcess> {
  return new Promise<TerminalProcess>((resolve, reject) => {
    let settled = false;
    if (
      !options.target ||
      typeof options.target !== "string" ||
      options.target.length > 256 ||
      options.target.includes("\n")
    ) {
      reject(new TerminalBoundsError("invalid terminal target"));
      return;
    }
    if (
      !Number.isInteger(options.cols) ||
      options.cols < limits.minCols ||
      options.cols > limits.maxCols ||
      !Number.isInteger(options.rows) ||
      options.rows < limits.minRows ||
      options.rows > limits.maxRows
    ) {
      reject(new TerminalBoundsError(`grid out of bounds: ${options.cols}x${options.rows}`));
      return;
    }

    const child = spawn(bin, spawnArgs(options.mode, options), {
      stdio: ["pipe", "pipe", "pipe"],
      env: childEnv,
      // New process group so escalation can SIGTERM/SIGKILL the whole tree;
      // herdr's pane shells must not be orphaned holding our stdout pipe open.
      detached: true,
    });

    const processImpl = new HerdrTerminalProcess(child, options.mode, limits);
    const onStartupFailure = (error: TerminalError): void => {
      if (settled) return;
      settled = true;
      processImpl.dispose();
      reject(error);
    };

    child.once("error", (err) => {
      onStartupFailure(new TerminalStartupError("spawn", `failed to start ${bin}: ${err.message}`));
    });

    // Handshake: the first record must be a terminal.frame (current-screen
    // replay). A terminal.closed before any frame is a startup verdict —
    // ownership conflict is the only classified one; everything else fails.
    processImpl.onceRecord((record) => {
      if (record.type === "bytes") {
        if (settled) return;
        settled = true;
        processImpl.setReplayFrame(record);
        resolve(processImpl);
        return;
      }
      if (record.type === "closed") {
        if (record.code === "ownership-conflict") {
          onStartupFailure(new TerminalOwnershipConflictError(record.reason ?? "terminal already has an attached client"));
        } else if (record.reason?.startsWith("process exited with code")) {
          // Synthetic record from our own exit handler: report as an exit
          // failure (stderr is complete because we emit on 'close').
          onStartupFailure(
            new TerminalStartupError(
              "process-exited",
              `${bin} exited before first frame (${record.reason}; stderr: ${processImpl.stderrTail() || "none"})`,
            ),
          );
        } else {
          onStartupFailure(
            new TerminalStartupError("handshake-closed", record.reason ?? `terminal session closed before first frame (${record.code})`),
          );
        }
        return;
      }
      onStartupFailure(new TerminalStartupError(record.code, `terminal session failed before first frame: ${record.message}`));
    });

    const timer = setTimeout(() => {
      onStartupFailure(
        new TerminalStartupError(
          "handshake-timeout",
          `no terminal.frame within ${limits.handshakeTimeoutMs} ms (stderr: ${processImpl.stderrTail() || "none"})`,
        ),
      );
    }, limits.handshakeTimeoutMs);
    timer.unref();

    // Keep the handshake promise alive until the process settles.
    void processImpl.exited().then(() => {
      if (!settled) {
        onStartupFailure(
          new TerminalStartupError(
            "process-exited",
            `${bin} exited before first frame (code ${processImpl.exitCode()}; stderr: ${processImpl.stderrTail() || "none"})`,
          ),
        );
      }
    });
  });
}

class HerdrTerminalProcess implements TerminalProcess {
  readonly mode: TerminalMode;

  private readonly child: ChildProcess;
  private readonly limits: typeof TERMINAL_LIMITS;
  /** Set by setReplayFrame before open() resolves; never null on a live process. */
  replayFrame!: { bytes: Buffer; seq: number; full: boolean; width: number; height: number };
  private readonly listeners = new Set<(record: TerminalRecord) => void>();
  private readonly onceListeners = new Set<(record: TerminalRecord) => void>();
  private readonly exitedPromise: Promise<void>;
  private stderrChunks: Buffer[] = [];
  private stderrTailCache = "";
  private stderrBytes = 0;
  private hasExited = false;
  private exitCodeValue: number | null = null;
  private released = false;
  private closedEmitted = false;
  private disposing = false;
  private stdoutBuf: Buffer = Buffer.alloc(0);
  /** Records emitted before the first subscriber attaches (open() is resolving). */
  private buffered: TerminalRecord[] = [];
  private bufferedBytes = 0;

  constructor(child: ChildProcess, mode: TerminalMode, limits: typeof TERMINAL_LIMITS) {
    this.child = child;
    this.mode = mode;
    this.limits = limits;
    // Resolve on 'close', not 'exit': stderr data events complete before
    // 'close', so startup-failure messages can include the full stderr tail.
    this.exitedPromise = new Promise((resolve) => {
      if (this.hasExited) return resolve();
      this.child.once("close", () => resolve());
    });
    this.attach();
  }

  private attach(): void {
    this.child.stdout!.on("data", (chunk: Buffer) => {
      this.ingest(chunk);
    });
    this.child.stderr!.on("data", (chunk: Buffer) => {
      this.stderrChunks.push(chunk);
      this.stderrBytes += chunk.length;
      // Bound the retained tail (diagnostics only, never terminal content):
      // drop oldest chunks while over budget, always keeping the newest.
      while (this.stderrBytes > 64 * 1024 && this.stderrChunks.length > 1) {
        this.stderrBytes -= this.stderrChunks[0]!.length;
        this.stderrChunks.shift();
      }
      this.stderrTailCache = "";
    });
    this.child.once("exit", (code) => {
      this.hasExited = true;
      this.exitCodeValue = code;
    });
    // Emit on 'close', not 'exit': stderr data events complete before 'close',
    // so the synthetic record can be enriched with the full stderr tail.
    this.child.once("close", () => {
      if (!this.disposing && !this.released && !this.closedEmitted) {
        this.closedEmitted = true;
        this.emit({
          type: "closed",
          code: "unknown",
          reason: `process exited with code ${this.exitCodeValue ?? "null"} without a terminal.closed record`,
        });
      }
    });
  }

  /** Bounded stderr tail for startup-failure diagnostics (never terminal content). */
  stderrTail(): string {
    if (!this.stderrTailCache) {
      const total = this.stderrChunks.reduce((n, c) => n + c.length, 0);
      const keep = Math.max(0, total - 4096);
      let acc = Buffer.alloc(0);
      for (const c of this.stderrChunks) {
        acc = Buffer.concat([acc, c]);
        if (acc.length > keep) break;
      }
      this.stderrTailCache = acc.subarray(keep).toString("utf8");
    }
    return this.stderrTailCache;
  }

  exitCode(): number | null {
    return this.exitCodeValue;
  }

  exited(): Promise<void> {
    return this.exitedPromise;
  }

  onRecord(listener: (record: TerminalRecord) => void): () => void {
    this.listeners.add(listener);
    // Drain anything emitted while open() was still resolving (records that
    // arrived in the same chunk as the handshake frame).
    if (this.buffered.length > 0) {
      for (const r of this.buffered) listener(r);
      this.buffered = [];
      this.bufferedBytes = 0;
    }
    return () => this.listeners.delete(listener);
  }

  /** Internal: one-shot handshake listener consumed before public listeners. */
  onceRecord(listener: (record: TerminalRecord) => void): void {
    this.onceListeners.add(listener);
  }

  /** Internal: handshake stores the replay frame for the broker's new-generation start. */
  setReplayFrame(frame: Extract<TerminalRecord, { type: "bytes" }>): void {
    this.replayFrame = frame;
  }

  private recordSize(record: TerminalRecord): number {
    return record.type === "bytes" ? record.bytes.length : 64;
  }

  private emit(record: TerminalRecord): void {
    const consumedByHandshake = this.onceListeners.size > 0;
    for (const l of [...this.onceListeners]) {
      this.onceListeners.delete(l);
      l(record);
    }
    if (this.listeners.size > 0) {
      for (const l of [...this.listeners]) l(record);
      return;
    }
    // The handshake frame is retained on replayFrame and must not be
    // re-delivered to the first subscriber; only buffer records emitted after
    // it while open() is still resolving.
    if (consumedByHandshake) return;
    this.buffered.push(record);
    this.bufferedBytes += this.recordSize(record);
    while (this.buffered.length > 128 || this.bufferedBytes > 16 * 1024 * 1024) {
      const dropped = this.buffered.shift();
      if (dropped) this.bufferedBytes -= this.recordSize(dropped);
    }
  }

  /** NDJSON ingestion: arbitrary chunk boundaries, multiple records per chunk, bounded lines. */
  private ingest(chunk: Buffer): void {
    if (this.hasExited || this.disposing) return;
    this.stdoutBuf = this.stdoutBuf.length === 0 ? chunk : Buffer.concat([this.stdoutBuf, chunk]);
    let newline: number;
    while ((newline = this.stdoutBuf.indexOf(0x0a)) >= 0) {
      const line = this.stdoutBuf.subarray(0, newline);
      this.stdoutBuf = this.stdoutBuf.subarray(newline + 1);
      if (line.length > this.limits.maxLineBytes) {
        this.fatal("line-too-long", `terminal record exceeds ${this.limits.maxLineBytes} bytes`);
        return;
      }
      if (line.length === 0) continue;
      this.handleLine(line);
    }
    if (this.stdoutBuf.length > this.limits.maxLineBytes) {
      this.fatal("line-too-long", `terminal record exceeds ${this.limits.maxLineBytes} bytes without newline`);
    }
  }

  private handleLine(line: Buffer): void {
    let parsed: unknown;
    try {
      parsed = JSON.parse(line.toString("utf8"));
    } catch {
      this.fatal("invalid-json", "terminal record is not valid JSON");
      return;
    }
    if (isFrame(parsed)) {
      let bytes: Buffer;
      try {
        bytes = decodeBase64Strict(parsed.bytes, "terminal.frame bytes");
      } catch (error) {
        this.fatal("invalid-base64", error instanceof Error ? error.message : "invalid base64");
        return;
      }
      if (bytes.length > this.limits.maxDecodedBytes) {
        this.fatal("frame-too-large", `terminal.frame decodes to ${bytes.length} bytes (limit ${this.limits.maxDecodedBytes})`);
        return;
      }
      if (
        !Number.isInteger(parsed.seq) ||
        parsed.seq < 0 ||
        !Number.isInteger(parsed.width) ||
        parsed.width < this.limits.minCols ||
        parsed.width > this.limits.maxCols ||
        !Number.isInteger(parsed.height) ||
        parsed.height < this.limits.minRows ||
        parsed.height > this.limits.maxRows
      ) {
        this.fatal("frame-bounds", `terminal.frame grid/seq out of bounds (${parsed.width}x${parsed.height} seq ${parsed.seq})`);
        return;
      }
      this.emit({ type: "bytes", bytes, seq: parsed.seq, full: parsed.full, width: parsed.width, height: parsed.height });
      return;
    }
    if (isClosed(parsed)) {
      const code = classifyClosedReason(parsed.reason ?? "");
      if (!this.closedEmitted) {
        this.closedEmitted = true;
          this.emit({ type: "closed", code, reason: parsed.reason });
      }
      return;
    }
    this.fatal("invalid-record", `unexpected terminal record shape: ${line.toString("utf8").slice(0, 120)}`);
  }

  /** Fatal mid-stream contract violation: report, then kill; recovery is a fresh generation. */
  private fatal(code: string, message: string): void {
    if (this.disposing || this.hasExited) return;
    this.disposing = true;
    this.emit({ type: "error", code, message });
    this.dispose();
  }

  sendInput(bytes: Buffer): boolean {
    if (this.mode !== "control" || this.released || this.hasExited || this.disposing) return false;
    if (!Buffer.isBuffer(bytes) || bytes.length === 0 || bytes.length > this.limits.maxInputBytes) return false;
    const stdin = this.child.stdin;
    if (!stdin || !stdin.writable || stdin.writableLength > this.limits.maxStdinBufferedBytes) return false;
    const record = JSON.stringify({ type: "terminal.input", bytes: bytes.toString("base64") });
    return stdin.write(record + "\n");
  }

  resize(cols: number, rows: number): boolean {
    if (this.mode !== "control" || this.released || this.hasExited || this.disposing) return false;
    if (
      !Number.isInteger(cols) ||
      cols < this.limits.minCols ||
      cols > this.limits.maxCols ||
      !Number.isInteger(rows) ||
      rows < this.limits.minRows ||
      rows > this.limits.maxRows
    ) {
      return false;
    }
    const stdin = this.child.stdin;
    if (!stdin || !stdin.writable) return false;
    return stdin.write(JSON.stringify({ type: "terminal.resize", cols, rows }) + "\n");
  }

  pauseOutput(): void {
    this.child.stdout?.pause();
  }

  resumeOutput(): void {
    this.child.stdout?.resume();
  }

  async release(): Promise<void> {
    if (this.released) return;
    this.released = true;
    if (this.mode === "control" && !this.hasExited && !this.disposing && this.child.stdin?.writable) {
      this.child.stdin.write(JSON.stringify({ type: "terminal.release" }) + "\n");
      await this.waitForExit(this.limits.releaseGraceMs);
    }
    if (!this.hasExited) {
      this.killGroup("SIGTERM");
      await this.waitForExit(this.limits.termGraceMs);
    }
    if (!this.hasExited) {
      this.killGroup("SIGKILL");
      await this.waitForExit(5_000);
    }
  }

  /** Signal the whole child process group (spawned detached); fall back to the child alone. */
  private killGroup(signal: NodeJS.Signals): void {
    try {
      process.kill(-this.child.pid!, signal);
    } catch {
      if (!this.hasExited) this.child.kill(signal);
    }
  }

  private waitForExit(timeoutMs: number): Promise<void> {
    if (this.hasExited) return Promise.resolve();
    return new Promise((resolve) => {
      const timer = setTimeout(resolve, timeoutMs);
      timer.unref();
      this.child.once("close", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }

  /** Hard teardown for startup failures: suppress exit records, kill the child. */
  dispose(): void {
    if (this.disposing && this.hasExited) return;
    this.disposing = true;
    if (!this.hasExited) {
      this.killGroup("SIGKILL");
    }
  }
}
