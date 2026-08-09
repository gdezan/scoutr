import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { HerdrClient } from "../src/herdr/client.js";
import { HerdrEventFeed } from "../src/herdr/feed.js";
import { createCockpitServer, type CockpitServer } from "../src/server.js";
import { UsageService } from "../src/usage/providers.js";

// RPC routes never touch herdr or the live socket, so this suite runs anywhere
// with the fake pi subprocess standing in for `pi --mode rpc`.
const PORT = 8791;
const TOKEN = "test_token_for_rpc_0002";

describe("cockpit bridge /api/rpc", () => {
  let server: CockpitServer;
  let sessionId = "";

  before(async () => {
    process.env.PI_BIN = join(dirname(fileURLToPath(import.meta.url)), "fixtures", "fake-pi.mjs");
    const usage = new UsageService({ authPath: join("/tmp", "cockpit-rpc-auth-missing.json") });
    server = createCockpitServer({
      herdr: new HerdrClient({ socketPath: "/nonexistent" }),
      feed: new HerdrEventFeed("/nonexistent"),
      usage,
      config: { token: TOKEN, port: PORT },
    });
  });

  after(async () => {
    await server.close();
  });

  async function call(
    path: string,
    method = "GET",
    body?: unknown,
  ): Promise<{ status: number; body: Record<string, unknown> }> {
    const response = await fetch(`http://127.0.0.1:${PORT}${path}`, {
      method,
      headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: response.status, body: (await response.json()) as Record<string, unknown> };
  }

  test("create, list, and delete sessions", async () => {
    const created = await call("/api/rpc", "POST", { name: "rpc-test" });
    assert.equal(created.status, 200);
    const session = created.body.session as { id: string; status: string; name: string };
    assert.equal(session.status, "running");
    assert.equal(session.name, "rpc-test");
    sessionId = session.id;

    const listed = await call("/api/rpc");
    assert.equal(listed.status, 200);
    const sessions = (listed.body.sessions as { id: string }[]).map((s) => s.id);
    assert.ok(sessions.includes(sessionId));

    const detail = await call(`/api/rpc/${sessionId}`);
    assert.equal((detail.body.session as { id: string }).id, sessionId);

    const gone = await call(`/api/rpc/${sessionId}`, "DELETE");
    assert.equal(gone.status, 200);
    const afterDelete = await call(`/api/rpc/${sessionId}`);
    assert.equal(afterDelete.status, 404);
  });

  test("prompt then poll entries with a cursor", async () => {
    const created = await call("/api/rpc", "POST");
    sessionId = (created.body.session as { id: string }).id;
    try {
      let entries = (await call(`/api/rpc/${sessionId}/entries`)).body.entries as { role: string }[];
      assert.equal(entries.length, 0);

      const prompt = await call(`/api/rpc/${sessionId}/prompt`, "POST", { message: "hello" });
      assert.equal(prompt.status, 200);

      entries = (await call(`/api/rpc/${sessionId}/entries`)).body.entries as {
        role: string; content: { text?: string }[];
      }[];
      assert.ok(entries.length >= 2);
      assert.equal(entries[0]!.role, "user");

      const last = (await call(`/api/rpc/${sessionId}/entries`)).body.lastEntryId as string;
      const delta = (await call(`/api/rpc/${sessionId}/entries?since=${encodeURIComponent(last)}`)).body
        .entries as unknown[];
      assert.equal(delta.length, 0);
    } finally {
      await call(`/api/rpc/${sessionId}`, "DELETE");
    }
  });

  test("programmatic answer to a pending extension_ui_request", async () => {
    const created = await call("/api/rpc", "POST");
    sessionId = (created.body.session as { id: string }).id;
    try {
      await call(`/api/rpc/${sessionId}/prompt`, "POST", { message: "ASK" });

      const detail = await call(`/api/rpc/${sessionId}`);
      const ui = (detail.body.session as { uiRequests: { id: string; method: string; options: string[] }[] })
        .uiRequests;
      assert.equal(ui.length, 1);
      assert.equal(ui[0]!.method, "select");

      const respond = await call(`/api/rpc/${sessionId}/respond`, "POST", {
        uiId: ui[0]!.id,
        value: "Approve",
      });
      assert.equal(respond.status, 200);

      await new Promise((done) => setTimeout(done, 150));
      const entries = (await call(`/api/rpc/${sessionId}/entries`)).body.entries as {
        role: string; content: { text?: string }[];
      }[];
      const last = entries.at(-1)!;
      assert.equal(last.role, "assistant");
      assert.match(last.content[0]!.text ?? "", /Approved: Approve/);
    } finally {
      await call(`/api/rpc/${sessionId}`, "DELETE");
    }
  });

  test("auth and unknown-session handling", async () => {
    const unauthorized = await fetch(`http://127.0.0.1:${PORT}/api/rpc`);
    assert.equal(unauthorized.status, 401);

    const missing = await call("/api/rpc/nope/entries");
    assert.equal(missing.status, 404);

    const noMessage = await call("/api/rpc/nope/prompt", "POST", { message: "x" });
    assert.equal(noMessage.status, 404);
  });
});
