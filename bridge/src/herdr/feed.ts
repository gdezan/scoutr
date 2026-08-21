import { HerdrClient, type SubscriptionHandle } from "./client.js";
import type { HerdrValue, SessionSnapshot, Subscription } from "./types.js";

/**
 * Long-lived event feed over the herdr socket.
 *
 * herdr's events.subscribe is per-connection: global (pane-less) subscriptions
 * plus per-pane subscriptions for agent_status_changed / scroll_changed. Panes
 * come and go, so the feed rebuilds its subscription whenever topology events
 * arrive, and periodically re-snapshots to correct drift.
 */

export interface FeedEvent {
  /** Event kind as streamed by herdr, e.g. "pane_agent_status_changed". */
  kind: string;
  data: Record<string, HerdrValue>;
}

export interface FeedSnapshot {
  type: "snapshot";
  snapshot: SessionSnapshot;
  /** True when the snapshot is a re-sync after a topology rebuild. */
  resync: boolean;
}

export type FeedMessage = FeedEvent | FeedSnapshot;

const GLOBAL_SUBSCRIPTIONS: Subscription[] = [
  { type: "workspace.created" },
  { type: "workspace.updated" },
  { type: "workspace.closed" },
  { type: "workspace.focused" },
  { type: "workspace.renamed" },
  { type: "tab.created" },
  { type: "tab.closed" },
  { type: "tab.focused" },
  { type: "tab.renamed" },
  { type: "pane.created" },
  { type: "pane.closed" },
  { type: "pane.updated" },
  { type: "pane.focused" },
  { type: "pane.moved" },
  { type: "pane.exited" },
  { type: "pane.agent_detected" },
  { type: "layout.updated" },
];

/** Topology events that require a subscription rebuild (new pane ids). */
const TOPOLOGY_EVENTS = new Set([
  "pane.created",
  "pane.closed",
  "pane.exited",
  "pane.moved",
  "workspace.closed",
  "tab.closed",
]);

/** Fixed delay before resubscribing after a failed subscribe attempt. */
const SUBSCRIBE_RETRY_MS = 1_000;

export class HerdrEventFeed {
  private readonly client: HerdrClient;
  private handle: SubscriptionHandle | null = null;
  private stopped = false;
  private rebuilding: Promise<void> | null = null;
  private refreshTimer: ReturnType<typeof setInterval> | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private rebuildTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private refreshSeq = 0;
  private lastSnapshot: SessionSnapshot | null = null;
  private readonly handlers = new Set<(message: FeedMessage) => void>();

  constructor(
    socketPath: string,
    emit?: (message: FeedMessage) => void,
  ) {
    this.client = new HerdrClient({ socketPath });
    if (emit) this.handlers.add(emit);
  }

  get snapshot(): SessionSnapshot | null {
    return this.lastSnapshot;
  }

  /** Register a message handler; returns an unsubscribe function. */
  onMessage(handler: (message: FeedMessage) => void): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  removeMessage(handler: (message: FeedMessage) => void): void {
    this.handlers.delete(handler);
  }

  private emitAll(message: FeedMessage): void {
    for (const handler of this.handlers) handler(message);
  }

  async start(): Promise<void> {
    this.stopped = false;
    await this.refreshSnapshot(false);
    await this.buildSubscription();
    this.refreshTimer = setInterval(() => {
      void this.refreshSnapshot(true).catch(() => undefined);
    }, 30_000);
    this.refreshTimer.unref?.();
  }

  async stop(): Promise<void> {
    this.stopped = true;
    if (this.refreshTimer) clearInterval(this.refreshTimer);
    this.refreshTimer = null;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    if (this.rebuildTimer) clearTimeout(this.rebuildTimer);
    this.rebuildTimer = null;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.handle?.close();
    this.handle = null;
  }

  private async refreshSnapshot(resync: boolean): Promise<void> {
    // Generation guard: periodic and rebuild-triggered refreshes can
    // overlap; an older request finishing last must not overwrite a newer
    // snapshot (or re-emit it as fresh).
    const seq = ++this.refreshSeq;
    const snapshot = await this.client.snapshot();
    if (seq !== this.refreshSeq) return;
    this.lastSnapshot = snapshot;
    this.emitAll({ type: "snapshot", snapshot, resync });
  }

  private buildSubscription(): Promise<void> {
    if (this.rebuilding) return this.rebuilding;
    this.rebuilding = this.doBuildSubscription().finally(() => {
      this.rebuilding = null;
    });
    return this.rebuilding;
  }

  private async doBuildSubscription(): Promise<void> {
    this.handle?.close();
    if (this.stopped) return;

    // Re-sync the snapshot before deriving pane subscriptions, so a rebuild
    // right after pane.created already sees the new pane (the 30s periodic
    // refresh alone would miss its first status event).
    try {
      await this.refreshSnapshot(true);
    } catch {
      // A snapshot failure must not block resubscribing to global events;
      // the next retry refreshes again.
    }
    if (this.stopped) return;

    const paneIds = (this.lastSnapshot?.panes ?? []).map((pane) => pane.pane_id);
    const subscriptions: Subscription[] = [
      ...GLOBAL_SUBSCRIPTIONS,
      ...paneIds.flatMap((paneId): Subscription[] => [
        { type: "pane.agent_status_changed", pane_id: paneId },
        { type: "pane.scroll_changed", pane_id: paneId },
      ]),
    ];

    const scheduleRebuild = () => {
      // One rebuild timer at a time; stop() clears it so a stale timer can
      // never fire against a restarted feed's new subscription.
      if (this.stopped || this.rebuildTimer) return;
      this.rebuildTimer = setTimeout(() => {
        this.rebuildTimer = null;
        if (this.stopped) return;
        void this.buildSubscription();
      }, 300);
    };

    try {
      const handle = await this.client.subscribe(subscriptions, {
        onEvent: (kind, data) => {
          const dotType = kind.replace(/_/g, ".");
          if (TOPOLOGY_EVENTS.has(dotType) || TOPOLOGY_EVENTS.has(kind)) {
            scheduleRebuild();
          }
          this.emitAll({ kind, data });
        },
        onError: (error) => {
          this.emitAll({ kind: "feed_error", data: { message: error.message } });
        },
        onClose: () => {
          // Socket died: reconnect after a short backoff unless stopped.
          // One reconnect timer at a time, cleared by stop() so a stale
          // timer can never rebuild a restarted feed's subscription.
          if (this.stopped || this.reconnectTimer) return;
          this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            if (this.stopped) return;
            void this.buildSubscription().catch((error) => {
              this.emitAll({ kind: "feed_error", data: { message: error.message } });
            });
          }, 1000);
        },
      });
      if (this.stopped) {
        // stop() ran while the ack was pending: never install a live
        // subscription on a stopped feed — close the late handle.
        handle.close();
        return;
      }
      this.handle = handle;
    } catch (error) {
      // Subscribe failed outright (herdr restarting, socket closed before the
      // ack): report and retry with a fixed backoff instead of wedging.
      this.emitAll({
        kind: "feed_error",
        data: { message: error instanceof Error ? error.message : String(error) },
      });
      if (this.stopped) return;
      this.retryTimer = setTimeout(() => {
        this.retryTimer = null;
        void this.buildSubscription();
      }, SUBSCRIBE_RETRY_MS);
    }
  }
}
