import { HerdrClient, type SubscriptionHandle } from "./client.js";
import type { SessionSnapshot, Subscription } from "./types.js";

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
  data: Record<string, unknown>;
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

export class HerdrEventFeed {
  private readonly client: HerdrClient;
  private handle: SubscriptionHandle | null = null;
  private stopped = false;
  private rebuilding: Promise<void> | null = null;
  private refreshTimer: ReturnType<typeof setInterval> | null = null;
  private lastSnapshot: SessionSnapshot | null = null;

  constructor(
    socketPath: string,
    private readonly emit: (message: FeedMessage) => void,
  ) {
    this.client = new HerdrClient({ socketPath });
  }

  get snapshot(): SessionSnapshot | null {
    return this.lastSnapshot;
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
    this.handle?.close();
    this.handle = null;
  }

  private async refreshSnapshot(resync: boolean): Promise<void> {
    const snapshot = await this.client.snapshot();
    this.lastSnapshot = snapshot;
    this.emit({ type: "snapshot", snapshot, resync });
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

    const paneIds = (this.lastSnapshot?.panes ?? []).map((pane) => pane.pane_id);
    const subscriptions: Subscription[] = [
      ...GLOBAL_SUBSCRIPTIONS,
      ...paneIds.flatMap((paneId): Subscription[] => [
        { type: "pane.agent_status_changed", pane_id: paneId },
        { type: "pane.scroll_changed", pane_id: paneId },
      ]),
    ];

    let rebuildScheduled = false;
    const scheduleRebuild = () => {
      if (rebuildScheduled || this.stopped) return;
      rebuildScheduled = true;
      setTimeout(() => {
        rebuildScheduled = false;
        void this.buildSubscription();
      }, 300);
    };

    this.handle = await this.client.subscribe(subscriptions, {
      onEvent: (kind, data) => {
        const dotType = kind.replace(/_/g, ".");
        if (TOPOLOGY_EVENTS.has(dotType) || TOPOLOGY_EVENTS.has(kind)) {
          scheduleRebuild();
        }
        this.emit({ kind, data });
      },
      onError: (error) => {
        this.emit({ kind: "feed_error", data: { message: error.message } });
      },
      onClose: () => {
        // Socket died: reconnect after a short backoff unless stopped.
        if (this.stopped) return;
        setTimeout(() => {
          void this.buildSubscription().catch((error) => {
            this.emit({ kind: "feed_error", data: { message: error.message } });
          });
        }, 1000);
      },
    });
  }
}
