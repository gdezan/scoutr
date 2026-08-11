import { BridgeError } from "../errors.js";
import type { DispatchRequest, Route, RouteContext, RouteDeps, RouteResult } from "./types.js";

export interface Match {
  route: Route;
  params: Record<string, string>;
}

type Segment = string | { param: string };

function parsePath(path: string): Segment[] {
  return path
    .split("/")
    .slice(1)
    .map((segment) => (segment.startsWith(":") ? { param: segment.slice(1) } : segment));
}

function isPattern(segments: Segment[]): boolean {
  return segments.some((segment) => typeof segment !== "string");
}

/**
 * Route table: literals and `:param` patterns, literals matched first so a
 * literal route can never be shadowed by a pattern. Constructing the table
 * asserts the invariants the old if-chain held implicitly — no duplicate
 * routes, and no pattern that can match a literal route's path.
 */
export class RouteTable {
  private readonly literals = new Map<string, Route>();
  private readonly patterns: { route: Route; segments: Segment[] }[] = [];

  constructor(routes: Route[]) {
    for (const route of routes) {
      const segments = parsePath(route.path);
      if (isPattern(segments)) {
        this.patterns.push({ route, segments });
      } else {
        const key = `${route.method} ${route.path}`;
        if (this.literals.has(key)) throw new Error(`duplicate route: ${key}`);
        this.literals.set(key, route);
      }
    }
    assertNoShadowing(routes);
  }

  /** Literal routes first, then patterns in registration order. */
  match(method: string, pathname: string): Match | undefined {
    const literal = this.literals.get(`${method} ${pathname}`);
    if (literal) return { route: literal, params: {} };

    const segments = pathname.split("/").slice(1);
    for (const { route, segments: pattern } of this.patterns) {
      if (route.method !== method || pattern.length !== segments.length) continue;
      const params: Record<string, string> = {};
      let matches = true;
      for (let i = 0; i < pattern.length; i++) {
        const expected = pattern[i]!;
        if (typeof expected === "string") {
          if (expected !== segments[i]) {
            matches = false;
            break;
          }
        } else if (segments[i]) {
          // :params match one non-empty segment (the old regexes used [^/]+).
          params[expected.param] = segments[i]!;
        } else {
          matches = false;
          break;
        }
      }
      if (matches) return { route, params };
    }
    return undefined;
  }
}

/** Max size of a parsed JSON request body (413 beyond it). */
export const JSON_BODY_MAX_BYTES = 1_000_000;

/** Constant-time token check against the Authorization header or ?token=. */
export function isAuthorized(request: DispatchRequest, token: string): boolean {
  const header = request.authorization;
  if (typeof header === "string" && header.startsWith("Bearer ")) {
    return timingSafeEqual(header.slice(7), token);
  }
  const queryToken = request.search.get("token");
  if (queryToken) return timingSafeEqual(queryToken, token);
  return false;
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function readBoundedBody(stream: AsyncIterable<Buffer>, maxBytes: number): Promise<Buffer> {
  const parts: Buffer[] = [];
  let total = 0;
  for await (const chunk of stream) {
    total += chunk.length;
    if (total > maxBytes) throw new BridgeError("body too large", 413);
    parts.push(chunk);
  }
  return Buffer.concat(parts);
}

/**
 * One dispatch = auth, match, body parse, handler, error mapping — the five
 * concerns the old if-chain repeated per branch. Returns a JSON-able result
 * or a 401/404/413/400 error body; handler failures map to the feature
 * error's status, anything else to a herdr-facing 502.
 */
export async function dispatchRoute(
  table: RouteTable,
  request: DispatchRequest,
  deps: RouteDeps,
): Promise<RouteResult> {
  if (!isAuthorized(request, deps.config.token)) {
    return { status: 401, body: { ok: false, error: "unauthorized" } };
  }
  const matched = table.match(request.method, request.pathname);
  if (!matched) {
    return { status: 404, body: { ok: false, error: "not found" } };
  }
  const { route, params } = matched;
  try {
    let body: RouteContext["body"] = {};
    let rawBody: AsyncIterable<Buffer> | undefined;
    if (route.rawBody) {
      rawBody = request.body;
    } else if (request.method === "POST") {
      const raw = (await readBoundedBody(request.body ?? noBody(), JSON_BODY_MAX_BYTES)).toString("utf8");
      let parsed: unknown = {};
      try {
        parsed = raw ? JSON.parse(raw) : {};
      } catch {
        return { status: 400, body: { ok: false, error: "request body must be valid JSON" } };
      }
      if (parsed === null || Array.isArray(parsed) || typeof parsed !== "object") {
        return { status: 400, body: { ok: false, error: "request body must be a JSON object" } };
      }
      body = parsed as RouteContext["body"];
    }
    return await route.handle({ params, query: request.search, body, rawBody, contentType: request.contentType, deps });
  } catch (error) {
    const status = error instanceof BridgeError ? error.status : 502;
    return {
      status,
      body: { ok: false, error: error instanceof Error ? error.message : String(error) },
    };
  }
}

async function* noBody(): AsyncIterable<Buffer> {}

function assertNoShadowing(routes: Route[]): void {
  const literals: { method: string; path: string; segments: Segment[] }[] = [];
  const patterns: { method: string; path: string; segments: Segment[] }[] = [];
  for (const route of routes) {
    const segments = parsePath(route.path);
    (isPattern(segments) ? patterns : literals).push({ method: route.method, path: route.path, segments });
  }
  // Two patterns with the same shape (position-wise literal/param, equal
  // literal values) would match the same paths — first-wins ordering would
  // silently shadow the second.
  for (let i = 0; i < patterns.length; i++) {
    for (let j = i + 1; j < patterns.length; j++) {
      const a = patterns[i]!;
      const b = patterns[j]!;
      if (a.method !== b.method || a.segments.length !== b.segments.length) continue;
      if (a.segments.every((segment, k) => shapesEqual(segment, b.segments[k]!))) {
        throw new Error(`routes shadow each other: ${a.path} and ${b.path}`);
      }
    }
  }
  // A pattern that can match a literal route's path shadows it: the literal
  // could never be reached (literals are matched first, but the pattern's
  // existence makes the literal dead — e.g. /api/sessions/control vs
  // /api/sessions/:paneId — and ordering-dependent).
  for (const pattern of patterns) {
    for (const literal of literals) {
      if (pattern.method !== literal.method || pattern.segments.length !== literal.segments.length) continue;
      if (
        pattern.segments.every(
          (segment, k) => typeof segment !== "string" || segment === literal.segments[k],
        )
      ) {
        throw new Error(`route ${pattern.path} shadows literal ${literal.path}`);
      }
    }
  }
}

function shapesEqual(a: Segment, b: Segment): boolean {
  if (typeof a === "string" || typeof b === "string") return a === b;
  return true; // both params
}
