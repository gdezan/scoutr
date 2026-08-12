import { createServer, type Server, type ServerResponse } from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import { HerdrEventFeed, type FeedMessage } from "./herdr/feed.js";
import { StatusTracker } from "./status.js";
import { BoardDetailCache } from "./board-detail.js";

import { handleCommand, type CommandMessage } from "./commands.js";
import { RouteTable, dispatchRoute, isAuthorized } from "./routes/dispatcher.js";
import { buildRoutes } from "./routes/index.js";
import type { RouteDeps, ServerDeps } from "./routes/types.js";
import { type SessionSnapshot } from "./herdr/types.js";

/**
 * Cockpit bridge HTTP + WebSocket API.
 *
 * Design (grounded in the goal + research):
 *   - The bridge owns the herdr socket (never exposed raw).
 *   - Listens on 127.0.0.1:PORT in plain HTTP; `tailscale serve` terminates
 *     TLS and fronts it on the tailnet. All requests carry the bearer token.
 *   - GET endpoints are read-only (health, snapshot, transcript, usage).
 *   - The /ws endpoint streams feed events and accepts steering commands.
 *   - Routes live in bridge/src/routes/, one module per feature; this file
 *     wires each request into the route dispatcher (auth, body parsing,
 *     404, and error mapping all live there) and serializes the result.
 *
 * Deliberate steering (agent.prompt / send_input) is only possible from an
 * authenticated client, i.e. the user acting through the app.
 */

export interface CockpitServer {
  url: string;
  close: () => Promise<void>;
}

export interface CreateServerOptions {
  /** Start listening immediately (default true). */
  listen?: boolean;
}

function sendJson(response: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(payload),
  });
  response.end(payload);
}

export function createCockpitServer(deps: ServerDeps, options: CreateServerOptions = {}): CockpitServer {
  const { herdr, feed, usage, config, publisher } = deps;
  const token = config.token;
  const listen = options.listen ?? true;

  // App-owned pane sessions are created via herdr directly (see the create
  // flow in layer 3); no pi --mode rpc processes live here anymore.

  // Track status entry times so cards can show "time in state".
  const tracker = new StatusTracker();
  // Bounded per-agent model/latest-activity detail, memoized by file mtime.
  const boardDetail = new BoardDetailCache();
  const routeDeps: RouteDeps = { ...deps, tracker, boardDetail };
  // herdr streams some event kinds with underscores and some with dots;
  // both spellings must be matched, so membership lives in explicit sets.
  const STATUS_KINDS = new Set(["pane_agent_status_changed", "pane.agent_status_changed"]);
  const CLOSE_KINDS = new Set(["pane_closed", "pane.closed", "pane_exited", "pane.exited"]);
  feed.onMessage((message) => {
    if (!("kind" in message)) return;
    if (STATUS_KINDS.has(message.kind)) {
      const data = message.data;
      const paneId = typeof data.pane_id === "string" ? data.pane_id : "";
      const status = typeof data.agent_status === "string" ? data.agent_status : "";
      if (paneId && status) tracker.note(paneId, status);
    } else if (CLOSE_KINDS.has(message.kind)) {
      // A closed pane leaves a stale "closed" status entry behind; prune all
      // entries whose pane is no longer in the live snapshot.
      const livePanes = new Set((feed.snapshot?.panes ?? []).map((pane) => pane.pane_id));
      tracker.prune(livePanes);
      boardDetail.prune(snapshotPaths(feed.snapshot));
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
    try {
      const url = new URL(request.url ?? "/", "http://localhost");
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
      sendJson(response, result.status, result.body);
    } catch (error) {
      // A route must never crash the process: log, then answer 500 unless the
      // client already went away.
      console.error(`route error: ${error instanceof Error ? error.message : String(error)}`);
      if (!response.writableEnded) {
        try {
          sendJson(response, 500, { ok: false, error: "internal server error" });
        } catch {
          // Response already written or aborted; nothing more to send.
        }
      }
    }
  });

  const wss = new WebSocketServer({ noServer: true });

  server.on("upgrade", (request, socket, head) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    const authorized = isAuthorized(
      {
        method: "GET",
        pathname: url.pathname,
        search: url.searchParams,
        authorization: request.headers.authorization,
      },
      token,
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

  wss.on("connection", (ws: WebSocket) => {
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

    ws.on("message", (data) => {
      void (async () => {
        let command: CommandMessage;
        try {
          command = JSON.parse(data.toString()) as CommandMessage;
        } catch {
          ws.send(JSON.stringify({ type: "error", error: "invalid JSON" }));
          return;
        }
        try {
          // Subscribe filters are per-connection state: merge the requested
          // kinds into the connection's set before the (ack-only) command
          // handler runs, so the feed forwarder below drops other kinds.
          if (command.type === "subscribe" && Array.isArray(command.filter)) {
            for (const kind of command.filter) filters.add(kind);
          }
          const result = await handleCommand(command, deps);
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
        console.error(`port ${config.port} already in use; is another cockpit-bridge running?`);
      } else {
        console.error(`server error: ${error.message}`);
      }
      process.exit(1);
    });
    server.listen(config.port, "127.0.0.1");
  }

  return {
    url: `http://127.0.0.1:${config.port}`,
    close: async () => {
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
