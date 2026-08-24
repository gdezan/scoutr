/**
 * First-Chat-open cost on the bridge: what `GET /api/sessions` spends to
 * produce one response body for a long session.
 *
 * This exists because the long-session slices are decided by measurement, not
 * by inspection. Two traps it is shaped around:
 *
 * 1. Timing only the functions a change touched hides the rest of the
 *    response. Serializing 30k entries to JSON cost 30 ms and the parsed
 *    transcript retained 46 MiB in the memo — both invisible to a benchmark
 *    of the reader alone, and together about half of what the bounded read
 *    actually saved. `docs/performance-study.md` -> "Bridge evidence" lists
 *    what a bridge measurement is expected to cover.
 * 2. A baseline run happens in a second worktree, so a result is easy to
 *    attribute to the wrong commit. Every line of output names the commit it
 *    actually ran at.
 *
 * Comparing against a baseline:
 *
 *   git worktree add /tmp/scoutr-base <commit>
 *   ln -s "$PWD/node_modules" /tmp/scoutr-base/bridge/node_modules
 *   cd /tmp/scoutr-base/bridge && npm run bench:session-read -- --mode full
 *
 * Fixtures are generated once per shape and reused, so both trees read the
 * same bytes. `--mode full` is the legacy unpaginated read (and the only mode
 * a pre-pagination checkout understands — it ignores the extra argument);
 * `--mode page` is the bounded initial page.
 *
 * Usage:
 *   npm run bench:session-read -- [--mode page|full] [--entries N]
 *                                 [--bytes N] [--limit N] [--runs N]
 */
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, statSync, utimesSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { readSession } from "../src/routes/sessions.js";

/** Fixtures live outside the repo and outlive one run; they are large. */
const FIXTURE_ROOT = "/tmp/scoutr-bench-fixture";

interface Options {
  mode: "page" | "full";
  entries: number;
  bytes: number;
  limit: number;
  runs: number;
}

function parseOptions(argv: string[]): Options {
  const options: Options = { mode: "page", entries: 8000, bytes: 300, limit: 50, runs: 7 };
  for (let i = 0; i < argv.length; i += 2) {
    const flag = argv[i];
    const value = argv[i + 1];
    if (value === undefined) throw new Error(`missing value for ${flag}`);
    switch (flag) {
      case "--mode":
        if (value !== "page" && value !== "full") throw new Error("--mode must be page or full");
        options.mode = value;
        break;
      case "--entries": options.entries = Number(value); break;
      case "--bytes": options.bytes = Number(value); break;
      case "--limit": options.limit = Number(value); break;
      case "--runs": options.runs = Number(value); break;
      default: throw new Error(`unknown flag: ${flag}`);
    }
  }
  for (const [name, value] of Object.entries(options)) {
    if (typeof value === "number" && !Number.isInteger(value)) throw new Error(`${name} must be an integer`);
  }
  return options;
}

/** The commit this run actually read, so a worktree result cannot be mislabelled. */
function revision(): string {
  const git = (args: string[]) => execFileSync("git", args, { cwd: import.meta.dirname, encoding: "utf8" }).trim();
  try {
    return `${git(["rev-parse", "--short", "HEAD"])}${git(["status", "--porcelain"]) ? "+dirty" : ""}`;
  } catch {
    return "unknown";
  }
}

/**
 * A pi transcript of [entries] messages, every third one a bulky tool result.
 * Written once per (entries, bytes) shape: a baseline run in another worktree
 * must read the identical file, not an equivalent one.
 */
function fixture(entries: number, bytes: number): string {
  const sessions = join(FIXTURE_ROOT, "agent", "sessions");
  mkdirSync(sessions, { recursive: true });
  const path = join(sessions, `session-${entries}-${bytes}.jsonl`);
  if (existsSync(path)) return path;
  const lines = [
    `{"type":"session","version":3,"id":"s-bench","timestamp":"2026-01-01T00:00:00Z","cwd":"/tmp"}`,
    `{"type":"model_change","id":"mc","timestamp":"2026-01-01T00:00:00Z","provider":"anthropic","modelId":"claude-opus-5"}`,
  ];
  for (let i = 0; i < entries; i += 1) {
    const message = i % 3 === 0
      ? { role: "toolResult", toolCallId: `c${i}`, toolName: "read_file", content: [{ type: "text", text: "y".repeat(Math.round(bytes * 1.3)) }] }
      : { role: i % 2 === 0 ? "user" : "assistant", content: [{ type: "text", text: `turn ${i} ${"z".repeat(bytes)}` }] };
    lines.push(JSON.stringify({ type: "message", id: `e${i}`, parentId: null, timestamp: "2026-01-01T00:00:00Z", message }));
  }
  writeFileSync(path, `${lines.join("\n")}\n`);
  return path;
}

declare const gc: (() => void) | undefined;

/** Heap the caches still hold after a forced GC — not transient parse peak. */
function retainedHeapMiB(baseline: number): number | null {
  if (!gc) return null;
  gc();
  return round((process.memoryUsage().heapUsed - baseline) / 1024 / 1024, 1);
}

function round(value: number, digits: number): number {
  return Number(value.toFixed(digits));
}

function median(samples: number[]): number {
  return [...samples].sort((a, b) => a - b)[Math.floor(samples.length / 2)]!;
}

const options = parseOptions(process.argv.slice(2));
const path = fixture(options.entries, options.bytes);
process.env.PI_CODING_AGENT_DIR = join(FIXTURE_ROOT, "agent");

// The 4th argument is ignored by a pre-pagination checkout, which is what
// makes `--mode full` runnable in a baseline worktree.
const read = options.mode === "page"
  ? () => readSession(path, null, undefined, { limit: options.limit })
  : () => readSession(path, null);

gc?.();
const baselineHeap = process.memoryUsage().heapUsed;
const cold: number[] = [];
const warm: number[] = [];
let bytes = 0;
let serializeMs = 0;
let entriesReturned = 0;

for (let run = 0; run <= options.runs; run += 1) {
  // A fresh mtime invalidates every (path, mtime, size) memo without touching
  // a byte of the file. That is the cost of opening a session that changed —
  // which is every open while its agent is working. The second read of each
  // pair is the memoized steady state the 2.5 s poll actually sees.
  const when = new Date(Date.now() + run * 1000);
  utimesSync(path, when, when);

  let at = performance.now();
  const result = await read();
  const coldMs = performance.now() - at;
  at = performance.now();
  await read();
  const warmMs = performance.now() - at;
  if (run > 0) { // discard the warm-up iteration
    cold.push(coldMs);
    warm.push(warmMs);
  }

  at = performance.now();
  const body = JSON.stringify({ ok: true, ...result });
  serializeMs = round(performance.now() - at, 1);
  bytes = Buffer.byteLength(body);
  entriesReturned = result.entries.length;
}

console.log(JSON.stringify({
  revision: revision(),
  mode: options.mode,
  fixtureMiB: round(statSync(path).size / 1024 / 1024, 2),
  fixtureEntries: options.entries,
  entryBytes: options.bytes,
  entriesReturned,
  coldReadMs: round(median(cold), 1),
  warmReadMs: round(median(warm), 2),
  serializeMs,
  responseKiB: round(bytes / 1024, 1),
  // null without --expose-gc; `npm run bench:session-read` passes it.
  retainedHeapMiB: retainedHeapMiB(baselineHeap),
}, null, 2));
