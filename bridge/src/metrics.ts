export type BridgeSocketKind = "feed" | "terminal";

export interface BridgeRouteMetricsSnapshot {
  requests: number;
  responses: number;
  errors: number;
  responseBytes: number;
  totalDurationMs: number;
  maxDurationMs: number;
  statuses: Record<string, number>;
}

export interface BridgeSocketMetricsSnapshot {
  opened: number;
  closed: number;
  active: number;
}

export interface BridgeMetricsSnapshot {
  activeRequests: number;
  requests: number;
  responses: number;
  errors: number;
  responseBytes: number;
  totalDurationMs: number;
  maxDurationMs: number;
  routes: Record<string, BridgeRouteMetricsSnapshot>;
  sockets: Record<BridgeSocketKind, BridgeSocketMetricsSnapshot>;
}

type MutableRouteMetrics = {
  requests: number;
  responses: number;
  errors: number;
  responseBytes: number;
  totalDurationMs: number;
  maxDurationMs: number;
  statuses: Map<number, number>;
};

/**
 * Process-local metrics for repeatable performance experiments.
 *
 * This intentionally has no exporter or persistent storage. Route labels are
 * normalized so ids, paths, query strings, and authorization values never
 * become metric dimensions.
 */
export class BridgeMetrics {
  private readonly routeMetrics = new Map<string, MutableRouteMetrics>();
  private readonly socketMetrics = {
    feed: { opened: 0, closed: 0, active: 0 },
    terminal: { opened: 0, closed: 0, active: 0 },
  };
  private activeRequests = 0;
  private requests = 0;
  private responses = 0;
  private errors = 0;
  private responseBytes = 0;
  private totalDurationMs = 0;
  private maxDurationMs = 0;

  beginRequest(method: string, pathname: string): BridgeRequestMetric {
    const route = `${method} ${normalizeMetricPath(pathname)}`;
    const metrics = this.routeMetrics.get(route) ?? this.createRoute(route);
    this.routeMetrics.set(route, metrics);
    this.activeRequests += 1;
    this.requests += 1;
    metrics.requests += 1;
    return new BridgeRequestMetric(this, metrics);
  }

  openSocket(kind: BridgeSocketKind): () => void {
    const metrics = this.socketMetrics[kind];
    metrics.opened += 1;
    metrics.active += 1;
    let closed = false;
    return () => {
      if (closed) return;
      closed = true;
      metrics.closed += 1;
      metrics.active = Math.max(0, metrics.active - 1);
    };
  }

  snapshot(): BridgeMetricsSnapshot {
    const routes = Object.fromEntries(
      [...this.routeMetrics.entries()].map(([route, metrics]) => [route, {
        requests: metrics.requests,
        responses: metrics.responses,
        errors: metrics.errors,
        responseBytes: metrics.responseBytes,
        totalDurationMs: metrics.totalDurationMs,
        maxDurationMs: metrics.maxDurationMs,
        statuses: Object.fromEntries([...metrics.statuses.entries()].map(([status, count]) => [String(status), count])),
      }]),
    );
    const sockets = {
      feed: { ...this.socketMetrics.feed },
      terminal: { ...this.socketMetrics.terminal },
    };
    return {
      activeRequests: this.activeRequests,
      requests: this.requests,
      responses: this.responses,
      errors: this.errors,
      responseBytes: this.responseBytes,
      totalDurationMs: this.totalDurationMs,
      maxDurationMs: this.maxDurationMs,
      routes,
      sockets,
    };
  }

  reset(): void {
    if (this.activeRequests !== 0 || this.socketMetrics.feed.active !== 0 || this.socketMetrics.terminal.active !== 0) {
      throw new Error("cannot reset bridge metrics with active work");
    }
    this.routeMetrics.clear();
    this.requests = 0;
    this.responses = 0;
    this.errors = 0;
    this.responseBytes = 0;
    this.totalDurationMs = 0;
    this.maxDurationMs = 0;
    for (const metrics of Object.values(this.socketMetrics)) {
      metrics.opened = 0;
      metrics.closed = 0;
    }
  }

  completeRequest(metrics: MutableRouteMetrics, status: number, responseBytes: number, durationMs: number): void {
    this.activeRequests = Math.max(0, this.activeRequests - 1);
    this.responses += 1;
    this.responseBytes += Math.max(0, responseBytes);
    this.totalDurationMs += Math.max(0, durationMs);
    this.maxDurationMs = Math.max(this.maxDurationMs, durationMs);
    metrics.responses += 1;
    metrics.responseBytes += Math.max(0, responseBytes);
    metrics.totalDurationMs += Math.max(0, durationMs);
    metrics.maxDurationMs = Math.max(metrics.maxDurationMs, durationMs);
    metrics.statuses.set(status, (metrics.statuses.get(status) ?? 0) + 1);
    if (status >= 400) {
      this.errors += 1;
      metrics.errors += 1;
    }
  }

  /** Settle a request whose response could not be written. */
  failRequest(metrics: MutableRouteMetrics): void {
    this.activeRequests = Math.max(0, this.activeRequests - 1);
    this.errors += 1;
    metrics.errors += 1;
  }

  private createRoute(_route: string): MutableRouteMetrics {
    return {
      requests: 0,
      responses: 0,
      errors: 0,
      responseBytes: 0,
      totalDurationMs: 0,
      maxDurationMs: 0,
      statuses: new Map(),
    };
  }
}

export class BridgeRequestMetric {
  private readonly startedAt = performance.now();
  private completed = false;

  constructor(
    private readonly owner: BridgeMetrics,
    private readonly metrics: MutableRouteMetrics,
  ) {}

  complete(status: number, responseBytes: number): void {
    if (this.completed) return;
    this.completed = true;
    this.owner.completeRequest(this.metrics, status, responseBytes, performance.now() - this.startedAt);
  }

  fail(): void {
    if (this.completed) return;
    this.completed = true;
    this.owner.failRequest(this.metrics);
  }
}

/** Keep metrics dimensions route-shaped and free of user-controlled values. */
export function normalizeMetricPath(pathname: string): string {
  const cleanPath = pathname.split("?", 1)[0] ?? "/";
  if (cleanPath === "/ws" || cleanPath === "/ws/terminal") return cleanPath;
  if (cleanPath.startsWith("/api/sessions/")) return "/api/sessions/:paneId";
  if (cleanPath.startsWith("/api/session-catalog/")) return "/api/session-catalog/:action";
  if (cleanPath.startsWith("/api/repo/")) return "/api/repo/:operation";
  if (cleanPath.startsWith("/api/")) return cleanPath.slice(0, 96);
  return "/other";
}
