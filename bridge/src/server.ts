import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import { HerdrClient } from "./herdr/client.js";
import { HerdrEventFeed, type FeedMessage } from "./herdr/feed.js";
import type { SessionSnapshot } from "./herdr/types.js";
import { readPiSessionFile, entryText, inspectSessionFile, type PiMessageEntry } from "./pi/session.js";
import { UsageService, type UsageSnapshot } from "./usage/providers.js";
import { loadOrCreateConfig, type BridgeConfig } from "./config.js";
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

function readBody(request: IncomingMessage): Promise<string> {
  return new Promise((resolveBody, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    request.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > 1_000_000) {
        reject(new Error("request body too large"));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => resolveBody(Buffer.concat(chunks).toString("utf8")));
    request.on("error", reject);
  });
}

export function createCockpitServer(deps: ServerDeps, options: CreateServerOptions = {}): CockpitServer {
  const { herdr, feed, usage, config } = deps;
  const token = config.token;
  const listen = options.listen ?? true;

  const server = createServer(async (request, response) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    try {
      if (!isAuthorized(request, token)) {
        sendJson(response, 401, { ok: false, error: "unauthorized" });
        return;
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
      sendJson(response, 200, { ok: true, agents: deriveAgentCards(snapshot) });
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

    sendJson(response, 404, { ok: false, error: "not found" });
  }

  if (listen) {
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
      await deps.herdr.paneSendText(paneId, text);
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
}

export function deriveAgentCards(snapshot: SessionSnapshot): AgentCard[] {
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

async function readSession(pathParam: string, since: string | null): Promise<SessionReadResult> {
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
  const entries = since ? session.entries.filter((entry) => entry.entryId > since) : session.entries;
  const lastEntry = session.entries[session.entries.length - 1];
  return {
    path: target,
    name: basename(target),
    exists: true,
    since,
    entries,
    preview: lastEntry ? entryText(lastEntry, 120) : undefined,
    lastEntryId: session.lastEntryId,
    mtimeMs: info.mtimeMs,
  };
}
