/**
 * Deterministic in-memory terminal launcher for offline tests.
 *
 * FakeTerminalLauncher records every probe/open and hands back scriptable
 * FakeTerminalProcess children, so the broker/adapter contract tests can
 * drive ownership conflicts, takeovers, pane closure, backpressure, and
 * teardown without spawning anything.
 */

import {
  TerminalOwnershipConflictError,
  TerminalStartupError,
} from "../../src/terminal/process.js";
import type {
  TerminalCapability,
  TerminalClosedCode,
  TerminalLauncher,
  TerminalMode,
  TerminalOpenOptions,
  TerminalProcess,
  TerminalRecord,
} from "../../src/terminal/types.js";

/** A scriptable child: records input/resize, emits records on demand. */
export class FakeTerminalProcess implements TerminalProcess {
  readonly mode: TerminalMode;
  readonly replayFrame: { bytes: Buffer; seq: number; full: boolean; width: number; height: number };
  readonly scrollback: Promise<Buffer>;
  readonly grid: { cols: number; rows: number };
  readonly inputs: Buffer[] = [];
  readonly resizes: { cols: number; rows: number }[] = [];
  pauseCount = 0;
  resumeCount = 0;
  releasedFlag = false;
  /** When true, sendInput reports backpressure without buffering. */
  inputBlocked = false;
  private readonly listeners = new Set<(record: TerminalRecord) => void>();
  private releaseWaiter: (() => void) | null = null;
  private readonly releasePromise: Promise<void>;

  constructor(options: { mode: TerminalMode; cols: number; rows: number; replayBytes?: Buffer; scrollbackBytes?: Buffer }) {
    this.mode = options.mode;
    this.grid = { cols: options.cols, rows: options.rows };
    this.scrollback = Promise.resolve(options.scrollbackBytes ?? Buffer.alloc(0));
    this.replayFrame = {
      bytes: options.replayBytes ?? Buffer.from(`\x1b[2Jreplay:${options.mode}`, "utf8"),
      seq: 1,
      full: true,
      width: options.cols,
      height: options.rows,
    };
    this.releasePromise = new Promise<void>((resolve) => {
      this.releaseWaiter = resolve;
    });
  }

  sendInput(bytes: Buffer): boolean {
    if (this.inputBlocked) return false;
    this.inputs.push(Buffer.from(bytes));
    return true;
  }

  resize(cols: number, rows: number): boolean {
    this.resizes.push({ cols, rows });
    return true;
  }

  pauseOutput(): void {
    this.pauseCount += 1;
  }

  resumeOutput(): void {
    this.resumeCount += 1;
  }

  async release(): Promise<void> {
    this.releasedFlag = true;
    this.releaseWaiter?.();
  }

  /** Resolves when the broker released this child (or it was already dead). */
  released(): Promise<void> {
    return this.releasePromise;
  }

  onRecord(listener: (record: TerminalRecord) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Scripted events from tests. */
  emitBytes(bytes: Buffer, options: Partial<{ seq: number; full: boolean; width: number; height: number }> = {}): void {
    const snapshot = [...this.listeners];
    for (const listener of snapshot) {
      listener({
        type: "bytes",
        bytes,
        seq: options.seq ?? this.replayFrame.seq + 1,
        full: options.full ?? false,
        width: options.width ?? this.replayFrame.width,
        height: options.height ?? this.replayFrame.height,
      });
    }
  }

  emitClosed(code: TerminalClosedCode, reason?: string): void {
    const closedSnapshot = [...this.listeners];
    for (const listener of closedSnapshot) listener({ type: "closed", code, reason });
  }

  emitError(message: string): void {
    const errorSnapshot = [...this.listeners];
    for (const listener of errorSnapshot) listener({ type: "error", code: "invalid-record", message });
  }
}

/** A launcher whose open/probe behavior tests script before each scenario. */
export class FakeTerminalLauncher implements TerminalLauncher {
  probeResult: TerminalCapability = { status: "supported", herdrVersion: "0.8.0", protocol: 19 };
  /** control opens reject with this when set; otherwise "ok". */
  controlFailure: "conflict" | "spawn" | null = null;
  observeFailure = false;
  takeoverFailure = false;
  /** When set, open() waits on this gate before resolving (race tests). */
  openGate: Promise<void> | null = null;
  readonly probeCalls: (string | undefined)[] = [];
  readonly opens: { options: TerminalOpenOptions; process: FakeTerminalProcess }[] = [];
  /** Scrollback prefetch bytes handed to every opened process. */
  scrollbackBytes: Buffer | null = null;

  async probe(target?: string): Promise<TerminalCapability> {
    this.probeCalls.push(target);
    return this.probeResult;
  }

  async open(options: TerminalOpenOptions): Promise<TerminalProcess> {
    if (this.openGate) await this.openGate;
    if (options.mode === "control") {
      if (options.takeover) {
        if (this.takeoverFailure) {
          throw new TerminalStartupError("spawn", "takeover spawn failed");
        }
      } else if (this.controlFailure === "conflict") {
        throw new TerminalOwnershipConflictError("pane already has an attached client");
      } else if (this.controlFailure === "spawn") {
        throw new TerminalStartupError("spawn", "control spawn failed");
      }
    } else if (this.observeFailure) {
      throw new TerminalStartupError("spawn", "observe spawn failed");
    }
    const process = new FakeTerminalProcess({
      mode: options.mode,
      cols: options.cols,
      rows: options.rows,
      scrollbackBytes: this.scrollbackBytes ?? Buffer.alloc(0),
    });
    this.opens.push({ options, process });
    return process;
  }

  /** The most recently opened process, or undefined. */
  last(): FakeTerminalProcess | undefined {
    return this.opens.at(-1)?.process;
  }
}
