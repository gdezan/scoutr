import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { nestLiveSubagents, nestLiveSubagentsFromStore } from "../src/pi-subagents/nest-live-subagents.js";
import { indexLiveRuns } from "../src/pi-subagents/run-store.js";
import { piWorkflowSessionIdentity } from "../src/pi-subagents/session-identity.js";
import type { IndexedLiveRun } from "../src/pi-subagents/run-store.js";
import type { SessionDescriptor, SessionKey } from "../src/session-model.js";

const PARENT_PATH = "/sessions/parent.jsonl";
const PARENT_IDENTITY = piWorkflowSessionIdentity(PARENT_PATH);

function live(paneId: string, status = "working") {
  return {
    paneId,
    workspaceId: "ws",
    tabId: "t",
    status,
    statusSinceMs: null,
  };
}

function descriptor(overrides: {
  paneId: string;
  title?: string;
  key?: SessionKey | null;
  agentKind?: string;
  status?: string;
}): SessionDescriptor {
  return {
    key: overrides.key === undefined ? { agentKind: "pi", path: PARENT_PATH } : overrides.key,
    agentKind: overrides.agentKind ?? "pi",
    displayName: "Pi",
    title: overrides.title ?? "parent",
    cwd: "/work",
    model: null,
    thinkingLevel: null,
    capabilities: [],
    updatedAtMs: null,
    transcriptMtimeMs: null,
    transcriptSize: null,
    latestActivity: "parent activity",
    attention: null,
    doneSummary: null,
    liveSummary: null,
    live: live(overrides.paneId, overrides.status),
  };
}

function run(overrides: Partial<IndexedLiveRun> & Pick<IndexedLiveRun, "runId" | "paneId">): IndexedLiveRun {
  return {
    sessionId: null,
    agent: "reviewer",
    label: null,
    status: "running",
    createdAt: "2026-01-01T00:00:00.000Z",
    task: "review",
    taskPreview: "review",
    ...overrides,
  };
}

async function withRunsDir(
  files: Array<{ runId: string; body: string }>,
  body: (runsDir: string) => Promise<void>,
): Promise<void> {
  const root = await mkdtemp(join(tmpdir(), "pi-subagents-"));
  const runsDir = join(root, "runs");
  try {
    for (const file of files) {
      const dir = join(runsDir, file.runId);
      await mkdir(dir, { recursive: true });
      await writeFile(join(dir, "run.json"), file.body);
    }
    await body(runsDir);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

describe("nestLiveSubagents", () => {
  test("nests a matching child under its parent and drops the child from the top-level list", () => {
    const parent = descriptor({ paneId: "parent-pane", title: "parent session" });
    const child = descriptor({
      paneId: "child-pane",
      title: "cloned child",
      key: null,
      status: "blocked",
    });
    const nested = nestLiveSubagents(
      [parent, child],
      new Map([
        ["child-pane", run({
          runId: "run-child",
          paneId: "child-pane",
          sessionId: PARENT_IDENTITY,
          agent: "reviewer",
          label: "Review",
        })],
      ]),
    );
    assert.equal(nested.length, 1);
    assert.equal(nested[0]?.live?.paneId, "parent-pane");
    assert.equal(nested[0]?.title, "parent session");
    assert.deepEqual(nested[0]?.subagents, [
      { runId: "run-child", paneId: "child-pane", role: "reviewer", label: "Review", status: "blocked" },
    ]);
    assert.equal(nested[0]?.latestActivity, "parent activity");
  });

  test("keeps an unmatched sessionId as an orphan card", () => {
    const parent = descriptor({ paneId: "parent-pane" });
    const child = descriptor({ paneId: "orphan-pane", title: "cloned", key: null });
    const nested = nestLiveSubagents(
      [parent, child],
      new Map([
        ["orphan-pane", run({
          runId: "run-orphan",
          paneId: "orphan-pane",
          sessionId: "does-not-match",
          agent: "scout",
          label: "Scout",
        })],
      ]),
    );
    assert.equal(nested.length, 2);
    assert.equal(nested[0]?.subagents, undefined);
    assert.deepEqual(nested[1]?.subagent, {
      runId: "run-orphan",
      role: "scout",
      label: "Scout",
      orphan: true,
    });
    assert.equal(nested[1]?.title, "Scout");
    assert.equal(nested[1]?.live?.paneId, "orphan-pane");
  });

  test("nests two children on one parent", () => {
    const parent = descriptor({ paneId: "parent-pane" });
    const a = descriptor({ paneId: "a", title: "a", key: null, status: "working" });
    const b = descriptor({ paneId: "b", title: "b", key: null, status: "idle" });
    const nested = nestLiveSubagents(
      [parent, a, b],
      new Map([
        ["a", run({ runId: "run-a", paneId: "a", sessionId: PARENT_IDENTITY, agent: "reviewer" })],
        ["b", run({ runId: "run-b", paneId: "b", sessionId: PARENT_IDENTITY, agent: "worker", label: "Impl" })],
      ]),
    );
    assert.equal(nested.length, 1);
    assert.deepEqual(nested[0]?.subagents?.map((child) => child.runId), ["run-a", "run-b"]);
  });

  test("uses label or agent for an orphan title", () => {
    const unlabeled = descriptor({ paneId: "p1", title: "cloned", key: null });
    const nested = nestLiveSubagents(
      [unlabeled],
      new Map([["p1", run({ runId: "r1", paneId: "p1", agent: "researcher", label: null })]]),
    );
    assert.equal(nested[0]?.title, "researcher");
  });
});

describe("indexLiveRuns", () => {
  test("skips a corrupt run.json and still indexes the rest", async () => {
    await withRunsDir(
      [
        {
          runId: "good-run",
          body: JSON.stringify({
            runId: "good-run",
            paneId: "p1",
            agent: "reviewer",
            sessionId: PARENT_IDENTITY,
            status: "running",
          }),
        },
        { runId: "bad-run", body: "{not json" },
      ],
      async (runsDir) => {
        const indexed = await indexLiveRuns(new Set(["p1", "p2"]), { runsDir });
        assert.equal(indexed.size, 1);
        assert.equal(indexed.get("p1")?.runId, "good-run");
      },
    );
  });

  test("prefers queued or running when two run.json files share a paneId", async () => {
    await withRunsDir(
      [
        {
          runId: "old-run",
          body: JSON.stringify({
            runId: "old-run",
            paneId: "p1",
            agent: "reviewer",
            status: "completed",
            createdAt: "2026-01-02T00:00:00.000Z",
          }),
        },
        {
          runId: "live-run",
          body: JSON.stringify({
            runId: "live-run",
            paneId: "p1",
            agent: "reviewer",
            status: "running",
            createdAt: "2026-01-01T00:00:00.000Z",
          }),
        },
      ],
      async (runsDir) => {
        const indexed = await indexLiveRuns(new Set(["p1"]), { runsDir });
        assert.equal(indexed.get("p1")?.runId, "live-run");
      },
    );
  });
});

describe("nestLiveSubagentsFromStore", () => {
  test("leaves descriptors unchanged when the run store is missing", async () => {
    const parent = descriptor({ paneId: "parent-pane" });
    const child = descriptor({ paneId: "child-pane", key: null });
    const result = await nestLiveSubagentsFromStore([parent, child], {
      runsDir: join(tmpdir(), "pi-subagents-missing", "runs"),
    });
    assert.deepEqual(result, [parent, child]);
  });

  test("nests from on-disk run.json files", async () => {
    const parent = descriptor({ paneId: "parent-pane" });
    const child = descriptor({ paneId: "child-pane", key: null, status: "working" });
    await withRunsDir(
      [
        {
          runId: "run-child",
          body: JSON.stringify({
            runId: "run-child",
            paneId: "child-pane",
            agent: "reviewer",
            label: "Review",
            sessionId: PARENT_IDENTITY,
            status: "running",
          }),
        },
      ],
      async (runsDir) => {
        const result = await nestLiveSubagentsFromStore([parent, child], { runsDir });
        assert.equal(result.length, 1);
        assert.equal(result[0]?.subagents?.[0]?.runId, "run-child");
      },
    );
  });
});
