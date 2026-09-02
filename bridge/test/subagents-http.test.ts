import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { RouteTable, dispatchRoute } from "../src/routes/dispatcher.js";
import { buildRoutes } from "../src/routes/index.js";
import type { DispatchRequest, RouteContext, RouteResult } from "../src/routes/types.js";

const TOKEN = "subagents_http_test_token_0001";
const table = new RouteTable(buildRoutes());

function deps(): RouteContext["deps"] {
  // SAFETY: the progress route only reads the PI-workflow run store.
  return { config: { configDir: "/tmp/scoutr-test-config", hostId: "host_test", token: TOKEN } } as RouteContext["deps"];
}

function request(pathname: string): DispatchRequest {
  return {
    method: "GET",
    pathname,
    search: new URLSearchParams(),
    authorization: `Bearer ${TOKEN}`,
  };
}

async function withHome(body: (home: string) => Promise<void>): Promise<void> {
  const home = await mkdtemp(join(tmpdir(), "pi-subagents-home-"));
  const previous = process.env.PI_SUBAGENTS_HOME;
  process.env.PI_SUBAGENTS_HOME = home;
  try {
    await body(home);
  } finally {
    if (previous === undefined) delete process.env.PI_SUBAGENTS_HOME;
    else process.env.PI_SUBAGENTS_HOME = previous;
    await rm(home, { recursive: true, force: true });
  }
}

describe("GET /api/subagents/:runId", () => {
  test("returns progress for a fixture run", async () => {
    await withHome(async (home) => {
      const dir = join(home, "runs", "run-abc");
      await mkdir(dir, { recursive: true });
      await writeFile(join(dir, "run.json"), JSON.stringify({
        runId: "run-abc",
        paneId: "p1",
        agent: "reviewer",
        label: "Review",
        task: "review the nest change",
        taskPreview: "review the nest",
        status: "running",
      }));
      await writeFile(join(dir, "progress.json"), JSON.stringify({
        progress: { lastMessage: "reading files", status: "running" },
      }));
      await writeFile(join(dir, "result.json"), JSON.stringify({
        output: "ok",
        truncated: false,
      }));
      const result: RouteResult = await dispatchRoute(table, request("/api/subagents/run-abc"), deps());
      assert.equal(result.status, 200);
      assert.deepEqual(result.body, {
        ok: true,
        runId: "run-abc",
        role: "reviewer",
        label: "Review",
        task: "review the nest change",
        taskPreview: "review the nest",
        status: "running",
        paneId: "p1",
        lastMessage: "reading files",
        error: null,
        output: "ok",
        truncated: false,
      });
    });
  });

  test("returns 404 when the run is missing", async () => {
    await withHome(async () => {
      const result = await dispatchRoute(table, request("/api/subagents/missing-run"), deps());
      assert.equal(result.status, 404);
      assert.deepEqual(result.body, { ok: false, error: "run not found" });
    });
  });

  test("returns 400 for a path-shaped run id", async () => {
    await withHome(async () => {
      const result = await dispatchRoute(table, request("/api/subagents/%2e%2e%2fetc"), deps());
      assert.equal(result.status, 400);
      assert.deepEqual(result.body, { ok: false, error: "invalid run id" });
    });
  });
});
