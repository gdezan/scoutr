import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { BridgeError } from "../src/errors.js";
import { JSON_BODY_MAX_BYTES, RouteTable, dispatchRoute } from "../src/routes/dispatcher.js";
import type { DispatchRequest, Route, RouteContext, RouteResult } from "../src/routes/types.js";

const TOKEN = "dispatcher_test_token_0001";

function deps(): RouteContext["deps"] {
  return { config: { token: TOKEN, port: 1 } } as never;
}

function request(overrides: Partial<DispatchRequest> = {}): DispatchRequest {
  return {
    method: "GET",
    pathname: "/x",
    search: new URLSearchParams(),
    authorization: `Bearer ${TOKEN}`,
    ...overrides,
  };
}

function streamOf(chunks: Buffer[]): AsyncIterable<Buffer> {
  return (async function* () {
    for (const chunk of chunks) yield chunk;
  })();
}

function route(method: "GET" | "POST", path: string, result: RouteResult | Error): Route {
  return {
    method,
    path,
    handle: async () => {
      if (result instanceof Error) throw result;
      return result;
    },
  };
}

describe("RouteTable", () => {
  test("matches literal routes by method and path", () => {
    const table = new RouteTable([route("GET", "/api/health", { status: 200, body: { ok: true } })]);
    assert.equal(table.match("GET", "/api/health")?.route.path, "/api/health");
    assert.equal(table.match("POST", "/api/health"), undefined);
    assert.equal(table.match("GET", "/api/health/"), undefined);
    assert.equal(table.match("GET", "/other"), undefined);
  });

  test("extracts :param segments from pattern routes", () => {
    const table = new RouteTable([
      route("POST", "/api/sessions/:paneId/control", { status: 200, body: { ok: true } }),
    ]);
    const matched = table.match("POST", "/api/sessions/p1/control");
    assert.equal(matched?.route.path, "/api/sessions/:paneId/control");
    assert.deepEqual(matched?.params, { paneId: "p1" });
    assert.equal(table.match("POST", "/api/sessions/p1/control/extra"), undefined);
    assert.equal(table.match("POST", "/api/sessions//control"), undefined);
  });

  test("distinguishes same-shape patterns by method", () => {
    const table = new RouteTable([
      route("GET", "/api/session-catalog/:action", { status: 200, body: { ok: "get" } }),
      route("POST", "/api/session-catalog/:action", { status: 200, body: { ok: "post" } }),
    ]);
    assert.equal((table.match("GET", "/api/session-catalog/resume")?.route as Route).path, "/api/session-catalog/:action");
    assert.equal((table.match("POST", "/api/session-catalog/delete")?.route as Route).path, "/api/session-catalog/:action");
  });

  test("rejects duplicate literal routes", () => {
    assert.throws(
      () =>
        new RouteTable([
          route("GET", "/api/health", { status: 200, body: {} }),
          route("GET", "/api/health", { status: 200, body: {} }),
        ]),
      /duplicate route/,
    );
    // Same path, different method is fine.
    new RouteTable([
      route("GET", "/api/sessions", { status: 200, body: {} }),
      route("POST", "/api/sessions", { status: 200, body: {} }),
    ]);
  });

  test("rejects same-shape patterns that shadow each other", () => {
    assert.throws(
      () =>
        new RouteTable([
          route("GET", "/api/sessions/:a/control", { status: 200, body: {} }),
          route("GET", "/api/sessions/:b/control", { status: 200, body: {} }),
        ]),
      /shadow each other/,
    );
  });

  test("rejects a pattern that can match a literal route", () => {
    assert.throws(
      () =>
        new RouteTable([
          route("GET", "/api/agents/read", { status: 200, body: {} }),
          route("GET", "/api/agents/:paneId", { status: 200, body: {} }),
        ]),
      /shadows literal/,
    );
  });

  test("does not treat different-shaped patterns as shadowing", () => {
    const table = new RouteTable([
      route("POST", "/api/session-catalog/:action", { status: 200, body: {} }),
      route("POST", "/api/sessions/:paneId/control", { status: 200, body: {} }),
      route("GET", "/api/repo", { status: 200, body: {} }),
      route("GET", "/api/repo/diff", { status: 200, body: {} }),
    ]);
    assert.equal(table.match("POST", "/api/sessions/p1/control")?.route.path, "/api/sessions/:paneId/control");
  });
});

describe("dispatchRoute", () => {
  test("returns the handler result untouched", async () => {
    const table = new RouteTable([route("GET", "/x", { status: 201, body: { ok: true } })]);
    const result = await dispatchRoute(table, request(), deps());
    assert.deepEqual(result, { status: 201, body: { ok: true } });
  });

  test("maps BridgeError to its status", async () => {
    const table = new RouteTable([route("GET", "/x", new BridgeError("boom", 404))]);
    const result = await dispatchRoute(table, request(), deps());
    assert.deepEqual(result, { status: 404, body: { ok: false, error: "boom" } });
  });

  test("maps a derived feature error to its status", async () => {
    const { SessionsError } = await import("../src/sessions.js");
    const table = new RouteTable([route("GET", "/x", new SessionsError("nope", 409))]);
    const result = await dispatchRoute(table, request(), deps());
    assert.equal(result.status, 409);
    assert.equal((result.body as { error: string }).error, "nope");
  });

  test("maps unknown errors to 502", async () => {
    const table = new RouteTable([route("GET", "/x", new Error("upstream blew up"))]);
    const result = await dispatchRoute(table, request(), deps());
    assert.deepEqual(result, { status: 502, body: { ok: false, error: "upstream blew up" } });
  });

  test("rejects unauthenticated requests before matching", async () => {
    const table = new RouteTable([route("GET", "/x", { status: 200, body: { ok: true } })]);
    const noAuth = await dispatchRoute(table, request({ authorization: undefined }), deps());
    assert.deepEqual(noAuth, { status: 401, body: { ok: false, error: "unauthorized" } });
    const wrong = await dispatchRoute(table, request({ authorization: "Bearer nope" }), deps());
    assert.equal(wrong.status, 401);
    // Unknown paths are still 401, not 404 — auth comes first.
    const unknown = await dispatchRoute(table, request({ pathname: "/nope", authorization: undefined }), deps());
    assert.equal(unknown.status, 401);
  });

  test("accepts Bearer and ?token= authentication", async () => {
    const table = new RouteTable([route("GET", "/x", { status: 200, body: { ok: true } })]);
    const viaHeader = await dispatchRoute(table, request({ authorization: `Bearer ${TOKEN}` }), deps());
    assert.equal(viaHeader.status, 200);
    const viaQuery = await dispatchRoute(table, request({ search: new URLSearchParams({ token: TOKEN }) }), deps());
    assert.equal(viaQuery.status, 200);
  });

  test("returns 404 for unmatched methods and paths", async () => {
    const table = new RouteTable([route("GET", "/x", { status: 200, body: { ok: true } })]);
    const auth = request({ authorization: `Bearer ${TOKEN}` });
    assert.equal((await dispatchRoute(table, { ...auth, method: "POST" }, deps())).status, 404);
    assert.equal((await dispatchRoute(table, { ...auth, pathname: "/y" }, deps())).status, 404);
  });

  test("parses a JSON body for POST routes", async () => {
    const seen: RouteContext[] = [];
    const table = new RouteTable([{
      method: "POST",
      path: "/api/sessions",
      handle: async (c: RouteContext) => {
        seen.push(c);
        return { status: 200, body: { ok: true } };
      },
    }]);
    const result = await dispatchRoute(
      table,
      request({
        method: "POST",
        pathname: "/api/sessions",
        authorization: `Bearer ${TOKEN}`,
        body: streamOf([Buffer.from('{"model":"pi","name":"x"}')]),
      }),
      deps(),
    );
    assert.equal(result.status, 200);
    assert.deepEqual(seen[0]?.body, { model: "pi", name: "x" });
    assert.equal(seen[0]?.rawBody, undefined);
  });

  test("treats a missing POST body as {}", async () => {
    const seen: RouteContext[] = [];
    const table = new RouteTable([{
      method: "POST",
      path: "/api/sessions",
      handle: async (c: RouteContext) => {
        seen.push(c);
        return { status: 200, body: {} };
      },
    }]);
    await dispatchRoute(table, request({ method: "POST", pathname: "/api/sessions" }), deps());
    assert.deepEqual(seen[0]?.body, {});
  });

  test("rejects invalid JSON, null, and array bodies with 400", async () => {
    const table = new RouteTable([route("POST", "/api/sessions", { status: 200, body: {} })]);
    const auth = request({ method: "POST", pathname: "/api/sessions" });
    for (const raw of ["{oops", "null", "[1,2]", '"str"', "42"]) {
      const result = await dispatchRoute(table, { ...auth, body: streamOf([Buffer.from(raw)]) }, deps());
      assert.equal(result.status, 400, raw);
      assert.match((result.body as { error: string }).error, /JSON object|valid JSON/);
    }
  });

  test("rejects bodies over the 1 MiB cap with 413", async () => {
    const table = new RouteTable([route("POST", "/api/sessions", { status: 200, body: {} })]);
    const big = Buffer.alloc(JSON_BODY_MAX_BYTES + 1, 0x61);
    const result = await dispatchRoute(
      table,
      request({ method: "POST", pathname: "/api/sessions", body: streamOf([big]) }),
      deps(),
    );
    assert.deepEqual(result, { status: 413, body: { ok: false, error: "body too large" } });
  });

  test("hands the raw stream to rawBody routes", async () => {
    const seen: RouteContext[] = [];
    const table = new RouteTable([{
      method: "POST",
      path: "/api/attachments",
      rawBody: true,
      handle: async (c: RouteContext) => {
        seen.push(c);
        const chunks: Buffer[] = [];
        for await (const chunk of c.rawBody ?? []) chunks.push(chunk);
        return { status: 201, body: { bytes: Buffer.concat(chunks).length, type: c.contentType } };
      },
    }]);
    const result = await dispatchRoute(
      table,
      request({
        method: "POST",
        pathname: "/api/attachments",
        body: streamOf([Buffer.from([0x89, 0x50])]),
        contentType: "image/png",
      }),
      deps(),
    );
    assert.deepEqual(result, { status: 201, body: { bytes: 2, type: "image/png" } });
    assert.deepEqual(seen[0]?.body, {});
  });

  test("passes params and query through to the handler", async () => {
    const seen: RouteContext[] = [];
    const table = new RouteTable([{
      method: "GET",
      path: "/api/agents/:paneId/read",
      handle: async (c: RouteContext) => {
        seen.push(c);
        return { status: 200, body: { ok: true } };
      },
    }]);
    const result = await dispatchRoute(
      table,
      request({ pathname: "/api/agents/p%201/read", search: new URLSearchParams({ lines: "20" }) }),
      deps(),
    );
    assert.equal(result.status, 200);
    assert.deepEqual(seen[0]?.params, { paneId: "p%201" });
    assert.equal(seen[0]?.query.get("lines"), "20");
  });
});
