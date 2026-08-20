/**
 * Unit tests for the terminal process seam against the fake herdr CLI
 * (test/support/fake-terminal-cli.sh), which mirrors the captured 0.8.0
 * NDJSON contract. Live proofs against the real binary live in
 * scripts/terminal-proof.mjs.
 */
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

import {
  compactScrollback,
  HerdrTerminalLauncher,
  NdjsonLineReader,
  TERMINAL_LIMITS,
  TerminalBoundsError,
  TerminalOwnershipConflictError,
  TerminalStartupError,
} from "../src/terminal/process.js";
import type { TerminalProcess, TerminalRecord } from "../src/terminal/types.js";

const FAKE = join(fileURLToPath(new URL(".", import.meta.url)), "support", "fake-terminal-cli.sh");

const FRAME1 = Buffer.from("\x1b[2J\x1b[Hhello-contract\n", "utf8");
const FRAME2 = Buffer.from("\x1b[2Khello-live\n", "utf8");

interface Env {
  argLog: string;
  stdinLog: string;
  launcher: HerdrTerminalLauncher;
}

function makeEnv(
  scenario: string,
  overrides: { handshakeTimeoutMs?: number; releaseGraceMs?: number; termGraceMs?: number; scrollbackTimeoutMs?: number } = {},
): Env {
  const dir = mkdtempSync(join(tmpdir(), "scoutr-term-"));
  const argLog = join(dir, "args.log");
  const stdinLog = join(dir, "stdin.log");
  const launcher = new HerdrTerminalLauncher({
    bin: FAKE,
    env: { FAKE_HERDR_SCENARIO: scenario, FAKE_HERDR_ARG_LOG: argLog, FAKE_HERDR_STDIN_LOG: stdinLog },
    ...overrides,
  });
  return { argLog, stdinLog, launcher };
}

const open = (env: Env, options: Partial<{ mode: "control" | "observe"; takeover: boolean; cols: number; rows: number }> = {}) =>
  env.launcher.open({
    target: "w1:p1",
    mode: options.mode ?? "control",
    takeover: options.takeover ?? false,
    cols: options.cols ?? 80,
    rows: options.rows ?? 24,
  });

function collect(proc: TerminalProcess, until: (r: TerminalRecord) => boolean, timeoutMs = 3000): Promise<TerminalRecord[]> {
  return new Promise((resolve, reject) => {
    const records: TerminalRecord[] = [];
    const timer = setTimeout(() => reject(new Error("collect timeout")), timeoutMs);
    // onRecord can deliver buffered records synchronously, before `unsub` is
    // assigned — the placeholder makes that first call a no-op.
    let unsub: () => void = () => {};
    unsub = proc.onRecord((r) => {
      records.push(r);
      if (until(r)) {
        clearTimeout(timer);
        unsub();
        resolve(records);
      }
    });
  });
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

test("open spawns the exact CLI command and resolves on the replay frame", async () => {
  const env = makeEnv("ok");
  const proc = await open(env, { cols: 120, rows: 30 });
  try {
    assert.equal(proc.mode, "control");
    const args = readFileSync(env.argLog, "utf8").trim().split("\n").findLast((line) => line.startsWith("terminal session"))!;
    assert.equal(args, "terminal session control w1:p1 --cols 120 --rows 30");
    assert.ok(proc.replayFrame, "replay frame must be retained");
    assert.deepEqual(proc.replayFrame!.bytes, FRAME1);
    assert.equal(proc.replayFrame!.seq, 1);
    assert.equal(proc.replayFrame!.full, true);
    assert.equal(proc.replayFrame!.width, 120);
    assert.equal(proc.replayFrame!.height, 30);
  } finally {
    await proc.release();
  }
});

test("takeover flag is forwarded last", async () => {
  const env = makeEnv("ok");
  const proc = await open(env, { takeover: true });
  try {
    const args = readFileSync(env.argLog, "utf8").trim().split("\n").findLast((line) => line.startsWith("terminal session"))!;
    assert.equal(args, "terminal session control w1:p1 --cols 80 --rows 24 --takeover");
  } finally {
    await proc.release();
  }
});

test("pane history prefetch runs `pane read` and exposes the captured bytes", async () => {
  const env = makeEnv("ok");
  const proc = await open(env);
  try {
    const args = readFileSync(env.argLog, "utf8").trim().split("\n").find((line) => line.startsWith("pane read"))!;
    assert.equal(args, "pane read w1:p1 --source recent --lines 10000 --format ansi");
    const scrollback = await proc.scrollback;
    assert.equal(scrollback.toString("utf8"), "scrollback-row-1\r\nscrollback-row-2\r\n");
  } finally {
    await proc.release();
  }
});

test("a failed pane history prefetch resolves empty and the session still opens", async () => {
  const env = makeEnv("no-scrollback");
  const proc = await open(env);
  try {
    const scrollback = await proc.scrollback;
    assert.equal(scrollback.length, 0);
    assert.deepEqual(proc.replayFrame!.bytes, FRAME1);
  } finally {
    await proc.release();
  }
});

test("oversized pane history is truncated at the byte cap", async () => {
  const env = makeEnv("big-scrollback");
  const proc = await open(env);
  try {
    const scrollback = await proc.scrollback;
    assert.equal(scrollback.length, TERMINAL_LIMITS.scrollbackMaxBytes, "the prefix must fill the cap exactly");
  } finally {
    await proc.release();
  }
});

test("a stalled pane history prefetch times out without delaying the session", async () => {
  const env = makeEnv("slow-scrollback", { scrollbackTimeoutMs: 150 });
  const proc = await open(env);
  try {
    const scrollback = await proc.scrollback;
    assert.equal(scrollback.length, 0);
    assert.deepEqual(proc.replayFrame!.bytes, FRAME1);
  } finally {
    await proc.release();
  }
});


test("compactScrollback trims pane-width padding and normalizes row ends", () => {
  const padded = Buffer.from(`alpha${" ".repeat(400)}\r\nbeta\r\n   \r\n`, "utf8");
  assert.equal(compactScrollback(padded).toString("utf8"), "alpha\r\nbeta\r\n\r\n");
});

test("compactScrollback strips padding-only SGR but preserves resets of styled content", () => {
  const rows = Buffer.from(
    "\x1b[38;2;1;2;3mtext\x1b[0m   \r\n" + // reset protects styled text: kept
      "plain\x1b[48;5;12m   \x1b[0m  \r\n" + // SGR only colored padding: stripped
      "\x1b[31mred\x1b[0m\r\n" + // no padding at all: untouched
      "\x1b[1mbold", // row ending styled: reset appended
    "utf8",
  );
  assert.equal(
    compactScrollback(rows).toString("utf8"),
    "\x1b[38;2;1;2;3mtext\x1b[0m\r\nplain\r\n\x1b[31mred\x1b[0m\r\n\x1b[1mbold\x1b[0m",
  );
});

test("compactScrollback leaves over-wide rows and non-ASCII bytes untouched", () => {
  const wide = Buffer.from("x".repeat(200) + "\r\n", "utf8");
  assert.equal(compactScrollback(wide).toString("utf8"), "x".repeat(200) + "\r\n");
  // A truncated multibyte char must survive byte-exactly (latin1 round-trip).
  const split = Buffer.from("caf\xc3\r\n", "latin1");
  assert.deepEqual(compactScrollback(split), Buffer.from("caf\xc3\r\n", "latin1"));
});

test("fragmented records spanning multiple chunks parse", async () => {
  const env = makeEnv("fragmented");
  const proc = await open(env);
  try {
    assert.deepEqual(proc.replayFrame!.bytes, FRAME1);
  } finally {
    await proc.release();
  }
});

test("multiple records in one chunk are all emitted in order", async () => {
  const env = makeEnv("multi");
  const proc = await open(env);
  try {
    assert.deepEqual(proc.replayFrame!.bytes, FRAME1);
    const records = await collect(proc, (r) => r.type === "bytes" && r.seq === 2);
    const live = records.find((r) => r.type === "bytes");
    assert.ok(live && live.type === "bytes");
    assert.deepEqual(live.bytes, FRAME2);
    assert.equal(live.full, false);
    assert.equal(live.seq, 2);
  } finally {
    await proc.release();
  }
});

test("ownership conflict rejects with TerminalOwnershipConflictError", async () => {
  const env = makeEnv("conflict");
  await assert.rejects(
    open(env),
    (err: unknown) =>
      err instanceof TerminalOwnershipConflictError &&
      err.message.includes("already has an attached client") &&
      err.code === "ownership-conflict",
  );
});

test("terminal-gone closed reason rejects as a startup error", async () => {
  const env = makeEnv("gone");
  await assert.rejects(
    open(env),
    (err: unknown) => err instanceof TerminalStartupError && err.code === "handshake-closed" && err.message.includes("not found"),
  );
});

test("stderr before handshake fails open with the stderr tail", async () => {
  const env = makeEnv("stderr-startup");
  await assert.rejects(
    open(env),
    (err: unknown) =>
      err instanceof TerminalStartupError && err.code === "process-exited" && err.message.includes("fatal: herdr configuration is broken"),
  );
});

test("exit before the first frame fails open", async () => {
  const env = makeEnv("exit-before-frame");
  await assert.rejects(
    open(env),
    (err: unknown) => err instanceof TerminalStartupError && err.code === "process-exited" && err.message.includes("code 1"),
  );
});

test("no frame within the handshake bound fails open", async () => {
  const env = makeEnv("hang", { handshakeTimeoutMs: 250 });
  await assert.rejects(
    open(env),
    (err: unknown) => err instanceof TerminalStartupError && err.code === "handshake-timeout",
  );
});

test("invalid JSON record kills the session as a typed error", async () => {
  const env = makeEnv("invalid-json");
  await assert.rejects(
    open(env),
    (err: unknown) => err instanceof TerminalStartupError && err.code === "invalid-json",
  );
});

test("invalid base64 record kills the session as a typed error", async () => {
  const env = makeEnv("invalid-base64");
  await assert.rejects(
    open(env),
    (err: unknown) => err instanceof TerminalStartupError && err.code === "invalid-base64",
  );
});

test("over-long record line is rejected before allocation", async () => {
  const env = makeEnv("huge-line");
  await assert.rejects(
    open(env),
    (err: unknown) => err instanceof TerminalStartupError && err.code === "line-too-long",
  );
});

test("sendInput writes bytes as base64, never text", async () => {
  const env = makeEnv("ok");
  const proc = await open(env);
  try {
    const payload = Buffer.from("ls -la\n\x1b[1;1H", "utf8");
    assert.equal(proc.sendInput(payload), true);
    await sleep(100);
    const lines = readFileSync(env.stdinLog, "utf8").trim().split("\n");
    assert.deepEqual(lines, [`{"type":"terminal.input","bytes":"${payload.toString("base64")}"}`]);
  } finally {
    await proc.release();
  }
});

test("sendInput refuses empty, oversized, observer, and released sessions", async () => {
  const env = makeEnv("ok");
  const proc = await open(env);
  assert.equal(proc.sendInput(Buffer.alloc(0)), false);
  assert.equal(proc.sendInput(Buffer.alloc(64 * 1024 + 1)), false);
  assert.equal(proc.resize(0, 24), false);
  assert.equal(proc.resize(501, 24), false);
  assert.equal(proc.resize(80, 301), false);
  await proc.release();
  assert.equal(proc.sendInput(Buffer.from("x")), false);

  const obsEnv = makeEnv("ok");
  const obs = await open(obsEnv, { mode: "observe" });
  try {
    assert.equal(obs.sendInput(Buffer.from("x")), false);
    assert.equal(obs.resize(80, 24), false);
  } finally {
    await obs.release();
  }
});

test("resize writes the exact record", async () => {
  const env = makeEnv("ok");
  const proc = await open(env);
  try {
    assert.equal(proc.resize(100, 30), true);
    await sleep(100);
    const lines = readFileSync(env.stdinLog, "utf8").trim().split("\n");
    assert.deepEqual(lines, ['{"type":"terminal.resize","cols":100,"rows":30}']);
  } finally {
    await proc.release();
  }
});

test("release writes terminal.release, gets detached closed, exits 0, and is idempotent", async () => {
  const env = makeEnv("ok");
  const proc = await open(env);
  const closedPromise = collect(proc, (r) => r.type === "closed");
  await proc.release();
  await proc.release(); // idempotent
  const records = await closedPromise;
  const closed = records.find((r) => r.type === "closed");
  assert.ok(closed && closed.type === "closed");
  assert.equal(closed.code, "released");
  assert.equal(closed.reason, "detached");
  assert.equal(proc.exitCode(), 0);
  const lines = readFileSync(env.stdinLog, "utf8").trim().split("\n");
  assert.deepEqual(lines, ['{"type":"terminal.release"}']);
  assert.equal(proc.sendInput(Buffer.from("x")), false);
});

test("release escalates SIGTERM when the child ignores terminal.release", async () => {
  const env = makeEnv("no-release-response", { releaseGraceMs: 50, termGraceMs: 50 });
  const proc = await open(env);
  await proc.release();
  assert.ok(proc.exitCode() !== null);
});

test("exit without a closed record emits a closed(unknown) record", async () => {
  const env = makeEnv("exit-without-closed");
  const proc = await open(env);
  const records = await collect(proc, (r) => r.type === "closed");
  const closed = records.find((r) => r.type === "closed");
  assert.ok(closed && closed.type === "closed");
  assert.equal(closed.code, "unknown");
  assert.match(closed.reason ?? "", /without a terminal\.closed record/);
});

test("takeover displacing the controller surfaces closed(taken-over)", async () => {
  const env = makeEnv("taken-over");
  const proc = await open(env);
  const records = await collect(proc, (r) => r.type === "closed");
  const closed = records.find((r) => r.type === "closed");
  assert.ok(closed && closed.type === "closed");
  assert.equal(closed.code, "taken-over");
  assert.equal(closed.reason, "terminal attach taken over");
});

test("pauseOutput suppresses and resumeOutput delivers buffered frames", async () => {
  const env = makeEnv("live");
  const proc = await open(env);
  const seen: TerminalRecord[] = [];
  proc.onRecord((r) => seen.push(r));
  proc.pauseOutput();
  await sleep(500); // frame 2 fires at ~300ms
  assert.equal(seen.filter((r) => r.type === "bytes").length, 0);
  proc.resumeOutput();
  await collect(proc, (r) => r.type === "bytes" && r.seq === 2);
  assert.equal(seen.filter((r) => r.type === "bytes").length, 1);
  await proc.release();
});

test("spawn failure is a typed startup error", async () => {
  const launcher = new HerdrTerminalLauncher({ bin: "/nonexistent/herdr" });
  await assert.rejects(
    launcher.open({ target: "w1:p1", mode: "control", takeover: false, cols: 80, rows: 24 }),
    (err: unknown) => err instanceof TerminalStartupError && err.code === "spawn" && err.message.includes("nonexistent"),
  );
});

test("bounds: invalid targets and grids are rejected before spawn", async () => {
  const env = makeEnv("ok");
  await assert.rejects(open(env, { cols: 0 }), (err: unknown) => err instanceof TerminalBoundsError);
  await assert.rejects(open(env, { rows: 0 }), (err: unknown) => err instanceof TerminalBoundsError);
  await assert.rejects(open(env, { cols: 501 }), (err: unknown) => err instanceof TerminalBoundsError);
  await assert.rejects(
    env.launcher.open({ target: "w1\np1", mode: "control", takeover: false, cols: 80, rows: 24 }),
    (err: unknown) => err instanceof TerminalBoundsError,
  );
});

// ---------- capability probe ----------

test("probe: supported with a live observer handshake against a target", async () => {
  const env = makeEnv("ok");
  const cap = await env.launcher.probe("w1:p1");
  assert.deepEqual(cap, { status: "supported", herdrVersion: "0.8.0", protocol: 19 });
});

test("probe: unverified no-pane without a target", async () => {
  const env = makeEnv("ok");
  const cap = await env.launcher.probe();
  assert.equal(cap.status, "unverified");
  assert.ok(cap.status === "unverified" && cap.reason === "no-pane");
});

test("probe: a version below the floor is unsupported", async () => {
  const env = makeEnv("old-version");
  const cap = await env.launcher.probe();
  assert.equal(cap.status, "unsupported");
  assert.ok(cap.status === "unsupported" && cap.installedVersion === "0.7.0" && cap.required === "0.8.0 or newer");
});

test("probe: a newer herdr is admitted and judged by the functional checks", async () => {
  // The floor exists so a herdr release cannot take the terminal route down on
  // its version string alone; the live handshake is the real gate. 0.10.0 also
  // pins the ordering: a lexical compare would place it *below* the 0.8.0 floor.
  for (const [scenario, version, protocol] of [
    ["verified-0.8.2", "0.8.2", 20],
    ["future-version", "0.10.0", 21],
  ] as const) {
    const cap = await makeEnv(scenario).launcher.probe("w1:p1");
    assert.deepEqual(cap, { status: "supported", herdrVersion: version, protocol });
  }
});

test("probe: a newer herdr that broke the contract still fails, on evidence", async () => {
  // 0.10.0 clears the floor but never emits a first frame.
  const env = makeEnv("future-no-handshake");
  const cap = await env.launcher.probe("w1:p1");
  assert.equal(cap.status, "unsupported");
  assert.ok(cap.status === "unsupported" && cap.installedVersion === "0.10.0");
  assert.match((cap as { reason: string }).reason, /observer handshake failed/);
});

test("probe: unparseable version output", async () => {
  const env = makeEnv("no-version");
  const cap = await env.launcher.probe();
  assert.equal(cap.status, "unsupported");
});

test("probe: missing command surface", async () => {
  const env = makeEnv("no-surface");
  const cap = await env.launcher.probe();
  assert.equal(cap.status, "unsupported");
  assert.ok(cap.status === "unsupported" && cap.reason.includes("missing command surface"));
});

test("probe: missing binary", async () => {
  const launcher = new HerdrTerminalLauncher({ bin: "/nonexistent/herdr" });
  const cap = await launcher.probe("w1:p1");
  assert.equal(cap.status, "unsupported");
  assert.ok(cap.status === "unsupported" && cap.reason.includes("not found"));
});

test("probe: observer handshake failure is unsupported", async () => {
  const env = makeEnv("conflict");
  const cap = await env.launcher.probe("w1:p1");
  assert.equal(cap.status, "unsupported");
  assert.ok(cap.status === "unsupported" && cap.reason.includes("observer handshake failed"));
});

// --- NDJSON line splitting (the parser seam under the child process) ---

/** Collect the lines one reader produces for a scripted chunk sequence. */
function readAll(chunks: Buffer[], maxLineBytes = TERMINAL_LIMITS.maxLineBytes) {
  const reader = new NdjsonLineReader(maxLineBytes);
  const lines: string[] = [];
  const results: string[] = [];
  for (const chunk of chunks) {
    results.push(
      reader.push(chunk, (line) => {
        lines.push(line.toString("utf8"));
        return true;
      }),
    );
  }
  return { lines, results };
}

test("ndjson: one record split across many small chunks arrives whole and once", () => {
  const record = JSON.stringify({ type: "terminal.frame", bytes: "a".repeat(4096) });
  const chunks: Buffer[] = [];
  const whole = Buffer.from(record + "\n", "utf8");
  for (let at = 0; at < whole.length; at += 7) chunks.push(whole.subarray(at, at + 7));

  const { lines, results } = readAll(chunks);
  assert.deepEqual(lines, [record]);
  assert.ok(results.every((r) => r === "ok"));
});

test("ndjson: multiple records in one chunk keep their order", () => {
  const { lines } = readAll([Buffer.from('{"a":1}\n{"a":2}\n{"a":3}\n', "utf8")]);
  assert.deepEqual(lines, ['{"a":1}', '{"a":2}', '{"a":3}']);
});

test("ndjson: a newline exactly on a chunk boundary splits cleanly", () => {
  const { lines } = readAll([
    Buffer.from('{"a":1}', "utf8"),
    Buffer.from("\n", "utf8"),
    Buffer.from('{"a":2}\n', "utf8"),
  ]);
  assert.deepEqual(lines, ['{"a":1}', '{"a":2}']);
});

test("ndjson: empty lines are skipped and a trailing partial is retained", () => {
  const { lines } = readAll([Buffer.from('\n\n{"a":1}\ntail-without-newline', "utf8")]);
  assert.deepEqual(lines, ['{"a":1}']);
});

test("ndjson: no byte is lost across arbitrary chunk boundaries", () => {
  const records = Array.from({ length: 200 }, (_, i) => JSON.stringify({ seq: i, pad: "x".repeat(i) }));
  const stream = Buffer.from(records.join("\n") + "\n", "utf8");
  // Deterministic irregular chunking (1..97 bytes) over the whole stream.
  const chunks: Buffer[] = [];
  let at = 0;
  let size = 1;
  while (at < stream.length) {
    chunks.push(stream.subarray(at, at + size));
    at += size;
    size = (size * 7 + 3) % 97 + 1;
  }
  const { lines } = readAll(chunks);
  assert.deepEqual(lines, records);
});

test("ndjson: an over-long record is rejected before it is materialized", () => {
  const reader = new NdjsonLineReader(1024);
  // Terminated line over the bound.
  assert.equal(reader.push(Buffer.from("x".repeat(2048) + "\n", "utf8"), () => true), "line-too-long");
  // Unterminated accumulation over the bound, across chunks.
  const fresh = new NdjsonLineReader(1024);
  assert.equal(fresh.push(Buffer.from("y".repeat(600), "utf8"), () => true), "ok");
  assert.equal(fresh.push(Buffer.from("y".repeat(600), "utf8"), () => true), "pending-too-long");
  // The rejected prefix is dropped rather than carried into the next line.
  const lines: string[] = [];
  assert.equal(fresh.push(Buffer.from('{"a":1}\n', "utf8"), (l) => { lines.push(l.toString("utf8")); return true; }), "ok");
  assert.deepEqual(lines, ['{"a":1}']);
});

test("ndjson: a handler that stops leaves the rest of the chunk unread", () => {
  const reader = new NdjsonLineReader(1024);
  const lines: string[] = [];
  const result = reader.push(Buffer.from('{"a":1}\n{"a":2}\n{"a":3}\n', "utf8"), (line) => {
    lines.push(line.toString("utf8"));
    return lines.length < 2;
  });
  assert.equal(result, "stopped");
  assert.deepEqual(lines, ['{"a":1}', '{"a":2}']);
});
