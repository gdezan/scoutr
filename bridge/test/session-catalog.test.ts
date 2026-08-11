import assert from "node:assert/strict";
import { mkdtemp, mkdir, symlink, utimes, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import {
  deleteStoredSession,
  listSessionCatalog,
  renameStoredSession,
  SessionCatalogError,
} from "../src/session-catalog.js";

async function newCatalogRoot(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "cockpit-catalog-"));
  process.env.PI_CODING_AGENT_SESSION_DIR = root;
  return root;
}

async function writeSession(
  root: string,
  project: string,
  fileName: string,
  lines: Record<string, unknown>[],
  modifiedAt: string,
): Promise<string> {
  const directory = join(root, project);
  await mkdir(directory, { recursive: true });
  const path = join(directory, fileName);
  await writeFile(path, `${lines.map((line) => JSON.stringify(line)).join("\n")}\n`);
  await utimes(path, new Date(modifiedAt), new Date(modifiedAt));
  return path;
}

function sessionLine(id: string, cwd: string, timestamp: string): Record<string, unknown> {
  return { type: "session", version: 3, id, cwd, timestamp };
}

let nextEntryId = 0;

function userLine(text: string): Record<string, unknown> {
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
        status: "working",
        title: "Live build investigation",
      }],
    });

    assert.deepEqual(result.sessions.map((session) => session.id), ["session-new", "session-old"]);
    assert.equal(result.sessions[0]?.title, "Release review");
    assert.equal(result.sessions[0]?.status, "completed");
    assert.equal(result.sessions[1]?.title, "Live build investigation");
    assert.equal(result.sessions[1]?.active, true);
    assert.equal(result.sessions[1]?.paneId, "pane-1");
    assert.equal(result.sessions[1]?.model, "openai/gpt-5");
    assert.equal(result.sessions[1]?.preview, "Investigate the flaky build");
  });

  it("searches bounded metadata and reports result truncation", async () => {
    const root = await newCatalogRoot();
    await writeSession(
      root,
      "project",
      "one.jsonl",
      [sessionLine("one", "/repos/cockpit", "2026-01-01T00:00:00.000Z"), userLine("Fix Android navigation")],
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
    assert.deepEqual(search.sessions.map((session) => session.id), ["one"]);

    const limited = await listSessionCatalog({ roots: [root], query: "navigation", limit: 1 });
    assert.equal(limited.sessions.length, 1);
    assert.equal(limited.truncated, true);
  });

  it("ignores malformed files and symlinks that escape the sessions root", async () => {
    const root = await newCatalogRoot();
    const outside = await mkdtemp(join(tmpdir(), "cockpit-outside-"));
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
    assert.equal(renamed.sessions[0]?.title, "Release follow-up");

    await deleteStoredSession(path);
    const deleted = await listSessionCatalog({ roots: [root] });
    assert.deepEqual(deleted.sessions, []);
  });

  it("splits the candidate budget fairly so a giant first store cannot starve later roots", async () => {
    // Two registered stores: pi (scanned first) with more files than its half
    // of the global candidate cap, claude behind it with one session. The
    // claude root must still be scanned.
    const piRoot = await mkdtemp(join(tmpdir(), "cockpit-catalog-pi-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = piRoot;
    const claudeRoot = await mkdtemp(join(tmpdir(), "cockpit-catalog-claude-"));
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
      catalog.sessions.some((sess) => sess.path.endsWith("7d012817-0fb3-4810-9172-f26710238ead.jsonl")),
      true,
      "claude root must be scanned despite the saturated pi store",
    );
    assert.equal(catalog.truncated, true, "pi hit its per-root budget, so the listing is truncated");
  });

  it("rejects invalid limits and queries", async () => {
    const root = await newCatalogRoot();
    await assert.rejects(() => listSessionCatalog({ roots: [root], limit: 0 }), SessionCatalogError);
    await assert.rejects(() => listSessionCatalog({ roots: [root], query: "bad\nquery" }), SessionCatalogError);
  });
});
