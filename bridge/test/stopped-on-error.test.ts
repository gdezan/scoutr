import { test, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { endsInFailedModelCall, makeErrorStopInspector } from "../src/push/stopped-on-error.js";
import type { TranscriptEntry } from "../src/transcript.js";
import { pane, workspace } from "./support/snapshot.js";
import type { SessionSnapshot } from "../src/herdr/types.js";

/**
 * The 503 give-up signature, read off a real stopped session: pi appends one
 * assistant record per failed retry and then stops writing, so the file ends
 * on `stopReason:"error"`. herdr reports the pane idle either way — the tail
 * is the only thing that distinguishes this from a clean finish.
 */

function entry(overrides: Partial<TranscriptEntry>): TranscriptEntry {
  return {
    entryId: "e1",
    parentId: null,
    timestamp: "2026-08-25T15:00:00.000Z",
    role: "assistant",
    content: [],
    ...overrides,
  };
}

test("error record last means stopped on error", () => {
  assert.equal(
    endsInFailedModelCall([entry({ role: "user" }), entry({ stopReason: "error" })]),
    true,
  );
});

test("trailing tool results are stepped over to find the last speaking entry", () => {
  assert.equal(
    endsInFailedModelCall([
      entry({ stopReason: "toolUse", toolName: "bash" }),
      entry({ role: "toolResult", toolCallId: "call_1" }),
      entry({ stopReason: "error" }),
      entry({ role: "toolResult", toolCallId: "call_2" }),
    ]),
    true,
  );
});

test("a clean finish is not an error stop", () => {
  assert.equal(endsInFailedModelCall([entry({ role: "user" }), entry({ stopReason: "stop" })]), false);
});

test("an aborted turn is not an error stop", () => {
  assert.equal(endsInFailedModelCall([entry({ stopReason: "aborted" })]), false);
});

test("a user entry last means the conversation moved on", () => {
  assert.equal(endsInFailedModelCall([entry({ stopReason: "error" }), entry({ role: "user" })]), false);
});

test("an empty transcript is not an error stop", () => {
  assert.equal(endsInFailedModelCall([]), false);
});

const ERROR_LINE = JSON.stringify({
  type: "message",
  id: "83f8b0a2",
  parentId: "fe628e31",
  timestamp: "2026-08-25T14:53:58.570Z",
  message: {
    role: "assistant",
    content: [],
    api: "openai-completions",
    provider: "opencode-go",
    model: "ox-alpha-free",
    usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0 },
    stopReason: "error",
    errorMessage: '503: {"type":"server_error"}',
  },
});
const OK_LINE = JSON.stringify({
  type: "message",
  id: "ok1",
  parentId: null,
  timestamp: "2026-08-25T14:54:20.000Z",
  message: {
    role: "assistant",
    content: [{ type: "text", text: "done" }],
    usage: { input: 10, output: 5 },
    stopReason: "stop",
  },
});

let dir: string;
let previousRoot: string | undefined;

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), "scoutr-error-stop-"));
  previousRoot = process.env.PI_CODING_AGENT_SESSION_DIR;
  process.env.PI_CODING_AGENT_SESSION_DIR = dir;
});

afterEach(async () => {
  if (previousRoot === undefined) delete process.env.PI_CODING_AGENT_SESSION_DIR;
  else process.env.PI_CODING_AGENT_SESSION_DIR = previousRoot;
  await rm(dir, { recursive: true, force: true });
});

function snapshotFor(path: string | null): SessionSnapshot {
  return {
    version: "0.8.0",
    protocol: 19,
    focused_workspace_id: null,
    focused_tab_id: null,
    focused_pane_id: null,
    workspaces: [workspace()],
    tabs: [],
    layouts: [],
    agents: [],
    panes: [
      pane({
        pane_id: "w1:p1",
        agent: "pi",
        agent_status: "idle",
        cwd: "/home/gdezan/Dev/scoutr",
        agent_session:
          path === null ? null : { source: "herdr:pi", agent: "pi", kind: "path", value: path },
      }),
    ],
  };
}

async function writeSession(name: string, lines: string[]): Promise<string> {
  const path = join(dir, name);
  await writeFile(path, lines.join("\n") + "\n");
  return path;
}

test("inspector reads a real pi transcript that died on 503s", async () => {
  const path = await writeSession("dead.jsonl", [
    '{"type":"session","version":3,"id":"abc","timestamp":"2026-08-25T14:30:00.000Z","cwd":"/home/gdezan/Dev/scoutr"}',
    OK_LINE,
    ERROR_LINE,
    ERROR_LINE,
  ]);
  const inspect = makeErrorStopInspector({ snapshot: snapshotFor(path) });
  assert.equal(await inspect("w1:p1"), true);
});

test("inspector reads a real pi transcript that finished cleanly", async () => {
  const path = await writeSession("alive.jsonl", [
    '{"type":"session","version":3,"id":"abc","timestamp":"2026-08-25T14:30:00.000Z","cwd":"/home/gdezan/Dev/scoutr"}',
    ERROR_LINE,
    ERROR_LINE,
    OK_LINE,
  ]);
  const inspect = makeErrorStopInspector({ snapshot: snapshotFor(path) });
  assert.equal(await inspect("w1:p1"), false);
});

test("unknown panes and missing session refs are quiet finishes", async () => {
  const inspect = makeErrorStopInspector({ snapshot: snapshotFor(null) });
  assert.equal(await inspect("w1:p1"), false);
  assert.equal(await inspect("w1:gone"), false);
});

test("an unreadable transcript is a quiet finish, never a false alarm", async () => {
  const inspect = makeErrorStopInspector({
    snapshot: snapshotFor(join(dir, "missing.jsonl")),
  });
  assert.equal(await inspect("w1:p1"), false);
});

test("system records are not speaking entries and do not mask an error stop", () => {
  assert.equal(
    endsInFailedModelCall([entry({ stopReason: "error" }), entry({ role: "system" })]),
    true,
  );
});

test("a bash execution is the user moving on after the error", () => {
  assert.equal(
    endsInFailedModelCall([entry({ stopReason: "error" }), entry({ role: "bashExecution" })]),
    false,
  );
});
