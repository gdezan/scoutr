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
    const root = await mkdtemp(join(tmpdir(), "cockpit-catalog-"));
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
      root,
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
    const root = await mkdtemp(join(tmpdir(), "cockpit-catalog-"));
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

    const search = await listSessionCatalog({ root, query: "ANDROID", limit: 10 });
    assert.deepEqual(search.sessions.map((session) => session.id), ["one"]);

    const limited = await listSessionCatalog({ root, query: "navigation", limit: 1 });
    assert.equal(limited.sessions.length, 1);
    assert.equal(limited.truncated, true);
  });

  it("ignores malformed files and symlinks that escape the sessions root", async () => {
    const root = await mkdtemp(join(tmpdir(), "cockpit-catalog-"));
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

    const result = await listSessionCatalog({ root });
    assert.deepEqual(result.sessions, []);
  });

  it("renames and deletes a stored session", async () => {
    const root = await mkdtemp(join(tmpdir(), "cockpit-catalog-"));
    const path = await writeSession(
      root,
      "project",
      "saved.jsonl",
      [sessionLine("saved", "/work/saved", "2026-01-01T00:00:00.000Z")],
      "2026-01-01T00:00:00.000Z",
    );

    await renameStoredSession(path, "Release follow-up", root);
    const renamed = await listSessionCatalog({ root });
    assert.equal(renamed.sessions[0]?.title, "Release follow-up");

    await deleteStoredSession(path, root);
    const deleted = await listSessionCatalog({ root });
    assert.deepEqual(deleted.sessions, []);
  });

  it("rejects invalid limits and queries", async () => {
    const root = await mkdtemp(join(tmpdir(), "cockpit-catalog-"));
    await assert.rejects(() => listSessionCatalog({ root, limit: 0 }), SessionCatalogError);
    await assert.rejects(() => listSessionCatalog({ root, query: "bad\nquery" }), SessionCatalogError);
  });
});
