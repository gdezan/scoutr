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
import { createSession, controlSession, launchStoredSession, SessionsError } from "./sessions.js";
import { LiveOutputError, readLiveOutput } from "./live-output.js";
import { readCommandsCatalog, validateSlashCommand } from "./pi/commands.js";
import { basename, isAbsolute, relative, resolve } from "node:path";
import {
  deleteStoredSession,
  listSessionCatalog,
  renameStoredSession,
  resolveCatalogSessionPath,
  SessionCatalogError,
} from "./session-catalog.js";
import { existsSync, realpathSync } from "node:fs";

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
  thinkingLevel?: string;
  initialPrompt?: string;
  action?: string;
  text?: string;
  path?: string;
}

export type CommandMessage =
  | { type: "steer"; target: string; text: string }
  | { type: "answer_question"; paneId: string; text: string }
  | { type: "slash_command"; paneId: string; text: string }
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
  sessionCatalogRoot?: string;
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
          if (size > 1_000_000) {
            sendJson(response, 413, { ok: false, error: "body too large" });
            return;
          }
          chunks.push(chunk);
        }
        const raw = Buffer.concat(chunks).toString("utf8");
        let body: unknown = {};
        try {
          body = raw ? JSON.parse(raw) : {};
        } catch {
          sendJson(response, 400, { ok: false, error: "request body must be valid JSON" });
          return;
        }
        if (body === null || Array.isArray(body) || typeof body !== "object") {
          sendJson(response, 400, { ok: false, error: "request body must be a JSON object" });
          return;
        }
        (request as IncomingMessage & { body?: JsonBody }).body = body as JsonBody;
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

    const liveOutputMatch = pathname.match(/^\/api\/agents\/([^/]+)\/read$/);
    if (request.method === "GET" && liveOutputMatch) {
      let paneId: string;
      try {
        paneId = decodeURIComponent(liveOutputMatch[1] ?? "");
      } catch {
        sendJson(response, 400, { ok: false, error: "invalid pane id" });
        return;
      }
      try {
        const output = await readLiveOutput(
          routeDeps.herdr,
          paneId,
          url.searchParams.get("lines"),
        );
        sendJson(response, 200, { ok: true, output });
      } catch (error) {
        const status = error instanceof LiveOutputError ? error.status : 502;
        sendJson(response, status, { ok: false, error: error instanceof Error ? error.message : String(error) });
      }
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

    if (request.method === "GET" && pathname === "/api/session-catalog") {
      const snapshot = routeDeps.feed.snapshot as SessionSnapshot | null;
      const active = snapshot
        ? deriveAgentCards(snapshot, (paneId) => tracker.since(paneId)).flatMap((card) =>
            card.sessionPath
              ? [{
                  path: card.sessionPath,
                  paneId: card.paneId,
                  workspaceId: card.workspaceId,
                  status: card.status,
                  title: card.title,
                }]
              : [],
          )
        : [];
      try {
        const limitValue = url.searchParams.get("limit");
        sendJson(response, 200, {
          ok: true,
          ...(await listSessionCatalog({
            root: routeDeps.sessionCatalogRoot,
            active,
            query: url.searchParams.get("q") ?? undefined,
            limit: limitValue === null ? undefined : Number(limitValue),
          })),
        });
      } catch (error) {
        if (error instanceof SessionCatalogError) {
          sendJson(response, error.status, { ok: false, error: error.message });
        } else {
          console.error("session catalog failed", error);
          sendJson(response, 500, { ok: false, error: "session catalog unavailable" });
        }
      }
      return;
    }

    const storedAction = pathname.match(/^\/api\/session-catalog\/(resume|fork|rename|delete)$/)?.[1];
    if (request.method === "POST" && storedAction) {
      const body = (request as IncomingMessage & { body?: JsonBody }).body ?? {};
      if (typeof body.path !== "string" || !body.path) {
        sendJson(response, 400, { ok: false, error: "path is required" });
        return;
      }
      try {
        const target = await resolveCatalogSessionPath(body.path, routeDeps.sessionCatalogRoot);
        const snapshot = routeDeps.feed.snapshot as SessionSnapshot | null;
        const active = snapshot
          ? deriveAgentCards(snapshot).find((card) => card.sessionPath && canonicalPath(card.sessionPath) === target)
          : undefined;

        if (storedAction === "resume" && active) {
          sendJson(response, 200, { ok: true, workspaceId: active.workspaceId, paneId: active.paneId });
        } else if (storedAction === "resume" || storedAction === "fork") {
          const created = await launchStoredSession(routeDeps.herdr, {
            path: target,
            mode: storedAction,
            sessionRoot: routeDeps.sessionCatalogRoot,
          });
          sendJson(response, 201, { ok: true, ...created });
        } else if (storedAction === "rename") {
          if (typeof body.text !== "string") throw new SessionCatalogError("name is required");
          if (active) {
            await controlSession(routeDeps.herdr, { paneId: active.paneId, action: "rename", text: body.text });
          } else {
            await renameStoredSession(target, body.text, routeDeps.sessionCatalogRoot);
          }
          sendJson(response, 200, { ok: true });
        } else {
          if (active) throw new SessionCatalogError("close the active session before deleting it", 409);
          await deleteStoredSession(target, routeDeps.sessionCatalogRoot);
          sendJson(response, 200, { ok: true });
        }
      } catch (error) {
        const status = error instanceof SessionCatalogError || error instanceof SessionsError ? error.status : 502;
        sendJson(response, status, { ok: false, error: error instanceof Error ? error.message : String(error) });
      }
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

    if (request.method === "GET" && pathname === "/api/commands") {
      const cwd = url.searchParams.get("cwd") ?? undefined;
      if (cwd && (cwd.length > 4096 || /[\u0000-\u001f\u007f]/.test(cwd))) {
        sendJson(response, 400, { ok: false, error: "invalid cwd" });
        return;
      }
      if (cwd) {
        const snapshot = routeDeps.feed.snapshot as SessionSnapshot | null;
        const requestedCwd = canonicalPath(cwd);
        const belongsToActiveAgent = snapshot && deriveAgentCards(snapshot).some((agent) => (
          agent.cwd !== undefined && canonicalPath(agent.cwd) === requestedCwd
        ));
        if (!belongsToActiveAgent) {
          sendJson(response, 403, { ok: false, error: "cwd is not attached to an active agent" });
          return;
        }
      }
      sendJson(response, 200, { ok: true, catalog: await readCommandsCatalog(cwd) });
      return;
    }

    if (request.method === "POST" && pathname === "/api/sessions") {
      const body = (request as IncomingMessage & { body?: JsonBody }).body ?? {};
      try {
        // createSession validates; one authenticated call creates the pane and
        // delivers the first prompt (optional initialPrompt, thinkingLevel).
        const created = await createSession(routeDeps.herdr, {
          cwd: body.cwd ?? "",
          model: body.model ?? "",
          name: body.name,
          thinkingLevel: body.thinkingLevel,
          initialPrompt: body.initialPrompt,
        });
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
    case "slash_command": {
      const { paneId, text } = command;
      if (!paneId) throw new Error("slash_command requires paneId");
      const slashCommand = validateSlashCommand(text);
      await deps.herdr.paneSendInput(paneId, slashCommand, ["Enter"]);
      ws.send(JSON.stringify({ type: "command_sent", paneId, text: slashCommand }));
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
  model: string | null;
  thinkingLevel: string | null;
  preview?: string;
  lastEntryId: string | null;
  mtimeMs: number;
}

export async function readSession(pathParam: string, since: string | null): Promise<SessionReadResult> {
  // Only allow absolute paths under the user's pi agent directory (read-only data).
  const agentRoot = canonicalPath(resolve(process.env.PI_CODING_AGENT_DIR?.trim() || `${process.env.HOME}/.pi/agent`));
  const target = canonicalPath(resolve(pathParam));
  const pathFromRoot = relative(agentRoot, target);
  if (!pathFromRoot || pathFromRoot.startsWith("..") || isAbsolute(pathFromRoot)) {
    throw new Error("session path must live under the pi agent directory");
  }
  const info = await inspectSessionFile(target);
  if (!info.exists) {
    return { path: target, name: basename(target), exists: false, since, entries: [], model: null, thinkingLevel: null, lastEntryId: null, mtimeMs: 0 };
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
    model: session.model,
    thinkingLevel: session.thinkingLevel,
    preview: lastEntry ? entryText(lastEntry, 120) : undefined,
    lastEntryId: session.lastEntryId,
    mtimeMs: info.mtimeMs,
  };
}

function canonicalPath(path: string): string {
  try {
    return realpathSync(path);
  } catch {
    return resolve(path);
  }
}
