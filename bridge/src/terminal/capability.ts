/**
 * Bounded herdr terminal capability probe (plan "Capability gate").
 *
 * Rules implemented here:
 *   1. Resolve the exact executable the launcher spawns (HERDR_BIN when set,
 *      otherwise PATH) and run bounded version/status/help checks.
 *   2. The only supported set is herdr 0.8.0 / protocol 19; any other
 *      version is `unsupported` — no guessed semver ranges.
 *   3. Verify both `terminal session control` and `observe` surfaces.
 *   4. With a target, complete a bounded read-only observer handshake
 *      (never takes ownership) and release; failure is `unsupported`.
 *   5. Without a target, return the provisional `unverified/no-pane` state.
 *
 * Cache and upgrade-time re-probing (snapshot-driven, atomically replacing
 * the cached capability before a /ws/terminal 101) belong to the WebSocket
 * slice; this module only answers the question "what can this binary do?".
 */
import { spawn } from "node:child_process";
import type { TerminalCapability } from "./types.js";
import { openTerminalProcess, TERMINAL_LIMITS, TerminalError } from "./process.js";

const REQUIRED_VERSION = "0.8.0";
const PROBE_TIMEOUT_MS = 5_000;
const CAPTURE_CAP = 64 * 1024;

interface BoundedRun {
  code: number | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
  spawnError?: string;
}

function runBounded(bin: string, args: string[], childEnv: NodeJS.ProcessEnv, timeoutMs = PROBE_TIMEOUT_MS): Promise<BoundedRun> {
  return new Promise((resolve) => {
    const child = spawn(bin, args, { stdio: ["pipe", "pipe", "pipe"], env: childEnv });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const finish = (partial: Omit<BoundedRun, "timedOut"> & { timedOut?: boolean }): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ code: partial.code, stdout: partial.stdout, stderr: partial.stderr, timedOut: partial.timedOut ?? false, spawnError: partial.spawnError });
    };
    child.stdout.on("data", (d: Buffer) => {
      if (stdout.length < CAPTURE_CAP) stdout += d.toString("utf8").slice(0, CAPTURE_CAP - stdout.length);
    });
    child.stderr.on("data", (d: Buffer) => {
      if (stderr.length < CAPTURE_CAP) stderr += d.toString("utf8").slice(0, CAPTURE_CAP - stderr.length);
    });
    child.on("error", (err) => finish({ code: null, stdout, stderr, spawnError: err.message }));
    child.on("exit", (code) => finish({ code, stdout, stderr }));
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      finish({ code: null, stdout, stderr, timedOut: true });
    }, timeoutMs);
    timer.unref();
  });
}

const unsupported = (installedVersion: string | undefined, reason: string): TerminalCapability => ({
  status: "unsupported",
  installedVersion,
  required: REQUIRED_VERSION,
  reason,
});

export async function probeTerminalCapability(
  options: { bin: string; socketPath?: string; handshakeTimeoutMs?: number; env?: NodeJS.ProcessEnv },
  target?: string,
): Promise<TerminalCapability> {
  const { bin, socketPath, handshakeTimeoutMs, env } = options;
  const childEnv = { ...process.env, ...(socketPath ? { HERDR_SOCKET_PATH: socketPath } : {}), ...env };

  // 1. Version check.
  const versionRun = await runBounded(bin, ["--version"], childEnv);
  if (versionRun.spawnError) {
    return unsupported(undefined, `herdr executable not found: ${versionRun.spawnError}`);
  }
  if (versionRun.timedOut) {
    return unsupported(undefined, "herdr --version timed out");
  }
  const versionMatch = /^herdr (\d+\.\d+\.\d+)/.exec(versionRun.stdout.trim());
  const installedVersion = versionMatch?.[1];
  if (!installedVersion) {
    return unsupported(undefined, `unexpected herdr --version output: ${JSON.stringify(versionRun.stdout.slice(0, 80)) || "(empty)"}`);
  }
  if (installedVersion !== REQUIRED_VERSION) {
    return unsupported(
      installedVersion,
      `herdr ${installedVersion} is not supported: the terminal NDJSON contract is verified only for ${REQUIRED_VERSION} (protocol 19)`,
    );
  }

  // 2. Protocol, from the local client status (no server dependency).
  const statusRun = await runBounded(bin, ["status", "client", "--json"], childEnv);
  let protocol = 0;
  if (!statusRun.spawnError && !statusRun.timedOut && statusRun.code === 0) {
    try {
      const parsed = JSON.parse(statusRun.stdout) as { version?: string; protocol?: unknown };
      if (typeof parsed.protocol === "number" && Number.isInteger(parsed.protocol)) protocol = parsed.protocol;
    } catch {
      // fall through to unsupported below
    }
  }
  if (!protocol) {
    return unsupported(installedVersion, "could not read the herdr protocol from `herdr status client --json`");
  }

  // 3. Both command surfaces must exist.
  for (const surface of ["control", "observe"]) {
    const run = await runBounded(bin, ["terminal", "session", surface, "--help"], childEnv);
    if (run.spawnError || run.timedOut || run.code !== 0) {
      return unsupported(installedVersion, `missing command surface: herdr terminal session ${surface} (${run.stderr.trim() || run.spawnError || "non-zero exit"})`);
    }
  }

  // 4. With a target: complete a bounded read-only observer handshake.
  if (target) {
    try {
      const proc = await openTerminalProcess(bin, childEnv, {
        ...TERMINAL_LIMITS,
        handshakeTimeoutMs: handshakeTimeoutMs ?? TERMINAL_LIMITS.handshakeTimeoutMs,
      } as typeof TERMINAL_LIMITS, {
        target,
        mode: "observe",
        takeover: false,
        cols: 80,
        rows: 24,
      });
      await proc.release();
    } catch (error) {
      const detail = error instanceof TerminalError ? error.message : String(error);
      return unsupported(installedVersion, `observer handshake failed: ${detail}`);
    }
  }

  if (!target) {
    return { status: "unverified", herdrVersion: installedVersion, protocol, reason: "no-pane" };
  }
  return { status: "supported", herdrVersion: installedVersion, protocol };
}
