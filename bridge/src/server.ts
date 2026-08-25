import { randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { createServer, type ServerResponse } from "node:http";
import type { Duplex } from "node:stream";
import { WebSocketServer, WebSocket } from "ws";
import type { FeedMessage } from "./herdr/feed.js";
import { StatusTracker } from "./status.js";
import { BoardDetailCache } from "./board-detail.js";
import { BoardRepoSummaryCache } from "./board-repo-summary.js";

import { handleLegacyWsCommand, type CommandMessage } from "./commands.js";
import { RouteTable, dispatchRoute, isAuthorized } from "./routes/dispatcher.js";
import { buildRoutes } from "./routes/index.js";
import type { RouteDeps, RouteFile, ServerDeps } from "./routes/types.js";
import { BridgeMetrics } from "./metrics.js";
import { type SessionSnapshot } from "./herdr/types.js";
import { TerminalSessionBroker } from "./terminal/broker.js";
import { attachTerminalSocket } from "./terminal/websocket.js";
import * as v from "valibot";
import type { JsonValue } from "./routes/types.js";
/**
 * Scoutr bridge HTTP + WebSocket API.
 *
 * Design (grounded in the goal + research):
 *   - The bridge owns the herdr socket (never exposed raw).
 *   - Listens on 127.0.0.1:PORT in plain HTTP; the configured exposure
 *     terminates TLS and fronts it. All requests carry the bearer token.
 *   - GET endpoints are read-only (health, snapshot, transcript, usage).
 *   - The /ws endpoint streams feed events. One-shot session commands are
 *     HTTP routes (`commands.http.v1`); /ws still answers the legacy command
 *     frames for installed APKs built before that feature existed.
 *   - Routes live in bridge/src/routes/, one module per feature; this file
 *     wires each request into the route dispatcher (auth, body parsing,
 *     404, and error mapping all live there) and serializes the result.
 *
 * Deliberate steering (agent.prompt / send_input) is only possible from an
 * authenticated client, i.e. the user acting through the app.
 */

export interface ScoutrServer {
  url: string;
  metrics: BridgeMetrics;
  close: () => Promise<void>;
}

export interface CreateServerOptions {
  /** Start listening immediately (default true). */
  listen?: boolean;
}

function sendJson(response: ServerResponse, status: number, body: JsonValue): void {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(payload),
  });
  response.end(payload);
}

/**
 * Streams a route's file straight from disk (the update APK is far too large
 * to serialize through JSON). Headers go out before the first byte is read, so
 * a mid-stream read error can only destroy the socket — the client sees a
 * truncated body, which its content-length check catches.
 */
function sendFile(response: ServerResponse, file: RouteFile): Promise<void> {
  return new Promise((resolve, reject) => {
    response.writeHead(200, {
      "content-type": file.contentType,
      "content-length": file.size,
      "content-disposition": `attachment; filename="${file.filename}"`,
      "cache-control": "no-store",
    });
    const stream = createReadStream(file.path);
    stream.on("error", (error) => {
      response.destroy();
      reject(error);
    });
    response.on("close", () => stream.destroy());
    stream.pipe(response);
    response.on("finish", () => resolve());
  });
}

export function createScoutrServer(deps: ServerDeps, options: CreateServerOptions = {}): ScoutrServer {
  const { feed, config, publisher } = deps;
  const metrics = deps.metrics ?? new BridgeMetrics();
  const token = config.token;
  const listen = options.listen ?? true;
  // App-owned pane sessions are created via herdr directly (see the create
  // flow in layer 3); no pi --mode rpc processes live here anymore.

  // Track status entry times so cards can show "time in state".
  const tracker = new StatusTracker();
  // Bounded per-agent model/latest-activity detail, memoized by file mtime.
  const boardDetail = new BoardDetailCache();
  // TTL-bounded repo summaries for Done cards; one git pass per repo per window.
  const boardRepoSummary = new BoardRepoSummaryCache();
  // One terminal stream per connection, owned here (Slice 3).
  const terminalBroker = new TerminalSessionBroker({
    launcher: deps.terminal,
    feed,
    graceMs: deps.terminalOptions?.graceMs,
    log: (message) => console.error(`terminal: ${message}`),
  });
  const routeDeps: RouteDeps = { ...deps, metrics, tracker, boardDetail, boardRepoSummary, terminalBroker };
  // herdr streams some event kinds with underscores and some with dots;
  // both spellings must be matched, so membership lives in explicit sets.
  const STATUS_KINDS = new Set(["pane_agent_status_changed", "pane.agent_status_changed"]);
  const CLOSE_KINDS = new Set(["pane_closed", "pane.closed", "pane_exited", "pane.exited"]);
  feed.onMessage((message) => {
    if (!("kind" in message)) return;
    if (STATUS_KINDS.has(message.kind)) {
      const data = v.safeParse(
        v.looseObject({ pane_id: v.optional(v.string()), agent_status: v.optional(v.string()) }),
        message.data,
      );
      const paneId = data.success && data.output.pane_id ? data.output.pane_id : "";
      const status = data.success && data.output.agent_status ? data.output.agent_status : "";
      if (paneId && status) tracker.note(paneId, status);
    } else if (CLOSE_KINDS.has(message.kind)) {
      // A closed pane leaves a stale "closed" status entry behind; prune all
      // entries whose pane is no longer in the live snapshot.
      const pruneFromSnapshot = () => {
        const livePanes = new Set((feed.snapshot?.panes ?? []).map((pane) => pane.pane_id));
        tracker.prune(livePanes);
        boardDetail.prune(snapshotPaths(feed.snapshot));
        void publisher?.prune(livePanes);
      };
      pruneFromSnapshot();
      // The cached snapshot still contains the pane until the feed's rebuild
      // lands, so the pass above can be a no-op; re-prune once it catches up
      // or notifications would outlive their pane.
      void feed.refreshSnapshot(true).then(pruneFromSnapshot).catch(() => {});
    }
  });

  // Push publisher consumes feed events independently of any WS client.
  if (publisher) {
    feed.onMessage((message) => {
      if ("kind" in message) {
        void publisher.handleEvent(message).catch(() => {});
      }
    });
  }

  const routeTable = new RouteTable(buildRoutes());

  const server = createServer(async (request, response) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    const requestMetric = metrics.beginRequest(request.method ?? "GET", url.pathname);
    try {
      const result = await dispatchRoute(
        routeTable,
        {
          method: request.method ?? "GET",
          pathname: url.pathname,
          search: url.searchParams,
          authorization: request.headers.authorization,
          body: request,
          contentType: request.headers["content-type"],
        },
        routeDeps,
      );
      if (result.file) {
        await sendFile(response, result.file);
        requestMetric.complete(result.status, result.file.size);
      } else {
        const payload = JSON.stringify(result.body);
        sendJson(response, result.status, result.body);
        requestMetric.complete(result.status, Buffer.byteLength(payload));
      }
    } catch (error) {
      // A route must never crash the process: log, then answer 500 unless the
      // client already went away.
      console.error(`route error: ${error instanceof Error ? error.message : String(error)}`);
      const errorBody = { ok: false, error: "internal server error" };
      const errorPayload = JSON.stringify(errorBody);
      if (!response.writableEnded) {
        try {
          sendJson(response, 500, errorBody);
          requestMetric.complete(500, Buffer.byteLength(errorPayload));
        } catch {
          requestMetric.fail();
        }
      } else {
        requestMetric.fail();
      }
    }
  });

  const wss = new WebSocketServer({ noServer: true });
  const terminalWss = new WebSocketServer({ noServer: true });

  /** Reject an upgrade attempt with a bare HTTP response. */
  function rejectUpgrade(socket: Duplex, status: number, body?: JsonValue): void {
    const payload = body === undefined ? "" : JSON.stringify(body);
    socket.write(
      `HTTP/1.1 ${status} ${status === 401 ? "Unauthorized" : "Service Unavailable"}\r\n` +
        `Content-Type: application/json; charset=utf-8\r\n` +
        `Content-Length: ${Buffer.byteLength(payload)}\r\n` +
        "Connection: close\r\n\r\n" +
        payload,
    );
    socket.destroy();
  }

  server.on("upgrade", (request, socket, head) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    if (url.pathname === "/ws/terminal") {
      // Terminal upgrade: header-only bearer auth. Query tokens are rejected
      // even when correct (terminal URLs end up in app logs).
      const authorized = isAuthorized(
        {
          method: "GET",
          pathname: url.pathname,
          search: url.searchParams,
          authorization: request.headers.authorization,
        },
        token,
      );
      if (!authorized) {
        rejectUpgrade(socket, 401);
        return;
      }
      // Capability gate before the 101: a broken terminal surface answers
      // with an actionable HTTP error instead of a failing WebSocket.
      void terminalBroker
        .ensureCapabilityForUpgrade()
        .then((capability) => {
          if (capability.status === "unsupported") {
            rejectUpgrade(socket, 503, { ok: false, error: capability.reason, terminal: { capability } });
            return;
          }
          terminalWss.handleUpgrade(request, socket, head, (ws) => {
            terminalWss.emit("connection", ws, request);
          });
        })
        .catch((error) => {
          console.error(`terminal capability check failed: ${error instanceof Error ? error.message : String(error)}`);
          rejectUpgrade(socket, 503, { ok: false, error: "terminal capability check failed" });
        });
      return;
    }
    const authorized = isAuthorized(
      {
        method: "GET",
        pathname: url.pathname,
        search: url.searchParams,
        authorization: request.headers.authorization,
      },
      token,
      // Keep query auth for older APKs. Current clients use the Authorization
      // header so the token does not land in URL logs.
      { allowQueryToken: true },
    );
    if (url.pathname !== "/ws" || !authorized) {
      socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit("connection", ws, request);
    });
  });

  wss.on("error", (error) => {
    console.error(`websocket server error: ${error.message}`);
  });

  terminalWss.on("error", (error) => {
    console.error(`terminal websocket server error: ${error.message}`);
  });

  terminalWss.on("connection", (ws: WebSocket) => {
    const closeMetrics = metrics.openSocket("terminal");
    ws.once("close", closeMetrics);
    ws.once("error", closeMetrics);
    attachTerminalSocket(ws, {
      broker: terminalBroker,
      // Each connection owns its own broker session. The bearer token is
      // shared by every device, so using it as the identity would make one
      // device's attach silently replace the other's (a reconnect ping-pong).
      identity: randomUUID(),
      highWaterBytes: deps.terminalOptions?.highWaterBytes,
      lowWaterBytes: deps.terminalOptions?.lowWaterBytes,
      slowClientTimeoutMs: deps.terminalOptions?.slowClientTimeoutMs,
      inputQueueMaxBytes: deps.terminalOptions?.inputQueueMaxBytes,
    });
  });

  wss.on("connection", (ws: WebSocket) => {
    const closeMetrics = metrics.openSocket("feed");
    ws.once("close", closeMetrics);
    ws.once("error", closeMetrics);
    let closed = false;
    const filters: Set<string> = new Set();

    const handleFeed = (message: FeedMessage): void => {
      if (closed) return;
      if ("kind" in message) {
        // FeedEvent: {kind, data}. FeedSnapshot: {type:"snapshot", snapshot}.
        const kind = message.kind ?? "";
        if (filters.size > 0 && !filters.has(kind)) return;
      }
      // A torn-down socket throws on send; check state instead of crashing.
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: "feed", payload: message }));
      }
    };

    feed.onMessage(handleFeed);

    // Inbound frames are `subscribe`/`ping` — the live feed vocabulary — plus
    // the legacy one-shot mutation frames kept for APKs installed before
    // `commands.http.v1`. Current app builds send mutations to the HTTP
    // routes in routes/session-commands.ts and never open a socket for one;
    // no new mutation verb belongs here.
    ws.on("message", (data) => {
      void (async () => {
        let command: CommandMessage;
        try {
          // SAFETY: the frame arrives from a bearer-authenticated client; the
          // downstream `command.type` switch handles every shape (unknown
          // types fall through to the legacy adapter), so reading it as a
          // CommandMessage does not crash on malformed input.
          command = JSON.parse(data.toString()) as CommandMessage;
        } catch {
          ws.send(JSON.stringify({ type: "error", error: "invalid JSON" }));
          return;
        }
        try {
          // The live feed vocabulary is answered here, so nothing but a legacy
          // mutation frame ever reaches the compatibility adapter.
          if (command.type === "ping") {
            ws.send(JSON.stringify({ type: "pong", ts: Math.round(Date.now()) }));
            return;
          }
          if (command.type === "subscribe") {
            // Subscribe filters are per-connection state: merge the requested
            // kinds into this connection's set so the feed forwarder above
            // drops every other kind.
            const requested = Array.isArray(command.filter) ? command.filter : [];
            for (const kind of requested) filters.add(kind);
            ws.send(JSON.stringify({ type: "subscribed", filters: requested }));
            return;
          }
          const result = await handleLegacyWsCommand(command, deps);
          ws.send(JSON.stringify(result));
        } catch (error) {
          ws.send(JSON.stringify({ type: "error", error: error instanceof Error ? error.message : String(error) }));
        }
      })();
    });

    ws.on("error", (error) => {
      console.error(`websocket error: ${error.message}`);
      closed = true;
      feed.removeMessage(handleFeed);
    });

    ws.on("close", () => {
      closed = true;
      feed.removeMessage(handleFeed);
    });
  });

  if (listen) {
    server.on("error", (error) => {
      if (error instanceof Error && "code" in error && error.code === "EADDRINUSE") {
        console.error(`port ${config.port} already in use; is another scoutr-bridge running?`);
      } else {
        console.error(`server error: ${error.message}`);
      }
      process.exit(1);
    });
    server.listen(config.port, "127.0.0.1");
  }

  return {
    url: `http://127.0.0.1:${config.port}`,
    metrics,
    close: async () => {
      // Release terminal children and cancel grace timers first; attached
      // sockets get closed(shutdown) before the connection is torn down.
      await terminalBroker.close();
      for (const client of terminalWss.clients) client.terminate();
      for (const client of wss.clients) client.terminate();
      await new Promise<void>((resolveClose) => server.close(() => resolveClose()));
    },
  };
}

/** Session paths currently in the snapshot, for cache pruning. */
export function snapshotPaths(snapshot: SessionSnapshot | null): Set<string> {
  const paths = new Set<string>();
  for (const agent of snapshot?.agents ?? []) {
    if (agent.agent_session?.kind === "path") paths.add(agent.agent_session.value);
  }
  return paths;
}
