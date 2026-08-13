/**
 * Terminal process seam types.
 *
 * Contract source: live capture against the installed `herdr 0.8.0`
 * (protocol 19) CLI — see bridge/reference/terminal-contract-0.8.0.md.
 * The production adapter (process.ts) alone knows the CLI command and NDJSON
 * vocabulary; HerdrPort never grows terminal-process methods.
 */

/** A writable controller owns input/resize; an observer is read-only. */
export type TerminalMode = "control" | "observe";

/**
 * Stable classification of the `terminal.closed` reason string Herdr emits.
 * Classification rules are pinned to the captured 0.8.0 strings; anything
 * else maps to "unknown" rather than being guessed.
 */
export type TerminalClosedCode =
  | "released" // {"reason":"detached"} — explicit release or stdin EOF
  | "taken-over" // {"reason":"terminal attach taken over"}
  | "terminal-gone" // {"reason":"terminal attach ended: terminal <id> not found"}
  | "ownership-conflict" // {"reason":"terminal attach failed: ... already has an attached client; retry with --takeover"}
  | "unknown";

/**
 * Records emitted by a live TerminalProcess.
 *
 * `bytes` is raw ANSI/PTY output, never decoded to text by the bridge.
 * `full` marks a full-screen redraw (initial replay, resize, observer
 * restart); `seq`/`width`/`height` mirror the captured `terminal.frame`
 * fields so the broker can order and size generations.
 */
export type TerminalRecord =
  | { type: "bytes"; bytes: Buffer; seq: number; full: boolean; width: number; height: number }
  | { type: "closed"; code: TerminalClosedCode; reason?: string }
  | { type: "error"; code: string; message: string };

/** Bounded child-process view of one herdr terminal session. */
export interface TerminalProcess {
  readonly mode: TerminalMode;
  /**
   * The current-screen replay frame that completed the handshake (consumed
   * by open(); the broker uses it to start each new generation). The frame
   * is not re-delivered through onRecord.
   */
  readonly replayFrame: { bytes: Buffer; seq: number; full: boolean; width: number; height: number };
  /**
   * Send raw input bytes to a controller. Returns false when the process is
   * not writable (observer, released, exited) or its stdin queue is over the
   * bound; the caller decides the backpressure policy and never drops bytes
   * silently inside a live generation.
   */
  sendInput(bytes: Buffer): boolean;
  /** Resize the controller viewport. Returns false when not writable or out of bounds. */
  resize(cols: number, rows: number): boolean;
  /** Pause/resume child stdout (high/low-water backpressure). */
  pauseOutput(): void;
  resumeOutput(): void;
  /**
   * Idempotent, bounded teardown: sends `terminal.release` (control only),
   * waits a bounded grace, then escalates SIGTERM → SIGKILL.
   */
  release(): Promise<void>;
  /** Subscribe to records. Returns an unsubscribe function. */
  onRecord(listener: (record: TerminalRecord) => void): () => void;
}

/** Capability is a state, not a Boolean (plan "Capability gate"). */
export type TerminalCapability =
  | { status: "supported"; herdrVersion: string; protocol: number }
  | { status: "unverified"; herdrVersion: string; protocol: number; reason: "no-pane" }
  | { status: "unsupported"; installedVersion?: string; required: string; reason: string };

export interface TerminalOpenOptions {
  target: string;
  mode: TerminalMode;
  takeover: boolean;
  cols: number;
  rows: number;
}

export interface TerminalLauncher {
  /**
   * Bounded capability probe. With a target, completes a read-only observer
   * handshake (never takes ownership); without one, returns the provisional
   * unverified/no-pane state after version + command-surface checks.
   */
  probe(target?: string): Promise<TerminalCapability>;
  /**
   * Spawn a herdr terminal session child. Resolves after the first
   * `terminal.frame` arrives (the current-screen replay). Rejects with
   * TerminalOwnershipConflictError only on the verified ownership-conflict
   * record; every other startup failure is a typed startup error.
   */
  open(options: TerminalOpenOptions): Promise<TerminalProcess>;
}
