import assert from "node:assert/strict";
import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { claudeBackend } from "../src/agents/claude/index.js";
import { piBackend } from "../src/agents/pi/index.js";
import { keyForAgent, keyForStoredSession } from "../src/session-model.js";

describe("canonical session keys", () => {
  it("matches a pi live path reference to the stored transcript key", async () => {
    const root = await mkdtemp(join(tmpdir(), "scoutr-session-key-pi-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = root;
    const path = join(root, "project", "session.jsonl");
    await mkdir(join(root, "project"), { recursive: true });
    await writeFile(path, "\n");

    const live = await keyForAgent(piBackend, {
      source: "herdr:pi",
      agent: "pi",
      kind: "path",
      value: path,
    });

    assert.deepEqual(live, keyForStoredSession(piBackend, path));
  });

  it("matches a claude id reference to the stored transcript key", async () => {
    const root = await mkdtemp(join(tmpdir(), "scoutr-session-key-claude-"));
    process.env.CLAUDECONFIGDIR = root;
    const project = join(root, "projects", "-work-project");
    const path = join(project, "session-123.jsonl");
    await mkdir(project, { recursive: true });
    await writeFile(path, "\n");

    const live = await keyForAgent(
      claudeBackend,
      {
        source: "herdr:claude",
        agent: "claude",
        kind: "id",
        value: "session-123",
      },
      "/work/project",
    );

    assert.deepEqual(live, keyForStoredSession(claudeBackend, path));
  });

  it("does not turn an unowned resolved path into durable identity", async () => {
    const root = await mkdtemp(join(tmpdir(), "scoutr-session-key-owned-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = root;

    const key = await keyForAgent(piBackend, {
      source: "herdr:pi",
      agent: "pi",
      kind: "path",
      value: "/tmp/outside-session.jsonl",
    });

    assert.equal(key, null);
  });
});
