import type { HerdrEventFeed } from "../herdr/feed.js";
import type { HerdrPort } from "../herdr/port.js";
import type { UsageService } from "../usage/providers.js";
import type { BridgeConfig } from "../config.js";
import type { NtfyPublisher } from "../notify.js";
import type { StatusTracker } from "../status.js";
import type { BoardDetailCache } from "../board-detail.js";
import type { TerminalLauncher } from "../terminal/types.js";
import type { TerminalSessionBroker } from "../terminal/broker.js";
import type { TerminalConnectionOptions } from "../terminal/websocket.js";
import type { BridgeMetrics } from "../metrics.js";
import type { SessionKey } from "../session-model.js";

/** JSON body of a POST request, after parsing and object validation. */
export interface JsonBody {
  cwd?: string;
  model?: string;
  name?: string;
  thinkingLevel?: string;
  initialPrompt?: string;
  agent?: string;
  action?: string;
  text?: string;
  path?: string;
  key?: SessionKey;
}

/** Everything the HTTP + WS layers need beyond the caller-provided wiring. */
export interface ServerDeps {
  herdr: HerdrPort;
  feed: HerdrEventFeed;
  usage: UsageService;
  config: BridgeConfig;
  /** Optional process-local counters for HTTP and WebSocket experiments. */
  metrics?: BridgeMetrics;
  /** Terminal child-process launcher (Slice 3 /ws/terminal route). */
  terminal: TerminalLauncher;
  /** Backpressure/grace tuning; defaults are provisional slice-8 constants. */
  terminalOptions?: Pick<
    TerminalConnectionOptions,
    "highWaterBytes" | "lowWaterBytes" | "slowClientTimeoutMs" | "inputQueueMaxBytes"
  > & { graceMs?: number };
  /** Push publisher for blocked-agent events (layer 5); optional. */
  publisher?: NtfyPublisher;
}

/**
 * Route deps: ServerDeps plus the per-server derived state the server owns
 * (status timestamps and the bounded board-detail cache). The server builds
 * these inside createScoutrServer and hands them to every handler.
 */
export interface RouteDeps extends ServerDeps {
  tracker: StatusTracker;
  boardDetail: BoardDetailCache;
  /** Server-owned terminal stream broker (health + upgrade path). */
  terminalBroker: TerminalSessionBroker;
}

export interface RouteContext {
  /** Path parameters captured by `:name` segments, percent-encoded as in the URL. */
  params: Record<string, string>;
  query: URLSearchParams;
  /** Parsed JSON body ({} for GET routes and when no body was sent). */
  body: JsonBody;
  /**
   * The unconsumed request body stream — present only on routes with
   * `rawBody: true` (e.g. binary attachment uploads), which read it
   * themselves instead of the dispatcher parsing JSON.
   */
  rawBody?: AsyncIterable<Buffer>;
  /** Content-Type header, for routes that validate it (rawBody routes). */
  contentType?: string;
  deps: RouteDeps;
}

/** Everything the dispatcher needs to serve one HTTP request. */
export interface DispatchRequest {
  method: string;
  /** Raw (percent-encoded) path, e.g. "/api/sessions/p%201/control". */
  pathname: string;
  search: URLSearchParams;
  /** Raw Authorization header value, if any. */
  authorization?: string;
  /** Request body stream; JSON routes are parsed by the dispatcher. */
  body?: AsyncIterable<Buffer>;
  /** Content-Type header, for routes that validate it (rawBody routes). */
  contentType?: string;
}

/**
 * A file streamed straight from disk instead of a JSON body — the APK the
 * phone pulls is tens of megabytes, far past what base64 inside JSON can carry.
 */
export interface RouteFile {
  path: string;
  /** Stat'd by the route, so the response can carry a content-length. */
  size: number;
  contentType: string;
  /** Suggested download name (content-disposition). */
  filename: string;
}

export interface RouteResult {
  status: number;
  body: unknown;
  /** When set, the server streams this file and ignores `body`. */
  file?: RouteFile;
}

export interface Route {
  method: "GET" | "POST";
  /**
   * Literal path or a pattern with :params — "/api/sessions/:paneId/control".
   * Literals always match before patterns regardless of table order.
   */
  path: string;
  /**
   * Consume the raw request body stream instead of JSON: the dispatcher
   * hands the unconsumed stream to `ctx.rawBody` and skips body parsing.
   */
  rawBody?: boolean;
  handle(ctx: RouteContext): Promise<RouteResult> | RouteResult;
}
