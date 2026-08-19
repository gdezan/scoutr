/**
 * Turns herdr status events into contentless FCM pings.
 *
 * Publishing is edge-triggered: the publisher remembers which panes are
 * currently blocked and sends only on transitions in and out of that state.
 * A repeated `blocked` event for an already-blocked pane sends nothing at any
 * interval, which dedupes strictly better than a timer would — and, unlike a
 * timer, it structurally cannot swallow the resolve that clears the phone's
 * notification.
 */

import type { FeedEvent } from "../herdr/feed.js";
import type { DeviceRegistry, FcmSender, PingKind } from "./fcm.js";

// herdr emits status events in both dot-form and snake_case across versions.
const STATUS_KINDS = new Set(["pane_agent_status_changed", "pane.agent_status_changed"]);

export class FcmPublisher {
  private readonly blockedPanes = new Set<string>();

  constructor(
    private readonly sender: FcmSender,
    private readonly devices: DeviceRegistry,
  ) {}

  /** Handle one feed event; returns true when a ping was attempted. */
  async handleEvent(event: FeedEvent): Promise<boolean> {
    if (!STATUS_KINDS.has(event.kind)) return false;
    const data = event.data;
    const paneId = typeof data.pane_id === "string" ? data.pane_id : "";
    if (!paneId) return false;
    const status = typeof data.agent_status === "string" ? data.agent_status : "";

    if (status === "blocked") {
      if (this.blockedPanes.has(paneId)) return false;
      this.blockedPanes.add(paneId);
      return this.ping("blocked", paneId);
    }
    if (!status) return false;
    // Any other status — working, idle, done — means the agent no longer
    // needs the user, so the phone's notification for this pane should go.
    if (!this.blockedPanes.delete(paneId)) return false;
    return this.ping("resolve", paneId);
  }

  /** Panes gone from the snapshot resolve, then are forgotten. */
  prune(paneIds: ReadonlySet<string>): void {
    for (const paneId of [...this.blockedPanes]) {
      if (paneIds.has(paneId)) continue;
      this.blockedPanes.delete(paneId);
      void this.ping("resolve", paneId);
    }
  }

  private async ping(kind: PingKind, paneId: string): Promise<boolean> {
    const tokens = this.devices.list().map((device) => device.token);
    if (tokens.length === 0) return false;
    const stale = await this.sender.send(tokens, kind, paneId);
    for (const token of stale) await this.devices.unregister(token);
    return true;
  }
}
