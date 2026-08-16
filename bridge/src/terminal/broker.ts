/**
 * TerminalSessionBroker — one-source-of-truth owner of terminal streams.
 *
 * The server keeps one broker and hands each connection a fresh per-connection
 * identity (server.ts assigns a random UUID per upgrade); the broker holds at
 * most one active session per identity (one generation at a time, ADR 0001).
 * All terminal traffic — child spawning, replacement, grace, teardown — flows
 * through here, never through the herdr socket port.
 *
 * Lifecycle rules pinned by the /ws/terminal design (protocol.ts):
 *   - hello validates paneId against the fresh snapshot and settles the
 *     capability cache before anything is spawned.
 *   - intent auto opens control and falls back to observe only on a
 *     verified ownership-conflict record; intent takeover opens control
 *     with takeover:true and never falls back.
 *   - A new hello for the same identity replaces the old stream: old
 *     process listeners detach first, closed(replaced) is emitted, the old
 *     child is released (awaited) and only then the new generation opens.
 *   - Socket loss without release starts a grace period (default 30 s):
 *     the child stays alive only to preserve ownership; output is discarded
 *     with no retention. Grace expiry releases the child.
 *   - A mid-session taken-over (control displaced) or pane disappearance
 *     ends the socket with closed(taken_over)/closed(pane_closed): a mode
 *     change ends a socket per the one-generation invariant. The reopen
 *     path re-attempts control with observe fallback.
 *   - Broker close (server shutdown) emits closed(shutdown) to attached
 *     sockets, releases every child, and cancels grace timers.
 */

import type { HerdrEventFeed } from "../herdr/feed.js";
import { TerminalOwnershipConflictError } from "./process.js";
import type { TerminalHello, TerminalClosedReason } from "./protocol.js";
import type {
  TerminalCapability,
  TerminalLauncher,
  TerminalMode,
  TerminalProcess,
  TerminalRecord,
} from "./types.js";

/** Default socket-loss grace: keep ownership, discard output. */
export const TERMINAL_GRACE_MS = 30_000;

/** Events a TerminalClient listener receives, in wire order. */
export type TerminalSessionEvent =
  | {
      type: "ready";
      generation: number;
      paneId: string;
      mode: TerminalMode;
      cols: number;
      rows: number;
      reset: true;
    }
  | { type: "ownership"; generation: number; mode: TerminalMode; canTakeover: boolean }
  | { type: "bytes"; generation: number; bytes: Buffer }
  | { type: "closed"; generation: number; reason: TerminalClosedReason }
  | { type: "error"; generation: number; code: string; message: string; retryable: boolean };

export interface TerminalHelloError {
  code: string;
  message: string;
  retryable: boolean;
}

/**
 * One attached generation, owned by the adapter that performed the hello.
 * The adapter registers onEvent before openClient resolves; events emitted
 * in between are buffered by the session and delivered on attach.
 */
export interface TerminalClient {
  readonly generation: number;
  readonly paneId: string;
  readonly mode: TerminalMode;
  /**
   * Forward raw input bytes to the controller. Returns false when the
   * process is not writable or its stdin bound is reached; the caller
   * queues and retries and never drops bytes silently.
   */
  sendInput(bytes: Buffer): boolean;
  /** Resize the controller viewport (writable sessions only). */
  resize(cols: number, rows: number): void;
  /** High/low-water output backpressure from the socket adapter. */
  setBackpressure(paused: boolean): void;
  /** Socket lost without release: begin the grace period. Idempotent. */
  close(): void;
  /** Explicit client release: emits closed(released), then tears down. */
  release(): void;
  onEvent(listener: (event: TerminalSessionEvent) => void): () => void;
}

export type TerminalHelloResult =
  | { ok: true; client: TerminalClient }
  | { ok: false; error: TerminalHelloError };

export interface TerminalBrokerOptions {
  launcher: TerminalLauncher;
  /** Snapshot source; hello paneIds are validated against it. */
  feed: HerdrEventFeed;
  /** Socket-loss grace before releasing the child (default TERMINAL_GRACE_MS). */
  graceMs?: number;
  /** Structured lifecycle logging (pane/generation/transition, no content). */
  log?: (message: string) => void;
}

interface Session extends TerminalClient {
  identity: string;
  phase: "attached" | "grace" | "ending" | "ended";
  graceTimer: NodeJS.Timeout | null;
  process: TerminalProcess;
  /** Buffer for events emitted before the adapter attaches its listener. */
  earlyEvents: TerminalSessionEvent[];
  /** Broker-initiated end (replacement/shutdown): emit closed when attached. */
  endByBroker(reason: "replaced" | "shutdown"): void;
  /** Shutdown hook awaited by the broker. */
  shutdown(): Promise<void>;
  /** Resolves once the child has been released. */
  released(): Promise<void>;
  detachProcess(): void;
  releaseProcess(): Promise<void>;
}

export class TerminalSessionBroker {
  private readonly sessions = new Map<string, Session>();
  private readonly graceMs: number;
  private readonly log: (message: string) => void;
  private capabilityCache: TerminalCapability = {
    status: "unverified",
    herdrVersion: "",
    protocol: 0,
    reason: "no-pane",
  };
  private probeFlight: Promise<TerminalCapability> | null = null;
  /** Per-identity hello sequence; late-settling opens are discarded. */
  private readonly identitySeq = new Map<string, number>();
  private generationCounter = 0;
  private closing = false;

  constructor(private readonly options: TerminalBrokerOptions) {
    this.graceMs = options.graceMs ?? TERMINAL_GRACE_MS;
    this.log = options.log ?? (() => {});
  }

  /** Current capability cache entry (health surface). */
  capability(): TerminalCapability {
    return this.capabilityCache;
  }

  /**
   * Upgrade-time capability gate: probe once per process lifetime and cache
   * the result atomically; a supported/unsupported cache entry is final.
   * With no pane in the snapshot the entry stays provisional (unverified)
   * and the first hello probes with its own pane.
   */
  async ensureCapabilityForUpgrade(): Promise<TerminalCapability> {
    const firstPane = this.firstSnapshotPane();
    return this.settleCapability(firstPane ?? undefined);
  }

  /**
   * Accept a hello: validate, replace any existing stream for the identity,
   * open the child, and return a client whose first events are ready (with
   * reset:true) followed by the replay bytes — always in that order.
   *
   * Identities are per connection (server.ts hands each upgrade a fresh
   * UUID): one device's session is never replaced by another device's
   * attach. The exception is a pane whose existing session is in GRACE —
   * its socket is dead, so a new connection claiming the pane takes over
   * that ownership and regains control immediately; an ATTACHED session
   * for the same pane is left alone and the spawn's ownership conflict
   * falls back to observe.
   */
  async openClient(identity: string, hello: TerminalHello): Promise<TerminalHelloResult> {
    if (this.closing) {
      return { ok: false, error: { code: "shutdown", message: "bridge is shutting down", retryable: false } };
    }

    const snapshot = this.options.feed.snapshot;
    if (!snapshot) {
      return { ok: false, error: { code: "no_snapshot", message: "no herdr snapshot yet; try again", retryable: true } };
    }
    if (!snapshot.panes.some((pane) => pane.pane_id === hello.paneId)) {
      return {
        ok: false,
        error: { code: "pane_not_found", message: `no pane ${hello.paneId} in the current snapshot`, retryable: true },
      };
    }

    const capability = await this.settleCapability(hello.paneId);
    if (capability.status === "unsupported") {
      return {
        ok: false,
        error: { code: "unsupported", message: capability.reason, retryable: false },
      };
    }

    // Serialize hellos per identity: a later hello supersedes an in-flight
    // one even before either has settled.
    const seq = (this.identitySeq.get(identity) ?? 0) + 1;
    this.identitySeq.set(identity, seq);

    const existing = this.sessions.get(identity);
    if (existing) {
      existing.endByBroker("replaced");
      await existing.released();
      if (this.closing) {
        return { ok: false, error: { code: "shutdown", message: "bridge is shutting down", retryable: false } };
      }
      if ((this.identitySeq.get(identity) ?? 0) !== seq) {
        return { ok: false, error: { code: "replaced", message: "superseded by a newer connection", retryable: true } };
      }
    }

    // Grace sessions hold a pane whose socket is gone: a new connection
    // claiming the pane (typically the same device reconnecting) replaces
    // it instead of tripping the ownership conflict.
    for (const candidate of [...this.sessions.values()]) {
      if (candidate.identity === identity) continue;
      if (candidate.paneId !== hello.paneId || candidate.phase !== "grace") continue;
      candidate.endByBroker("replaced");
      await candidate.released();
      if (this.closing) {
        return { ok: false, error: { code: "shutdown", message: "bridge is shutting down", retryable: false } };
      }
      if ((this.identitySeq.get(identity) ?? 0) !== seq) {
        return { ok: false, error: { code: "replaced", message: "superseded by a newer connection", retryable: true } };
      }
      break;
    }

    let process: TerminalProcess;
    let mode: TerminalMode;
    try {
      if (hello.intent === "takeover") {
        mode = "control";
        process = await this.options.launcher.open({
          target: hello.paneId,
          mode,
          takeover: true,
          cols: hello.cols,
          rows: hello.rows,
        });
      } else {
        try {
          mode = "control";
          process = await this.options.launcher.open({
            target: hello.paneId,
            mode,
            takeover: false,
            cols: hello.cols,
            rows: hello.rows,
          });
        } catch (error) {
          if (error instanceof TerminalOwnershipConflictError) {
            // Verified ownership conflict: fall back to a read-only observer.
            mode = "observe";
            process = await this.options.launcher.open({
              target: hello.paneId,
              mode,
              takeover: false,
              cols: hello.cols,
              rows: hello.rows,
            });
          } else {
            throw error;
          }
        }
      }
    } catch (error) {
      return { ok: false, error: this.openError(error) };
    }

    // A newer hello settled while this child was opening: discard it.
    if ((this.identitySeq.get(identity) ?? 0) !== seq) {
      void process.release().catch(() => {});
      return { ok: false, error: { code: "replaced", message: "superseded by a newer connection", retryable: true } };
    }
    // Pane history prefetch (bounded, never rejects): settle it before the
    // session exists so the bytes can be ordered between ready (reset) and
    // the replay frame — history emitted after the replay would land below
    // the screen. A stalled pane read can therefore delay open() by at most
    // the prefetch timeout; normal reads settle in milliseconds. Live records
    // stay buffered in the process meanwhile.
    const scrollback = await process.scrollback;
    if (this.closing) {
      void process.release().catch(() => {});
      return { ok: false, error: { code: "shutdown", message: "bridge is shutting down", retryable: false } };
    }
    if ((this.identitySeq.get(identity) ?? 0) !== seq) {
      void process.release().catch(() => {});
      return { ok: false, error: { code: "replaced", message: "superseded by a newer connection", retryable: true } };
    }

    const generation = ++this.generationCounter;
    const session = new TerminalSessionImpl(
      this,
      this.graceMs,
      (message) => this.log(message),
      identity,
      hello.paneId,
      generation,
      mode,
      process,
    );
    this.sessions.set(identity, session);
    this.log(`hello pane=${hello.paneId} generation=${generation} mode=${mode} intent=${hello.intent}`);
    session.emit({ type: "ready", generation, paneId: hello.paneId, mode, cols: hello.cols, rows: hello.rows, reset: true });
    if (scrollback.length > 0) {
      // Pane history precedes the replay frame: the emulator appends it above
      // the screen before the replay redraws, so it lands in the transcript.
      session.emit({ type: "bytes", generation, bytes: scrollback });
    }
    session.emit({ type: "bytes", generation, bytes: process.replayFrame.bytes });
    if (mode === "observe") {
      session.emit({ type: "ownership", generation, mode, canTakeover: true });
    }
    return { ok: true, client: session };
  }

  /** Server shutdown: end every session, release children, cancel timers. */
  async close(): Promise<void> {
    this.closing = true;
    await Promise.all([...this.sessions.values()].map((session) => session.shutdown()));
  }

  /**
   * Grace expired (session timer fired): the socket never came back, so
   * release the child. Called by the session's own timer via callback.
   */
  onGraceExpired(session: Session): void {
    if (session.phase !== "grace") return;
    session.phase = "ending";
    session.graceTimer = null;
    session.detachProcess();
    this.log(`grace expired pane=${session.paneId} generation=${session.generation}`);
    void session.releaseProcess();
  }

  /** Forget a session when its process finished or was released. */
  forget(session: Session): void {
    if (this.sessions.get(session.identity) === session) {
      this.sessions.delete(session.identity);
    }
  }

  /**
   * The connection for an identity is gone: drop its hello sequence so
   * per-connection identities never accumulate in identitySeq.
   */
  forgetHello(identity: string): void {
    this.identitySeq.delete(identity);
  }

  private firstSnapshotPane(): string | null {
    return this.options.feed.snapshot?.panes[0]?.pane_id ?? null;
  }

  /** Settle the capability cache, probing once when it is provisional. */
  private async settleCapability(target: string | undefined): Promise<TerminalCapability> {
    if (this.capabilityCache.status !== "unverified") return this.capabilityCache;
    if (!target) return this.capabilityCache;
    if (!this.probeFlight) {
      this.probeFlight = this.options.launcher
        .probe(target)
        .then((capability) => {
          this.capabilityCache = capability;
          this.log(`capability probed status=${capability.status}`);
          return capability;
        })
        .finally(() => {
          this.probeFlight = null;
        });
    }
    return this.probeFlight;
  }

  private openError(error: unknown): TerminalHelloError {
    if (error instanceof TerminalOwnershipConflictError) {
      return {
        code: "ownership_conflict",
        message: "the pane already has another writable controller; release it or retry with takeover",
        retryable: true,
      };
    }
    const message = error instanceof Error ? error.message : String(error);
    return { code: "startup_error", message, retryable: true };
  }
}

class TerminalSessionImpl implements Session {
  readonly identity: string;
  readonly paneId: string;
  readonly generation: number;
  readonly mode: TerminalMode;
  readonly process: TerminalProcess;
  phase: "attached" | "grace" | "ending" | "ended" = "attached";
  graceTimer: NodeJS.Timeout | null = null;
  earlyEvents: TerminalSessionEvent[] = [];
  private readonly listeners = new Set<(event: TerminalSessionEvent) => void>();
  private unlistenProcess: () => void;
  private releaseDone: Promise<void> | null = null;

  constructor(
    private readonly broker: TerminalSessionBroker,
    private readonly graceMs: number,
    private readonly logSink: (message: string) => void,
    identity: string,
    paneId: string,
    generation: number,
    mode: TerminalMode,
    process: TerminalProcess,
  ) {
    this.identity = identity;
    this.paneId = paneId;
    this.generation = generation;
    this.mode = mode;
    this.process = process;
    this.unlistenProcess = process.onRecord((record) => this.onProcessRecord(record));
  }

  onEvent(listener: (event: TerminalSessionEvent) => void): () => void {
    this.listeners.add(listener);
    // Deliver events emitted before the adapter attached (ready + replay
    // bytes), in wire order, then drop the buffer.
    if (this.earlyEvents.length > 0) {
      for (const event of this.earlyEvents) listener(event);
      this.earlyEvents = [];
    }
    return () => this.listeners.delete(listener);
  }

  emit(event: TerminalSessionEvent): void {
    if (this.listeners.size === 0) {
      this.earlyEvents.push(event);
      return;
    }
    for (const listener of [...this.listeners]) listener(event);
  }

  sendInput(bytes: Buffer): boolean {
    if (this.phase !== "attached" || this.mode !== "control") return false;
    return this.process.sendInput(bytes);
  }

  resize(cols: number, rows: number): void {
    if (this.phase !== "attached" || this.mode !== "control") return;
    this.process.resize(cols, rows);
  }

  setBackpressure(paused: boolean): void {
    if (this.phase !== "attached") return;
    if (paused) this.process.pauseOutput();
    else this.process.resumeOutput();
  }

  /** Socket lost without release: keep the child, discard output. */
  close(): void {
    if (this.phase !== "attached") return;
    this.phase = "grace";
    // The grace discard loop must keep draining child stdout.
    this.process.resumeOutput();
    this.graceTimer = setTimeout(() => this.broker.onGraceExpired(this), this.graceMs);
    this.graceTimer.unref();
    this.log("socket lost; grace started");
  }

  /** Explicit client release: emit closed(released), then tear down. */
  release(): void {
    if (this.phase === "ending" || this.phase === "ended") return;
    this.phase = "ending";
    this.detachProcess();
    if (this.graceTimer) {
      clearTimeout(this.graceTimer);
      this.graceTimer = null;
    }
    this.emit({ type: "closed", generation: this.generation, reason: "released" });
    void this.releaseProcess();
  }

  /** Broker-initiated end (replacement or shutdown). */
  endByBroker(reason: "replaced" | "shutdown"): void {
    if (this.phase === "ending" || this.phase === "ended") return;
    const wasAttached = this.phase === "attached";
    this.phase = "ending";
    this.detachProcess();
    if (this.graceTimer) {
      clearTimeout(this.graceTimer);
      this.graceTimer = null;
    }
    // No socket to tell during grace; the replacement path only emits when
    // the old stream is still attached.
    if (wasAttached) {
      this.emit({ type: "closed", generation: this.generation, reason });
    }
    void this.releaseProcess();
  }

  /** Shutdown hook: like endByBroker but always awaited by the broker. */
  shutdown(): Promise<void> {
    if (this.phase === "ending" || this.phase === "ended") return this.released();
    this.endByBroker("shutdown");
    return this.released();
  }

  /** Resolves once the child has been released (or was already dead). */
  released(): Promise<void> {
    if (!this.releaseDone) {
      this.releaseDone = this.releaseProcess();
    }
    return this.releaseDone;
  }

  async releaseProcess(): Promise<void> {
    try {
      await this.process.release();
    } catch {
      // release() never throws by contract; keep teardown total anyway.
    }
    this.phase = "ended";
    this.broker.forget(this);
  }

  detachProcess(): void {
    this.unlistenProcess();
    this.unlistenProcess = () => {};
  }

  private onProcessRecord(record: TerminalRecord): void {
    if (this.phase === "ending" || this.phase === "ended") return;
    if (record.type === "bytes") {
      if (this.phase === "attached") {
        this.emit({ type: "bytes", generation: this.generation, bytes: record.bytes });
      }
      // Grace: discard without retention.
      return;
    }
    if (record.type === "closed") {
      switch (record.code) {
        case "terminal-gone":
          this.finish({ type: "closed", generation: this.generation, reason: "pane_closed" });
          return;
        case "taken-over":
          this.finish({ type: "closed", generation: this.generation, reason: "taken_over" });
          return;
        case "released":
          // Our own explicit release; the session already ended itself.
          return;
        default:
          this.finish({
            type: "error",
            generation: this.generation,
            code: "child_failed",
            message: record.reason ?? "terminal session ended unexpectedly",
            retryable: true,
          });
          return;
      }
    }
    this.finish({
      type: "error",
      generation: this.generation,
      code: "child_failed",
      message: record.message,
      retryable: true,
    });
  }

  /** Terminal died while a socket was attached: end the stream. */
  private finish(event: TerminalSessionEvent): void {
    this.phase = "ending";
    this.detachProcess();
    if (this.graceTimer) {
      clearTimeout(this.graceTimer);
      this.graceTimer = null;
    }
    this.emit(event);
    this.phase = "ended";
    this.broker.forget(this);
  }

  private log(message: string): void {
    this.logSink(`pane=${this.paneId} generation=${this.generation}: ${message}`);
  }
}
