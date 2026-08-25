import assert from "node:assert/strict";
import { chmod, mkdtemp, mkdir, symlink, utimes, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import {
  deleteStoredSession,
  listSessionCatalog,
  renameStoredSession,
  SessionCatalogError,
} from "../src/session-catalog.js";

interface SessionFixtureLine {
  type: string;
  id?: string;
  version?: number;
  cwd?: string;
  timestamp?: string;
  provider?: string;
  modelId?: string;
  name?: string;
  message?: {
    role: string;
    content: Array<{ type: string; text: string }>;
  };
}

async function newCatalogRoot(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "scoutr-catalog-"));
  process.env.PI_CODING_AGENT_SESSION_DIR = root;
  return root;
}

async function writeSession(
  root: string,
  project: string,
  fileName: string,
  lines: SessionFixtureLine[],
  modifiedAt: string,
): Promise<string> {
  const directory = join(root, project);
  await mkdir(directory, { recursive: true });
  const path = join(directory, fileName);
  await writeFile(path, `${lines.map((line) => JSON.stringify(line)).join("\n")}\n`);
  await utimes(path, new Date(modifiedAt), new Date(modifiedAt));
  return path;
}

function sessionLine(id: string, cwd: string, timestamp: string) {
  return { type: "session", version: 3, id, cwd, timestamp };
}

let nextEntryId = 0;

function userLine(text: string) {
  nextEntryId += 1;
  return {
    type: "message",
    id: `e${nextEntryId}`,
    timestamp: "2026-01-01T00:00:00.000Z",
    message: { role: "user", content: [{ type: "text", text }] },
  };
}

describe("session catalog", () => {
  it("lists persisted sessions newest-first and joins active pane state", async () => {
    const root = await newCatalogRoot();
    const older = await writeSession(
      root,
      "project-a",
      "older.jsonl",
      [
        sessionLine("session-old", "/work/alpha", "2026-01-01T00:00:00.000Z"),
        userLine("Investigate the flaky build"),
        { type: "model_change", provider: "openai", modelId: "gpt-5" },
      ],
      "2026-01-02T00:00:00.000Z",
    );
    await writeSession(
      root,
      "project-b",
      "newer.jsonl",
      [
        sessionLine("session-new", "/work/beta", "2026-01-03T00:00:00.000Z"),
        userLine("Original prompt"),
        { type: "session_info", name: "Release review" },
      ],
      "2026-01-04T00:00:00.000Z",
    );

    const result = await listSessionCatalog({
      roots: [root],
      active: [{
        path: older,
        paneId: "pane-1",
        workspaceId: "workspace-1",
        tabId: "tab-1",
        status: "working",
        title: "Live build investigation",
      }],
    });

    assert.deepEqual(result.sessions.map((entry) => entry.session.key?.path.split("/").pop()), ["newer.jsonl", "older.jsonl"]);
    assert.equal(result.sessions[0]?.session.title, "Release review");
    assert.equal(result.sessions[0]?.session.live, null);
    assert.equal(result.sessions[1]?.session.title, "Live build investigation");
    assert.equal(result.sessions[1]?.session.live?.paneId, "pane-1");
    assert.equal(result.sessions[1]?.session.live?.tabId, "tab-1");
    assert.equal(result.sessions[1]?.session.model, "openai/gpt-5");
    assert.equal(result.sessions[1]?.session.latestActivity, "Investigate the flaky build");
  });

  it("searches bounded metadata and reports result truncation", async () => {
    const root = await newCatalogRoot();
    await writeSession(
      root,
      "project",
      "one.jsonl",
      [sessionLine("one", "/repos/scoutr", "2026-01-01T00:00:00.000Z"), userLine("Fix Android navigation")],
      "2026-01-02T00:00:00.000Z",
    );
    await writeSession(
      root,
      "project",
      "two.jsonl",
      [sessionLine("two", "/repos/bridge", "2026-01-02T00:00:00.000Z"), userLine("Add navigation endpoint")],
      "2026-01-03T00:00:00.000Z",
    );

    const search = await listSessionCatalog({ roots: [root], query: "ANDROID", limit: 10 });
    assert.deepEqual(search.sessions.map((entry) => entry.session.key?.path.split("/").pop()), ["one.jsonl"]);

    const limited = await listSessionCatalog({ roots: [root], query: "navigation", limit: 1 });
    assert.equal(limited.sessions.length, 1);
    assert.equal(limited.truncated, true);
  });

  it("ignores malformed files and symlinks that escape the sessions root", async () => {
    const root = await newCatalogRoot();
    const outside = await mkdtemp(join(tmpdir(), "scoutr-outside-"));
    await mkdir(join(root, "project"), { recursive: true });
    await writeFile(join(root, "project", "malformed.jsonl"), "not json\n");
    const outsideSession = await writeSession(
      outside,
      "project",
      "outside.jsonl",
      [sessionLine("outside", "/private", "2026-01-01T00:00:00.000Z")],
      "2026-01-01T00:00:00.000Z",
    );
    await symlink(outsideSession, join(root, "project", "escaped.jsonl"));

    const result = await listSessionCatalog({ roots: [root] });
    assert.deepEqual(result.sessions, []);
  });

  it("renames and deletes a stored session", async () => {
    const root = await newCatalogRoot();
    const path = await writeSession(
      root,
      "project",
      "saved.jsonl",
      [sessionLine("saved", "/work/saved", "2026-01-01T00:00:00.000Z")],
      "2026-01-01T00:00:00.000Z",
    );

    await renameStoredSession(path, "Release follow-up");
    const renamed = await listSessionCatalog({ roots: [root] });
    assert.equal(renamed.sessions[0]?.session.title, "Release follow-up");

    await deleteStoredSession(path);
    const deleted = await listSessionCatalog({ roots: [root] });
    assert.deepEqual(deleted.sessions, []);
  });

  it("splits the candidate budget fairly so a giant first store cannot starve later roots", async () => {
    // Two registered stores: pi (scanned first) with more files than its half
    // of the global candidate cap, claude behind it with one session. The
    // claude root must still be scanned.
    const piRoot = await mkdtemp(join(tmpdir(), "scoutr-catalog-pi-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = piRoot;
    const claudeRoot = await mkdtemp(join(tmpdir(), "scoutr-catalog-claude-"));
    process.env.CLAUDECONFIGDIR = claudeRoot;

    // One project dir with MAX_CANDIDATES/2 files (1000) exhausts pi's
    // per-root budget exactly; file 1001 proves the budget, not the cap.
    const piProject = join(piRoot, "project-a");
    await mkdir(piProject, { recursive: true });
    const slot = "2026-01-01T00:00:00.000Z";
    for (let i = 0; i < 1001; i += 1) {
      const file = join(piProject, `s${String(i).padStart(4, "0")}.jsonl`);
      await writeFile(file, `${JSON.stringify(sessionLine(`s${i}`, "/work/alpha", slot))}\n`);
      // old mtimes keep the pi store out of the newest-500 metadata slice
      await utimes(file, new Date(slot), new Date(slot));
    }

    const claudeFile = join(claudeRoot, "projects", "-encoded-", "7d012817-0fb3-4810-9172-f26710238ead.jsonl");
    await mkdir(join(claudeRoot, "projects", "-encoded-"), { recursive: true });
    await writeFile(
      claudeFile,
      `${JSON.stringify({ type: "user", uuid: "u1", sessionId: "7d012817-0fb3-4810-9172-f26710238ead", cwd: "/work/beta", timestamp: "2026-01-01T00:00:01.000Z", message: { role: "user", content: "hello" } })}\n`,
    );
    // newest file wins the metadata slice despite the saturated pi store
    await utimes(claudeFile, new Date(), new Date());

    const catalog = await listSessionCatalog({ roots: [piRoot, join(claudeRoot, "projects")], limit: 200 });
    assert.equal(
      catalog.sessions.some((entry) => entry.session.key?.path.endsWith("7d012817-0fb3-4810-9172-f26710238ead.jsonl")),
      true,
      "claude root must be scanned despite the saturated pi store",
    );
    assert.equal(catalog.truncated, true, "pi hit its per-root budget, so the listing is truncated");
  });

  it("keeps the newest sessions of an over-budget root, not the ones the walk happened to reach first", async () => {
    // The claude store grows to ~1000 transcripts across project dirs. When the
    // per-root budget was spent in directory order, today's sessions — written
    // into a project dir the walk reached last — never made the listing at all.
    const piRoot = await mkdtemp(join(tmpdir(), "scoutr-catalog-pi-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = piRoot;
    const claudeRoot = await mkdtemp(join(tmpdir(), "scoutr-catalog-claude-"));
    process.env.CLAUDECONFIGDIR = claudeRoot;
    const projects = join(claudeRoot, "projects");

    const claudeLine = (id: string, cwd: string, timestamp: string) => JSON.stringify({
      type: "user",
      uuid: `u-${id}`,
      sessionId: id,
      cwd,
      timestamp,
      message: { role: "user", content: `prompt ${id}` },
    });

    // First project dir: over the per-root budget on its own, all of it old.
    const stale = join(projects, "-work-old-");
    await mkdir(stale, { recursive: true });
    const staleAt = new Date("2026-01-01T00:00:00.000Z");
    for (let i = 0; i < 1200; i += 1) {
      const file = join(stale, `old-${String(i).padStart(4, "0")}.jsonl`);
      await writeFile(file, `${claudeLine(`old-${i}`, "/work/old", "2026-01-01T00:00:00.000Z")}\n`);
      await utimes(file, staleAt, staleAt);
    }

    // Second project dir, written after the budget would already be spent.
    const fresh = join(projects, "-work-today-");
    await mkdir(fresh, { recursive: true });
    const freshFile = join(fresh, "today.jsonl");
    await writeFile(freshFile, `${claudeLine("today", "/work/today", "2026-08-14T00:00:00.000Z")}\n`);
    await utimes(freshFile, new Date(), new Date());

    const catalog = await listSessionCatalog({ roots: [piRoot, projects], limit: 10 });
    assert.equal(catalog.sessions[0]?.session.key?.path, freshFile, "the newest session must survive the scan budget");
  });

  it("rejects invalid limits and queries", async () => {
    const root = await newCatalogRoot();
    await assert.rejects(() => listSessionCatalog({ roots: [root], limit: 0 }), SessionCatalogError);
    await assert.rejects(() => listSessionCatalog({ roots: [root], query: "bad\nquery" }), SessionCatalogError);
  });

  it("tolerates a dangling symlink inside a root", async () => {
    const root = await newCatalogRoot();
    await writeSession(
      root,
      "project",
      "good.jsonl",
      [sessionLine("good", "/work/good", "2026-01-01T00:00:00.000Z")],
      "2026-01-01T00:00:00.000Z",
    );
    await symlink(join(root, "project", "ghost.jsonl"), join(root, "project", "dangling.jsonl"));

    const result = await listSessionCatalog({ roots: [root] });
    assert.deepEqual(result.sessions.map((entry) => entry.session.title), ["good"], "a bad entry must not fail the whole listing");
  });

  it("skips an unreadable subdirectory instead of failing the listing", async (t) => {
    if (process.getuid?.() === 0) {
      t.skip("running as root; chmod 0o000 is not an obstacle");
      return;
    }
    const root = await newCatalogRoot();
    await writeSession(
      root,
      "project",
      "good.jsonl",
      [sessionLine("good", "/work/good", "2026-01-01T00:00:00.000Z")],
      "2026-01-01T00:00:00.000Z",
    );
    const locked = join(root, "locked");
    await mkdir(locked);
    await chmod(locked, 0o000);
    try {
      const result = await listSessionCatalog({ roots: [root] });
      assert.deepEqual(result.sessions.map((entry) => entry.session.title), ["good"], "the locked directory must be skipped");
    } finally {
      await chmod(locked, 0o755);
    }
  });

  it("serves an unchanged store from the catalog memo", async () => {
    const root = await newCatalogRoot();
    await writeSession(
      root,
      "project",
      "one.jsonl",
      [sessionLine("one", "/work/one", "2026-01-01T00:00:00.000Z"), userLine("First prompt")],
      "2026-01-02T00:00:00.000Z",
    );
    const first = await listSessionCatalog({ roots: [root] });
    const second = await listSessionCatalog({ roots: [root] });
    assert.deepEqual(second.sessions, first.sessions, "an unchanged store must return identical metadata");

    // A new file shifts the mtime+size keys, so the memo must be bypassed.
    await writeSession(
      root,
      "project",
      "two.jsonl",
      [sessionLine("two", "/work/two", "2026-01-02T00:00:00.000Z"), userLine("Second prompt")],
      "2026-01-03T00:00:00.000Z",
    );
    const third = await listSessionCatalog({ roots: [root] });
    assert.deepEqual(
      third.sessions.map((entry) => entry.session.key?.path.split("/").pop()),
      ["two.jsonl", "one.jsonl"],
    );
  });

  it("discovers AGY sessions stored under brain/<conv-id>/.system_generated/logs/", async () => {
    const agyBrainRoot = await mkdtemp(join(tmpdir(), "scoutr-agy-brain-"));
    process.env.ANTIGRAVITY_CONFIG_DIR = agyBrainRoot;
    const convDir = join(agyBrainRoot, "brain", "conv-999", ".system_generated", "logs");
    await mkdir(convDir, { recursive: true });
    const transcriptPath = join(convDir, "transcript.jsonl");
    const jsonl = [
      JSON.stringify({
        step_index: 0,
        source: "USER_EXPLICIT",
        type: "USER_INPUT",
        created_at: "2026-01-01T00:00:00.000Z",
        content: "<USER_REQUEST>Fix bug in AGY</USER_REQUEST>",
      }),
    ].join("\n");
    await writeFile(transcriptPath, `${jsonl}\n`);

    const result = await listSessionCatalog({ roots: [join(agyBrainRoot, "brain")] });
    assert.equal(result.sessions.length, 1);
    assert.equal(result.sessions[0]?.session.key?.agentKind, "agy");
    assert.equal(result.sessions[0]?.session.key?.path, transcriptPath);
    assert.equal(result.sessions[0]?.session.latestActivity, "Fix bug in AGY");
  });
});
