import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import { HerdrClient } from "./herdr/client.js";
import { HerdrEventFeed, type FeedMessage } from "./herdr/feed.js";
import type { SessionSnapshot } from "./herdr/types.js";
import { readPiSessionFile, entryText, inspectSessionFile, type PiMessageEntry } from "./pi/session.js";
import { UsageService, type UsageSnapshot } from "./usage/providers.js";
import { loadOrCreateConfig, type BridgeConfig } from "./config.js";
import { NtfyPublisher } from "./notify.js";
import { StatusTracker } from "./status.js";
import { listDirs, DirListingError } from "./dirs.js";
import { readModelsCatalog } from "./pi/models.js";
import { createSession, controlSession, SessionsError } from "./sessions.js";
import { basename, resolve } from "node:path";
import { existsSync } from "node:fs";

/**
 * Cockpit bridge HTTP + WebSocket API.
 *
 * Design (grounded in the goal + research):
 *   - The bridge owns the herdr socket (never exposed raw).
 *   - Listens on 127.0.0.1:PORT in plain HTTP; `tailscale serve` terminates
 *     TLS and fronts it on the tailnet. All requests carry the bearer token.
 *   - GET endpoints are read-only (health, snapshot, transcript, usage).
 *   - The /ws endpoint streams feed events and accepts steering commands.
 *
 * Deliberate steering (agent.prompt / send_input) is only possible from an
 * authenticated client, i.e. the user acting through the app.
 */

export interface JsonBody {
  cwd?: string;
  model?: string;
  name?: string;
  action?: string;
  text?: string;
}

export type CommandMessage =
  | { type: "steer"; target: string; text: string }
  | { type: "answer_question"; paneId: string; text: string }
  | { type: "send_text"; paneId: string; text: string }
  | { type: "ping" }
  | { type: "subscribe"; filter?: string[] };

export interface ServerDeps {
  herdr: HerdrClient;
  feed: HerdrEventFeed;
  usage: UsageService;
  config: BridgeConfig;
  /** Push publisher for blocked-agent events (layer 5); optional. */
  publisher?: NtfyPublisher;
}

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
  feed.onMessage((message) => {
    if (!("kind" in message)) return;
    if (message.kind === "pane_agent_status_changed" || message.kind === "pane.agent_status_changed") {
      const data = message.data;
      const paneId = typeof data.pane_id === "string" ? data.pane_id : "";
      const status = typeof data.agent_status === "string" ? data.agent_status : "";
      if (paneId && status) tracker.note(paneId, status);
    } else if (message.kind === "pane_closed" || message.kind === "pane.exited") {
      const data = message.data;
      const paneId = typeof data.pane_id === "string" ? data.pane_id : "";
      if (paneId) tracker.note(paneId, "closed");
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

  const server = createServer(async (request, response) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    try {
      if (!isAuthorized(request, token)) {
        sendJson(response, 401, { ok: false, error: "unauthorized" });
        return;
      }
      if (request.method === "POST") {
        const chunks: Buffer[] = [];
        let size = 0;
        for await (const chunk of request) {
          size += chunk.length;
          if (size > 1_000_000) throw new Error("body too large");
          chunks.push(chunk);
        }
        const raw = Buffer.concat(chunks).toString("utf8");
        (request as IncomingMessage & { body?: JsonBody }).body = raw ? JSON.parse(raw) : {};
      }
      await route(request, response, url, deps);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      sendJson(response, 500, { ok: false, error: message });
    }
  });

  const wss = new WebSocketServer({ noServer: true });

  server.on("upgrade", (request, socket, head) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    if (url.pathname !== "/ws" || !isAuthorized(request, token)) {
      socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit("connection", ws, request);
    });
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
      ws.send(JSON.stringify({ type: "feed", payload: message }));
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
          await handleCommand(command, ws, deps);
        } catch (error) {
          ws.send(JSON.stringify({ type: "error", error: error instanceof Error ? error.message : String(error) }));
        }
      })();
    });

    ws.on("close", () => {
      closed = true;
      feed.removeMessage(handleFeed);
    });
  });

  async function route(
    request: IncomingMessage,
    response: ServerResponse,
    url: URL,
    routeDeps: ServerDeps,
  ): Promise<void> {
    const pathname = url.pathname;

    if (request.method === "GET" && pathname === "/api/health") {
      let herdrConnected = false;
      let herdrVersion = "";
      let herdrProtocol: number | undefined;
      try {
        const pong = await routeDeps.herdr.ping();
        herdrConnected = true;
        herdrVersion = pong.version;
        herdrProtocol = pong.protocol;
      } catch {
        herdrConnected = false;
      }
      sendJson(response, 200, {
        ok: true,
        service: "cockpit-bridge",
        version: "0.1.0",
        herdr: { connected: herdrConnected, version: herdrVersion, protocol: herdrProtocol },
        ntfy: config.ntfyUrl && config.ntfyTopic ? { url: config.ntfyUrl, topic: config.ntfyTopic } : undefined,
      });
      return;
    }

    if (request.method === "GET" && pathname === "/api/snapshot") {
      const snapshot = routeDeps.feed.snapshot as SessionSnapshot | null;
      if (!snapshot) {
        sendJson(response, 503, { ok: false, error: "no herdr snapshot yet" });
        return;
      }
      sendJson(response, 200, { ok: true, snapshot });
      return;
    }

    if (request.method === "GET" && pathname === "/api/agents") {
      const snapshot = routeDeps.feed.snapshot as SessionSnapshot | null;
      if (!snapshot) {
        sendJson(response, 503, { ok: false, error: "no herdr snapshot yet" });
        return;
      }
      sendJson(response, 200, { ok: true, agents: deriveAgentCards(snapshot, (p) => tracker.since(p)) });
      return;
    }

    if (request.method === "GET" && pathname === "/api/sessions") {
      const pathParam = url.searchParams.get("path");
      const since = url.searchParams.get("since") ?? null;
      if (!pathParam) {
        sendJson(response, 400, { ok: false, error: "missing path query parameter" });
        return;
      }
      const result = await readSession(pathParam, since);
      sendJson(response, 200, { ok: true, ...result });
      return;
    }

    if (request.method === "GET" && pathname === "/api/usage") {
      const snapshots = await routeDeps.usage.all();
      sendJson(response, 200, { ok: true, usage: snapshots });
      return;
    }

    if (request.method === "GET" && pathname === "/api/models") {
      try {
        sendJson(response, 200, { ok: true, catalog: readModelsCatalog() });
      } catch (error) {
        sendJson(response, 404, {
          ok: false,
          error: "models-store.json not readable: " + (error instanceof Error ? error.message : String(error)),
        });
      }
      return;
    }

    if (request.method === "POST" && pathname === "/api/sessions") {
      const body = (request as IncomingMessage & { body?: JsonBody }).body ?? {};
      try {
        if (!body.cwd || !body.model) throw new SessionsError("cwd and model are required");
        const created = await createSession(routeDeps.herdr, { cwd: body.cwd, model: body.model, name: body.name });
        sendJson(response, 200, { ok: true, ...created });
      } catch (error) {
        const status = error instanceof SessionsError ? error.status : 502;
        sendJson(response, status, {
          ok: false,
          error: error instanceof Error ? error.message : String(error),
        });
      }
      return;
    }

    const controlMatch = pathname.match(/^\/api\/sessions\/([^/]+)\/control$/);
    if (request.method === "POST" && controlMatch) {
      const paneId = decodeURIComponent(controlMatch[1] ?? "");
      const body = (request as IncomingMessage & { body?: JsonBody }).body ?? {};
      try {
        await controlSession(routeDeps.herdr, { paneId, action: body.action as never, text: body.text });
        sendJson(response, 200, { ok: true });
      } catch (error) {
        const status = error instanceof SessionsError ? error.status : 502;
        sendJson(response, status, {
          ok: false,
          error: error instanceof Error ? error.message : String(error),
        });
      }
      return;
    }

    if (request.method === "GET" && pathname === "/api/dirs") {
      const requested = url.searchParams.get("path") ?? undefined;
      try {
        sendJson(response, 200, { ok: true, listing: listDirs(requested ?? process.env.HOME ?? "") });
      } catch (error) {
        const status = error instanceof DirListingError ? 400 : 500;
        sendJson(response, status, {
          ok: false,
          error: error instanceof Error ? error.message : String(error),
        });
      }
      return;
    }

    sendJson(response, 404, { ok: false, error: "not found" });
  }

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

async function handleCommand(command: CommandMessage, ws: WebSocket, deps: ServerDeps): Promise<void> {
  switch (command.type) {
    case "ping":
      ws.send(JSON.stringify({ type: "pong", ts: Math.round(Date.now()) }));
      return;
    case "subscribe": {
      // Intentional no-op placeholder: filter wiring lives on the connection.
      ws.send(JSON.stringify({ type: "subscribed", filters: command.filter ?? [] }));
      return;
    }
    case "steer": {
      const { target, text } = command;
      if (!target || !text) throw new Error("steer requires target and text");
      const result = await deps.herdr.agentPrompt(target, text);
      ws.send(JSON.stringify({ type: "steered", target, result }));
      return;
    }
    case "answer_question": {
      const { paneId, text } = command;
      if (!paneId || !text) throw new Error("answer_question requires paneId and text");
      // Type the answer, then Enter to submit it in pi's questionnaire UI.
      await deps.herdr.paneSendText(paneId, text);
      await deps.herdr.paneSendKeys(paneId, ["Enter"]);
      ws.send(JSON.stringify({ type: "answered", paneId, text }));
      return;
    }
    case "send_text": {
      const { paneId, text } = command;
      if (!paneId || !text) throw new Error("send_text requires paneId and text");
      await deps.herdr.paneSendText(paneId, text);
      ws.send(JSON.stringify({ type: "sent", paneId }));
      return;
    }
    default: {
      const exhaustive: never = command as never;
      throw new Error(`unknown command ${JSON.stringify(exhaustive)}`);
    }
  }
}

export function isAuthorized(request: IncomingMessage, token: string): boolean {
  const header = request.headers.authorization;
  if (typeof header === "string" && header.startsWith("Bearer ")) {
    return timingSafeEqual(header.slice(7), token);
  }
  const url = new URL(request.url ?? "/", "http://localhost");
  const queryToken = url.searchParams.get("token");
  if (queryToken) return timingSafeEqual(queryToken, token);
  return false;
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ── Derived views ─────────────────────────────────────────────────────

export interface AgentCard {
  paneId: string;
  workspaceId: string;
  tabId: string;
  agent: string;
  status: string;
  cwd?: string;
  title?: string;
  sessionPath?: string;
  terminalTitle?: string;
  blocked?: boolean;
  statusSinceMs?: number;
}

export function deriveAgentCards(
  snapshot: SessionSnapshot,
  statusSince: (paneId: string) => number | undefined = () => undefined,
): AgentCard[] {
  const cards: AgentCard[] = [];
  for (const agent of snapshot.agents ?? []) {
    const card: AgentCard = {
      paneId: agent.pane_id,
      workspaceId: agent.workspace_id,
      tabId: agent.tab_id,
      agent: agent.agent,
      status: agent.agent_status,
      cwd: agent.cwd ?? undefined,
      title: agent.terminal_title ?? undefined,
      terminalTitle: agent.terminal_title_stripped ?? undefined,
      blocked: agent.agent_status === "blocked",
    };
    const since = statusSince(agent.pane_id);
    if (since !== undefined) card.statusSinceMs = since;
    if (agent.agent_session?.kind === "path") card.sessionPath = agent.agent_session.value;
    cards.push(card);
  }
  return cards;
}

// ── Session transcript read (read-only) ───────────────────────────────

interface SessionReadResult {
  path: string;
  name: string;
  exists: boolean;
  since: string | null;
  entries: PiMessageEntry[];
  preview?: string;
  lastEntryId: string | null;
  mtimeMs: number;
}

export async function readSession(pathParam: string, since: string | null): Promise<SessionReadResult> {
  // Only allow absolute paths under the user's pi agent directory (read-only data).
  const agentRoot = resolve(process.env.PI_CODING_AGENT_DIR?.trim() || `${process.env.HOME}/.pi/agent`);
  const target = resolve(pathParam);
  if (!target.startsWith(agentRoot)) {
    throw new Error("session path must live under the pi agent directory");
  }
  const info = await inspectSessionFile(target);
  if (!info.exists) {
    return { path: target, name: basename(target), exists: false, since, entries: [], lastEntryId: null, mtimeMs: 0 };
  }
  const session = await readPiSessionFile(target);
  let entries = session.entries;
  let cursor: string | null = since;
  if (since) {
    // Compare by file position, not lexically: pi ids are random hex, so
    // lexical order re-sends loaded entries and the app appends duplicate
    // LazyColumn keys (Compose crashes on those).
    const cursorIndex = session.entries.findIndex((entry) => entry.entryId === since);
    if (cursorIndex === -1) {
      // Cursor no longer in the file (rotated/compacted): full snapshot; the
      // app replaces its list when since comes back null.
      cursor = null;
    } else {
      entries = session.entries.slice(cursorIndex + 1);
    }
  }
  const lastEntry = entries[entries.length - 1];
  return {
    path: target,
    name: basename(target),
    exists: true,
    since: cursor,
    entries,
    preview: lastEntry ? entryText(lastEntry, 120) : undefined,
    lastEntryId: session.lastEntryId,
    mtimeMs: info.mtimeMs,
  };
}
