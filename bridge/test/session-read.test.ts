import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync, appendFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseSessionPageLimit, readSession } from "../src/routes/sessions.js";

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
  const dir = mkdtempSync(join(tmpdir(), "scoutr-session-"));
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

test("readSession serves an unchanged file from the transcript memo", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const first = await readSession(file, null);
    const second = await readSession(file, null);
    // The memo reuses the parsed Transcript: entries are the same objects,
    // not a re-parse (the 2.5s poll steady state must cost one stat, not a
    // full read + JSON parse).
    assert.equal(first.entries[0], second.entries[0]);
    assert.deepEqual(second.entries, first.entries);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession drops the memo when the file grows", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await readSession(file, null);
    appendFileSync(
      file,
      `{"type":"message","id":"cafe1234","parentId":null,"timestamp":"2026-08-09T16:39:56Z","message":{"role":"user","content":[{"type":"text","text":"m6"}]}}\n`,
    );
    const result = await readSession(file, null);
    assert.equal(result.entries.length, 6);
    assert.equal(result.entries[5].entryId, "cafe1234");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession legacy full snapshot reports no older history", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null);
    assert.equal(result.beforeCursor, null);
    assert.equal(result.hasMoreBefore, false);
    assert.equal(result.lastEntryId, "b5a4c3d2");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession with limit returns the newest page in file order", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null, undefined, { limit: 2 });
    assert.deepEqual(result.entries.map((e) => e.entryId), ["0f1e2d3c", "b5a4c3d2"]);
    assert.equal(result.since, null);
    assert.equal(result.beforeCursor, "0f1e2d3c");
    assert.equal(result.hasMoreBefore, true);
    assert.equal(result.lastEntryId, "b5a4c3d2");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession reverse page returns the preceding chronological entries", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const first = await readSession(file, null, undefined, { before: "0f1e2d3c", limit: 2 });
    assert.deepEqual(first.entries.map((e) => e.entryId), ["a1b2c3d4", "77d90b6d"]);
    assert.equal(first.beforeCursor, "a1b2c3d4");
    assert.equal(first.hasMoreBefore, true);

    const middle = await readSession(file, null, undefined, { before: "77d90b6d", limit: 2 });
    assert.deepEqual(middle.entries.map((e) => e.entryId), ["ff676fb5", "a1b2c3d4"]);
    assert.equal(middle.beforeCursor, null);
    assert.equal(middle.hasMoreBefore, false);

    const finalPage = await readSession(file, null, undefined, { before: "a1b2c3d4", limit: 2 });
    assert.deepEqual(finalPage.entries.map((e) => e.entryId), ["ff676fb5"]);
    assert.equal(finalPage.beforeCursor, null);
    assert.equal(finalPage.hasMoreBefore, false);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession reverse page keeps random ids in file order", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    const result = await readSession(file, null, undefined, { before: "b5a4c3d2", limit: 3 });
    // File order, not lexical: ff676fb5 > a1b2c3d4 > 77d90b6d would be wrong.
    assert.deepEqual(result.entries.map((e) => e.entryId), ["a1b2c3d4", "77d90b6d", "0f1e2d3c"]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession rejects an invalid limit", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await assert.rejects(() => readSession(file, null, undefined, { limit: 0 }), /limit must be an integer between 1 and 200/);
    await assert.rejects(() => readSession(file, null, undefined, { limit: 201 }), /limit must be an integer between 1 and 200/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession rejects combining since and before", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await assert.rejects(
      () => readSession(file, "b5a4c3d2", undefined, { before: "0f1e2d3c", limit: 2 }),
      /since and before cannot be combined/,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
test("readSession rejects before without limit", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await assert.rejects(
      () => readSession(file, null, undefined, { before: "0f1e2d3c" }),
      /before requires limit/,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("readSession conflicts on a stale reverse cursor", async () => {
  const { dir, agentDir, file } = fixtureDir();
  process.env.PI_CODING_AGENT_DIR = agentDir;
  try {
    await assert.rejects(
      () => readSession(file, null, undefined, { before: "deadbeef", limit: 2 }),
      /reverse cursor is no longer in the transcript/,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("parseSessionPageLimit accepts 1 through 200 and rejects the rest", () => {
  assert.equal(parseSessionPageLimit(null), null);
  assert.equal(parseSessionPageLimit("1"), 1);
  assert.equal(parseSessionPageLimit("200"), 200);
  assert.throws(() => parseSessionPageLimit("0"), /limit must be an integer between 1 and 200/);
  assert.throws(() => parseSessionPageLimit("201"), /limit must be an integer between 1 and 200/);
  assert.throws(() => parseSessionPageLimit("1.5"), /limit must be an integer between 1 and 200/);
  assert.throws(() => parseSessionPageLimit("abc"), /limit must be an integer between 1 and 200/);
});
