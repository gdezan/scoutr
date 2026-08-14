import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { HerdrPort } from "../src/herdr/port.js";
import type { SessionSnapshot } from "../src/herdr/types.js";
import { RouteTable, dispatchRoute } from "../src/routes/dispatcher.js";
import { buildRoutes } from "../src/routes/index.js";
import type { DispatchRequest, RouteContext, RouteResult } from "../src/routes/types.js";
import { fakeHerdr, type FakeHerdrExtras } from "./support/fake-herdr.js";
import { pane, snapshot, tab, workspace } from "./support/snapshot.js";

const TOKEN = "terminal_hierarchy_test_token_0001";
const table = new RouteTable(buildRoutes());

type FakeHerdr = HerdrPort & FakeHerdrExtras;

/** The fake returns the same snapshot on every read; sequence pre/post for the route's two reads. */
function sequencedSnapshots(fake: FakeHerdr, pre: SessionSnapshot, post: SessionSnapshot): FakeHerdr {
  let reads = 0;
  return {
    ...fake,
    snapshot: async () => {
      fake.sent.push({ method: "snapshot", params: {} });
      return reads++ === 0 ? pre : post;
    },
  };
}

function depsFor(fake: HerdrPort): RouteContext["deps"] {
  return { herdr: fake, config: { configDir: "/tmp/scoutr-test-config", token: TOKEN } } as unknown as RouteContext["deps"];
}

function streamOf(chunks: Buffer[]): AsyncIterable<Buffer> {
  return (async function* () {
    for (const chunk of chunks) yield chunk;
  })();
}

function request(overrides: Partial<DispatchRequest> = {}): DispatchRequest {
  return {
    method: "POST",
    pathname: "/api/terminal/hierarchy",
    search: new URLSearchParams(),
    authorization: `Bearer ${TOKEN}`,
    ...overrides,
  };
}

async function postHierarchy(deps: RouteContext["deps"], body: unknown): Promise<RouteResult> {
  return dispatchRoute(
    table,
    request({ body: streamOf([Buffer.from(JSON.stringify(body))]) }),
    deps,
  );
}

function recorded(fake: FakeHerdr, method: keyof HerdrPort): Record<string, unknown>[] {
  return fake.sent.filter((call) => call.method === method).map((call) => call.params);
}

function okBody(result: RouteResult): { ok: boolean; selectedPaneId: string | null; snapshot: SessionSnapshot } {
  assert.equal(result.status, 200);
  return result.body as { ok: boolean; selectedPaneId: string | null; snapshot: SessionSnapshot };
}

describe("terminal hierarchy route", () => {
  test("registers alongside existing routes", () => {
    assert.equal(table.match("POST", "/api/terminal/hierarchy")?.route.path, "/api/terminal/hierarchy");
    assert.equal(table.match("GET", "/api/health")?.route.path, "/api/health");
    assert.equal(table.match("GET", "/api/snapshot")?.route.path, "/api/snapshot");
    assert.equal(table.match("GET", "/api/terminal/hierarchy"), undefined);
  });

  test("rejects unknown and missing operations before touching herdr", async () => {
    const fake = fakeHerdr();
    for (const body of [{ operation: "explode" }, {}, { operation: 42 }]) {
      const result = await postHierarchy(depsFor(fake), body);
      assert.equal(result.status, 400);
      assert.match((result.body as { error: string }).error, /unknown hierarchy operation/);
    }
    assert.equal(fake.sent.length, 0);
  });

  test("validates shared fields before touching herdr", async () => {
    const fake = fakeHerdr();
    const result = await postHierarchy(depsFor(fake), { operation: "create_tab", workspaceId: "", selectedPaneId: 42 });
    assert.equal(result.status, 400);
    assert.equal(fake.sent.length, 0);
  });
});

describe("create_tab", () => {
  test("sends exact herdr params with focus:false and cwd from the selected pane, with fresh pre/post snapshots", async () => {
    const pre = snapshot(
      [
        pane({ pane_id: "p-sel", foreground_cwd: "/home/user/proj", cwd: "/home/user/proj" }),
        pane({ pane_id: "p-other", cwd: "/home/user/other" }),
      ],
      [tab()],
      [workspace()],
    );
    const post = snapshot([pane({ pane_id: "p-new", tab_id: "t-new" })], [tab({ tab_id: "t-new" })], [workspace()]);
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);

    const result = await postHierarchy(depsFor(fake), { operation: "create_tab", workspaceId: "ws1", selectedPaneId: "p-sel" });
    const body = okBody(result);
    assert.equal(body.ok, true);
    assert.deepEqual(recorded(fake, "tabCreate"), [{ workspace_id: "ws1", cwd: "/home/user/proj", focus: false }]);
    assert.deepEqual(fake.sent.map((call) => call.method), ["snapshot", "tabCreate", "snapshot"]);
    // The created root pane (herdr's tab_created result) wins selection even
    // though the fresh catalog no longer contains it.
    assert.equal(body.selectedPaneId, "p1");
    assert.equal(body.snapshot, post);
  });

  test("falls back to another pane's cwd in the workspace", async () => {
    const fake = fakeHerdr(
      snapshot(
        [pane({ pane_id: "p-a" }), pane({ pane_id: "p-b", foreground_cwd: "/home/user/other" })],
        [tab()],
        [workspace()],
      ),
    );
    const result = await postHierarchy(depsFor(fake), { operation: "create_tab", workspaceId: "ws1" });
    assert.equal(result.status, 200);
    assert.deepEqual(recorded(fake, "tabCreate"), [{ workspace_id: "ws1", cwd: "/home/user/other", focus: false }]);
  });

  test("omits cwd entirely when no pane in the workspace has one", async () => {
    const fake = fakeHerdr(snapshot([pane({ pane_id: "p-a" })], [tab()], [workspace()]));
    const result = await postHierarchy(depsFor(fake), { operation: "create_tab", workspaceId: "ws1", selectedPaneId: "p-a" });
    assert.equal(result.status, 200);
    assert.deepEqual(recorded(fake, "tabCreate"), [{ workspace_id: "ws1", focus: false }]);
  });

  test("prefers the selected pane's foreground cwd over its cwd", async () => {
    const fake = fakeHerdr(
      snapshot(
        [pane({ pane_id: "p-sel", cwd: "/home/user/plain", foreground_cwd: "/home/user/foreground" })],
        [tab()],
        [workspace()],
      ),
    );
    const result = await postHierarchy(depsFor(fake), { operation: "create_tab", workspaceId: "ws1", selectedPaneId: "p-sel" });
    assert.equal(result.status, 200);
    assert.deepEqual(recorded(fake, "tabCreate"), [{ workspace_id: "ws1", cwd: "/home/user/foreground", focus: false }]);
  });

  test("returns 404 for an absent workspace and skips the mutation", async () => {
    const fake = fakeHerdr(snapshot([pane()], [tab()], [workspace()]));
    const result = await postHierarchy(depsFor(fake), { operation: "create_tab", workspaceId: "ws-ghost", selectedPaneId: "p1" });
    assert.equal(result.status, 404);
    assert.equal((result.body as { error: string }).error, "workspace not found");
    assert.deepEqual(fake.sent.map((call) => call.method), ["snapshot"]);
  });

  test("requires workspaceId", async () => {
    const fake = fakeHerdr();
    const result = await postHierarchy(depsFor(fake), { operation: "create_tab" });
    assert.equal(result.status, 400);
    assert.match((result.body as { error: string }).error, /missing workspaceId/);
    assert.equal(fake.sent.length, 0);
  });
});

describe("create_workspace", () => {
  test("resolves cwd inside home and sends exact herdr params with focus:false", async () => {
    const dir = mkdtempSync(join(homedir(), ".scoutr-hierarchy-"));
    try {
      const pre = snapshot([pane({ pane_id: "p-sel" })], [tab()], [workspace()]);
      const post = snapshot([pane({ pane_id: "p-new" })], [tab()], [workspace()]);
      const fake = sequencedSnapshots(fakeHerdr(), pre, post);

      const result = await postHierarchy(depsFor(fake), {
        operation: "create_workspace",
        cwd: dir,
        label: "New Workspace",
        selectedPaneId: "p-sel",
      });
      const body = okBody(result);
      assert.deepEqual(recorded(fake, "workspaceCreate"), [{ cwd: dir, label: "New Workspace", focus: false }]);
      assert.deepEqual(fake.sent.map((call) => call.method), ["snapshot", "workspaceCreate", "snapshot"]);
      assert.equal(body.selectedPaneId, "p1"); // herdr's workspace_created root pane wins
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  test("omits label when absent", async () => {
    const dir = mkdtempSync(join(homedir(), ".scoutr-hierarchy-"));
    try {
      const fake = fakeHerdr(snapshot([pane()], [tab()], [workspace()]));
      const result = await postHierarchy(depsFor(fake), { operation: "create_workspace", cwd: dir });
      assert.equal(result.status, 200);
      assert.deepEqual(recorded(fake, "workspaceCreate"), [{ cwd: dir, focus: false }]);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  test("rejects cwd outside the allowed root", async () => {
    const fake = fakeHerdr();
    const result = await postHierarchy(depsFor(fake), { operation: "create_workspace", cwd: "/etc" });
    assert.equal(result.status, 400);
    assert.equal((result.body as { error: string }).error, "path outside allowed root");
    assert.equal(fake.sent.length, 0);
  });

  test("rejects a missing cwd and a blank label", async () => {
    const fake = fakeHerdr();
    const missingCwd = await postHierarchy(depsFor(fake), { operation: "create_workspace" });
    assert.equal(missingCwd.status, 400);
    assert.match((missingCwd.body as { error: string }).error, /cwd must be a non-empty string/);
    const blankLabel = await postHierarchy(depsFor(fake), { operation: "create_workspace", cwd: homedir(), label: "   " });
    assert.equal(blankLabel.status, 400);
    assert.match((blankLabel.body as { error: string }).error, /label must be a non-empty string/);
    assert.equal(fake.sent.length, 0);
  });
});

describe("rename operations", () => {
  test("rename_pane sends exact params and preserves the selection", async () => {
    const pre = snapshot([pane({ pane_id: "p-sel", label: "old" })], [tab()], [workspace()]);
    const fake = fakeHerdr(pre);
    const result = await postHierarchy(depsFor(fake), { operation: "rename_pane", paneId: "p-sel", label: "My Pane", selectedPaneId: "p-sel" });
    const body = okBody(result);
    assert.deepEqual(recorded(fake, "paneRename"), [{ pane_id: "p-sel", label: "My Pane" }]);
    assert.deepEqual(fake.sent.map((call) => call.method), ["snapshot", "paneRename", "snapshot"]);
    assert.equal(body.selectedPaneId, "p-sel");
  });

  test("rename_tab sends exact params", async () => {
    const fake = fakeHerdr(snapshot([pane()], [tab({ label: "old" })], [workspace()]));
    const result = await postHierarchy(depsFor(fake), { operation: "rename_tab", tabId: "t1", label: "My Tab" });
    assert.equal(result.status, 200);
    assert.deepEqual(recorded(fake, "tabRename"), [{ tab_id: "t1", label: "My Tab" }]);
  });

  test("rename_workspace sends exact params", async () => {
    const fake = fakeHerdr(snapshot([pane()], [tab()], [workspace({ label: "old" })]));
    const result = await postHierarchy(depsFor(fake), { operation: "rename_workspace", workspaceId: "ws1", label: "My Workspace" });
    assert.equal(result.status, 200);
    assert.deepEqual(recorded(fake, "workspaceRename"), [{ workspace_id: "ws1", label: "My Workspace" }]);
  });

  test("returns 404 when the target is absent from the pre snapshot", async () => {
    for (const [body, error] of [
      [{ operation: "rename_pane", paneId: "p-ghost", label: "x" }, "pane not found"],
      [{ operation: "rename_tab", tabId: "t-ghost", label: "x" }, "tab not found"],
      [{ operation: "rename_workspace", workspaceId: "ws-ghost", label: "x" }, "workspace not found"],
    ] as const) {
      const fake = fakeHerdr(snapshot([pane()], [tab()], [workspace()]));
      const result = await postHierarchy(depsFor(fake), body);
      assert.equal(result.status, 404);
      assert.equal((result.body as { error: string }).error, error);
      assert.deepEqual(fake.sent.map((call) => call.method), ["snapshot"]);
    }
  });

  test("validates labels before touching herdr", async () => {
    const fake = fakeHerdr(snapshot([pane()], [tab()], [workspace()]));
    for (const body of [
      { operation: "rename_pane", paneId: "p1" },
      { operation: "rename_tab", tabId: "t1", label: "" },
      { operation: "rename_workspace", workspaceId: "ws1", label: "x".repeat(201) },
    ]) {
      const result = await postHierarchy(depsFor(fake), body);
      assert.equal(result.status, 400);
    }
    assert.equal(fake.sent.length, 0);
  });
});

describe("close_pane", () => {
  test("closing the active pane selects the next pane in the old tab", async () => {
    const pre = snapshot(
      [pane({ pane_id: "p1" }), pane({ pane_id: "p2" }), pane({ pane_id: "p3" }), pane({ pane_id: "p4" })],
      [tab()],
      [workspace()],
    );
    const post = snapshot([pane({ pane_id: "p1" }), pane({ pane_id: "p3" }), pane({ pane_id: "p4" })], [tab()], [workspace()]);
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);

    const result = await postHierarchy(depsFor(fake), { operation: "close_pane", paneId: "p2", selectedPaneId: "p2" });
    const body = okBody(result);
    assert.deepEqual(recorded(fake, "paneClose"), [{ pane_id: "p2" }]);
    assert.deepEqual(fake.sent.map((call) => call.method), ["snapshot", "paneClose", "snapshot"]);
    assert.equal(body.selectedPaneId, "p3");
  });

  test("closing the last pane in a tab selects the previous pane", async () => {
    const pre = snapshot([pane({ pane_id: "p1" }), pane({ pane_id: "p2" }), pane({ pane_id: "p3" })], [tab()], [workspace()]);
    const post = snapshot([pane({ pane_id: "p1" }), pane({ pane_id: "p2" })], [tab()], [workspace()]);
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);
    const result = await postHierarchy(depsFor(fake), { operation: "close_pane", paneId: "p3", selectedPaneId: "p3" });
    assert.equal(okBody(result).selectedPaneId, "p2");
  });

  test("closing the only pane in a tab falls back to the old workspace", async () => {
    const pre = snapshot(
      [pane({ pane_id: "p1", tab_id: "t1" }), pane({ pane_id: "p2", tab_id: "t2" })],
      [tab(), tab({ tab_id: "t2" })],
      [workspace()],
    );
    const post = snapshot([pane({ pane_id: "p2", tab_id: "t2" })], [tab({ tab_id: "t2" })], [workspace()]);
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);
    const result = await postHierarchy(depsFor(fake), { operation: "close_pane", paneId: "p1", selectedPaneId: "p1" });
    assert.equal(okBody(result).selectedPaneId, "p2");
  });

  test("closing the only pane in a workspace falls back globally", async () => {
    const pre = snapshot(
      [pane({ pane_id: "p1", workspace_id: "ws1", tab_id: "t1" }), pane({ pane_id: "p9", workspace_id: "ws2", tab_id: "t3" })],
      [tab(), tab({ tab_id: "t3", workspace_id: "ws2" })],
      [workspace(), workspace({ workspace_id: "ws2", number: 2, active_tab_id: "t3" })],
    );
    const post = snapshot([pane({ pane_id: "p9", workspace_id: "ws2", tab_id: "t3" })], [tab({ tab_id: "t3", workspace_id: "ws2" })], [
      workspace({ workspace_id: "ws2", number: 2, active_tab_id: "t3" }),
    ]);
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);
    const result = await postHierarchy(depsFor(fake), { operation: "close_pane", paneId: "p1", selectedPaneId: "p1" });
    assert.equal(okBody(result).selectedPaneId, "p9");
  });

  test("an unrelated close preserves the selection while it survives", async () => {
    const pre = snapshot([pane({ pane_id: "p1" }), pane({ pane_id: "p2" })], [tab()], [workspace()]);
    const post = snapshot([pane({ pane_id: "p2" })], [tab()], [workspace()]);
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);
    const result = await postHierarchy(depsFor(fake), { operation: "close_pane", paneId: "p1", selectedPaneId: "p2" });
    assert.equal(okBody(result).selectedPaneId, "p2");
  });

  test("an unrelated close that removes the selection yields the empty selector", async () => {
    const pre = snapshot([pane({ pane_id: "p1" }), pane({ pane_id: "p2" })], [tab()], [workspace()]);
    const post = snapshot([pane({ pane_id: "p1" })], [tab()], [workspace()]);
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);
    const result = await postHierarchy(depsFor(fake), { operation: "close_pane", paneId: "p1", selectedPaneId: "p2" });
    assert.equal(okBody(result).selectedPaneId, null);
  });

  test("a stale selection falls back to the first pane of the fresh catalog", async () => {
    const pre = snapshot([pane({ pane_id: "p1" }), pane({ pane_id: "p2" })], [tab()], [workspace()]);
    const post = snapshot([pane({ pane_id: "p2" })], [tab()], [workspace()]);
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);
    const result = await postHierarchy(depsFor(fake), { operation: "close_pane", paneId: "p1", selectedPaneId: "p-stale" });
    assert.equal(okBody(result).selectedPaneId, "p2");
  });

  test("returns 404 for an absent pane and skips the mutation", async () => {
    const fake = fakeHerdr(snapshot([pane()], [tab()], [workspace()]));
    const result = await postHierarchy(depsFor(fake), { operation: "close_pane", paneId: "p-ghost" });
    assert.equal(result.status, 404);
    assert.equal((result.body as { error: string }).error, "pane not found");
    assert.deepEqual(fake.sent.map((call) => call.method), ["snapshot"]);
  });

  test("maps herdr failures to 502", async () => {
    const fake = fakeHerdr(snapshot([pane()], [tab()], [workspace()]));
    fake.failNext("paneClose", new Error("herdr refused"));
    const result = await postHierarchy(depsFor(fake), { operation: "close_pane", paneId: "p1" });
    assert.equal(result.status, 502);
    assert.equal((result.body as { error: string }).error, "herdr refused");
  });
});

describe("close_tab", () => {
  test("sends exact params and preserves an unrelated selection", async () => {
    const pre = snapshot(
      [pane({ pane_id: "p1", tab_id: "t1" }), pane({ pane_id: "p2", tab_id: "t1" }), pane({ pane_id: "p3", tab_id: "t2" })],
      [tab(), tab({ tab_id: "t2" })],
      [workspace()],
    );
    const post = snapshot(
      [pane({ pane_id: "p1", tab_id: "t1" }), pane({ pane_id: "p2", tab_id: "t1" })],
      [tab()],
      [workspace()],
    );
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);
    const result = await postHierarchy(depsFor(fake), { operation: "close_tab", tabId: "t2", expectedPaneCount: 1, selectedPaneId: "p1" });
    const body = okBody(result);
    assert.deepEqual(recorded(fake, "tabClose"), [{ tab_id: "t2" }]);
    assert.deepEqual(fake.sent.map((call) => call.method), ["snapshot", "tabClose", "snapshot"]);
    assert.equal(body.selectedPaneId, "p1");
  });

  test("closing the active tab selects the next tab's first pane in the old workspace", async () => {
    const pre = snapshot(
      [pane({ pane_id: "p1", tab_id: "t1" }), pane({ pane_id: "p2", tab_id: "t1" }), pane({ pane_id: "p3", tab_id: "t2" }), pane({ pane_id: "p4", tab_id: "t2" })],
      [tab(), tab({ tab_id: "t2" })],
      [workspace()],
    );
    const post = snapshot(
      [pane({ pane_id: "p1", tab_id: "t1" }), pane({ pane_id: "p2", tab_id: "t1" })],
      [tab()],
      [workspace()],
    );
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);
    const result = await postHierarchy(depsFor(fake), { operation: "close_tab", tabId: "t2", expectedPaneCount: 2, selectedPaneId: "p3" });
    assert.equal(okBody(result).selectedPaneId, "p1");
  });

  test("closing the active tab falls back to the next surviving tab globally", async () => {
    const pre = snapshot(
      [pane({ pane_id: "p3", tab_id: "t2" }), pane({ pane_id: "p4", tab_id: "t2" }), pane({ pane_id: "p9", workspace_id: "ws2", tab_id: "t3" })],
      [tab({ tab_id: "t2" }), tab({ tab_id: "t3", workspace_id: "ws2" })],
      [workspace(), workspace({ workspace_id: "ws2", number: 2, active_tab_id: "t3" })],
    );
    const post = snapshot(
      [pane({ pane_id: "p9", workspace_id: "ws2", tab_id: "t3" })],
      [tab({ tab_id: "t3", workspace_id: "ws2" })],
      [workspace({ workspace_id: "ws2", number: 2, active_tab_id: "t3" })],
    );
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);
    const result = await postHierarchy(depsFor(fake), { operation: "close_tab", tabId: "t2", expectedPaneCount: 2, selectedPaneId: "p3" });
    assert.equal(okBody(result).selectedPaneId, "p9");
  });

  test("returns 409 with current count when the pane count changed", async () => {
    const fake = fakeHerdr(
      snapshot(
        [pane({ pane_id: "p1", tab_id: "t1" }), pane({ pane_id: "p2", tab_id: "t1" })],
        [tab()],
        [workspace()],
      ),
    );
    const result = await postHierarchy(depsFor(fake), { operation: "close_tab", tabId: "t1", expectedPaneCount: 1, selectedPaneId: "p1" });
    assert.equal(result.status, 409);
    assert.deepEqual(result.body, {
      ok: false,
      error: "tab pane count changed",
      id: "t1",
      name: "Tab 1",
      count: 2,
      expectedPaneCount: 1,
    });
    assert.equal(recorded(fake, "tabClose").length, 0);
  });

  test("returns 404 for an absent tab", async () => {
    const fake = fakeHerdr(snapshot([pane()], [tab()], [workspace()]));
    const result = await postHierarchy(depsFor(fake), { operation: "close_tab", tabId: "t-ghost", expectedPaneCount: 1 });
    assert.equal(result.status, 404);
    assert.equal((result.body as { error: string }).error, "tab not found");
  });

  test("validates expectedPaneCount", async () => {
    const fake = fakeHerdr(snapshot([pane()], [tab()], [workspace()]));
    for (const body of [
      { operation: "close_tab", tabId: "t1" },
      { operation: "close_tab", tabId: "t1", expectedPaneCount: "1" },
      { operation: "close_tab", tabId: "t1", expectedPaneCount: -1 },
      { operation: "close_tab", tabId: "t1", expectedPaneCount: 1.5 },
    ]) {
      const result = await postHierarchy(depsFor(fake), body);
      assert.equal(result.status, 400);
      assert.match((result.body as { error: string }).error, /expectedPaneCount/);
    }
    assert.equal(fake.sent.length, 0);
  });
});

describe("close_workspace", () => {
  test("sends exact params and selects the next surviving workspace's first pane", async () => {
    const pre = snapshot(
      [pane({ pane_id: "p1", tab_id: "t1" }), pane({ pane_id: "p9", workspace_id: "ws2", tab_id: "t3" })],
      [tab(), tab({ tab_id: "t3", workspace_id: "ws2" })],
      [workspace(), workspace({ workspace_id: "ws2", number: 2, active_tab_id: "t3" })],
    );
    const post = snapshot(
      [pane({ pane_id: "p9", workspace_id: "ws2", tab_id: "t3" })],
      [tab({ tab_id: "t3", workspace_id: "ws2" })],
      [workspace({ workspace_id: "ws2", number: 2, active_tab_id: "t3" })],
    );
    const fake = sequencedSnapshots(fakeHerdr(), pre, post);
    const result = await postHierarchy(depsFor(fake), { operation: "close_workspace", workspaceId: "ws1", expectedPaneCount: 1, selectedPaneId: "p1" });
    const body = okBody(result);
    assert.deepEqual(recorded(fake, "workspaceClose"), [{ workspace_id: "ws1" }]);
    assert.deepEqual(fake.sent.map((call) => call.method), ["snapshot", "workspaceClose", "snapshot"]);
    assert.equal(body.selectedPaneId, "p9");
  });

  test("returns 409 with current count when the pane count changed", async () => {
    const fake = fakeHerdr(
      snapshot(
        [pane({ pane_id: "p1" }), pane({ pane_id: "p2" })],
        [tab()],
        [workspace({ label: "My Workspace" })],
      ),
    );
    const result = await postHierarchy(depsFor(fake), { operation: "close_workspace", workspaceId: "ws1", expectedPaneCount: 1, selectedPaneId: "p1" });
    assert.equal(result.status, 409);
    assert.deepEqual(result.body, {
      ok: false,
      error: "workspace pane count changed",
      id: "ws1",
      name: "My Workspace",
      count: 2,
      expectedPaneCount: 1,
    });
    assert.equal(recorded(fake, "workspaceClose").length, 0);
  });

  test("returns 404 for an absent workspace", async () => {
    const fake = fakeHerdr(snapshot([pane()], [tab()], [workspace()]));
    const result = await postHierarchy(depsFor(fake), { operation: "close_workspace", workspaceId: "ws-ghost", expectedPaneCount: 1 });
    assert.equal(result.status, 404);
    assert.equal((result.body as { error: string }).error, "workspace not found");
  });
});
