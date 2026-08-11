import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readSession } from "../src/routes/sessions.js";

/**
 * Fixture whose message ids are random 8-hex strings in an order that is NOT
 * lexically sorted — the exact shape of real pi session files. A lexical
 * `entryId > since` filter re-sends already-loaded entries from here.
 */
function fixtureJsonl(): string {
  const ids = ["ff676fb5", "a1b2c3d4", "77d90b6d", "0f1e2d3c", "b5a4c3d2"];
  return (
    `{"type":"session","version":3,"id":"s1","timestamp":"2026-08-09T16:39:48Z","cwd":"/tmp"}\n` +
    ids
      .map(
        (id, i) =>
          `{"type":"message","id":"${id}","parentId":null,"timestamp":"2026-08-09T16:39:5${i}Z","message":{"role":"${i % 2 === 0 ? "user" : "assistant"}","content":[{"type":"text","text":"m${i}"}]}}`,
      )
      .join("\n") +
    "\n"
  );
}

function fixtureDir(): { dir: string; agentDir: string; file: string } {
  const dir = mkdtempSync(join(tmpdir(), "cockpit-session-"));
  const agentDir = join(dir, "agent");
  const sessionsDir = join(agentDir, "sessions");
  mkdirSync(sessionsDir, { recursive: true });
  const file = join(sessionsDir, "session.jsonl");
  writeFileSync(file, fixtureJsonl());
  return { dir, agentDir, file };
}

test("readSession returns the full file when no cursor is given", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null);
    assert.equal(result.since, null);
    assert.deepEqual(result.entries.map((e) => e.entryId), ["ff676fb5", "a1b2c3d4", "77d90b6d", "0f1e2d3c", "b5a4c3d2"]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession with the last entry as cursor returns nothing (no overlap)", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, "b5a4c3d2");
    assert.deepEqual(result.entries, []);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession with a middle cursor returns only entries after it in file order", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, "77d90b6d");
    assert.deepEqual(result.entries.map((e) => e.entryId), ["0f1e2d3c", "b5a4c3d2"]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession with an unknown cursor returns a full snapshot with since null", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, "deadbeef");
    assert.equal(result.since, null);
    assert.equal(result.entries.length, 5);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession rejects paths outside the agent root", async () => {
  const { dir, agentDir } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await assert.rejects(() => readSession("/etc/passwd", null), /outside a registered session store/);
    await assert.rejects(
      () => readSession(`${agentDir}-evil/session.jsonl`, null),
      /outside a registered session store/,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
