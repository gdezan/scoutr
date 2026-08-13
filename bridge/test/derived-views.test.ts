import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { deriveAgentCards } from "../src/routes/agents.js";
import { sessionWorkspaceRoots } from "../src/routes/review.js";
import { snapshotPaths } from "../src/server.js";
import type { AgentInfo, SessionSnapshot } from "../src/herdr/types.js";

function snapshotWithAgents(agents: Partial<AgentInfo>[]): SessionSnapshot {
  return {
    version: "0.8.0",
    protocol: 19,
    focused_workspace_id: null,
    focused_tab_id: null,
    focused_pane_id: null,
    workspaces: [],
    tabs: [],
    panes: [],
    agents: agents.map((agent, index) => ({
      agent: "pi",
      agent_status: "idle",
      pane_id: `p${index + 1}`,
      workspace_id: `ws${index + 1}`,
      tab_id: `t${index + 1}`,
      terminal_id: `term${index + 1}`,
      focused: false,
      cwd: "/work",
      foreground_cwd: "/work",
      revision: 0,
      state_change_seq: 0,
      ...agent,
    })),
    layouts: [],
  };
}

describe("deriveAgentCards", () => {
  test("maps each agent to a card with status, blocked, and session path", () => {
    const snapshot = snapshotWithAgents([
      { agent_status: "blocked", cwd: "/a", terminal_title: "pi: fix bug", terminal_title_stripped: "fix bug", agent_session: { kind: "path", value: "/sessions/abc.jsonl" } },
      { agent_status: "done" },
    ]);
    const cards = deriveAgentCards(snapshot);
    assert.equal(cards.length, 2);
    assert.deepEqual(cards[0], {
      paneId: "p1",
      workspaceId: "ws1",
      tabId: "t1",
      agent: "pi",
      agentKind: "pi",
    displayName: "Pi",
      capabilities: ["abort", "retry", "compact", "fork", "rename", "close", "set_model", "set_thinking"],
      status: "blocked",
      cwd: "/a",
      title: "pi: fix bug",
      terminalTitle: "fix bug",
      blocked: true,
      sessionPath: "/sessions/abc.jsonl",
    });
    assert.equal(cards[1]?.status, "done");
    assert.equal(cards[1]?.blocked, false);
  });

  test("reports statusSinceMs from the statusSince callback", () => {
    const cards = deriveAgentCards(snapshotWithAgents([{}]), () => 1234);
    assert.equal(cards[0]?.statusSinceMs, 1234);
  });
});

describe("snapshotPaths", () => {
  test("collects agent_session paths and ignores non-path sessions", () => {
    const snapshot = snapshotWithAgents([
      { agent_session: { kind: "path", value: "/sessions/one.jsonl" } },
      { agent_session: { kind: "other", value: "x" } },
      {},
    ]);
    assert.deepEqual(snapshotPaths(snapshot), new Set(["/sessions/one.jsonl"]));
  });

  test("returns an empty set for null", () => {
    assert.deepEqual(snapshotPaths(null), new Set());
  });
});

describe("sessionWorkspaceRoots", () => {
  let repoA: string;
  let repoB: string;
  let scratch: string;

  before(() => {
    repoA = mkdtempSync(join(tmpdir(), "scoutr-roots-a-"));
    repoB = mkdtempSync(join(tmpdir(), "scoutr-roots-b-"));
    scratch = mkdtempSync(join(tmpdir(), "scoutr-roots-scratch-"));
    for (const repo of [repoA, repoB]) {
      execFileSync("git", ["init", "-q", "-b", "main", repo]);
      execFileSync("git", ["config", "user.email", "test@scoutr.dev"], { cwd: repo });
      execFileSync("git", ["config", "user.name", "Scoutr Test"], { cwd: repo });
      execFileSync("git", ["commit", "-q", "--allow-empty", "-m", "initial"], { cwd: repo });
    }
  });

  after(() => {
    for (const dir of [repoA, repoB, scratch]) rmSync(dir, { recursive: true, force: true });
  });

  test("resolves agent cwds to their git repo roots, deduped", async () => {
    const nested = join(repoA, "sub", "dir");
    execFileSync("mkdir", ["-p", nested]);
    const roots = await sessionWorkspaceRoots(
      snapshotWithAgents([
        { cwd: nested, foreground_cwd: repoA },
        { cwd: repoB, foreground_cwd: repoB },
      ]),
    );
    assert.deepEqual(roots.sort(), [repoA, repoB].sort());
  });

  test("drops non-repo cwds so a $HOME-like cwd never opens the home dir", async () => {
    const roots = await sessionWorkspaceRoots(snapshotWithAgents([{ cwd: scratch, foreground_cwd: scratch }]));
    assert.deepEqual(roots, []);
  });

  test("joins catalog cwds and handles a null snapshot", async () => {
    const fromCatalog = await sessionWorkspaceRoots(null, [repoA, scratch]);
    assert.deepEqual(fromCatalog, [repoA]);
    const empty = await sessionWorkspaceRoots(null);
    assert.deepEqual(empty, []);
  });
});
