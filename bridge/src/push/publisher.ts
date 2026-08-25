/**
 * Turns herdr status events into contentless FCM pings.
 *
 * Publishing is edge-triggered: the publisher remembers which panes are
 * currently blocked and which have already triggered a done ping, sending only
 * on transitions in and out of those states.
 * A repeated `blocked` or `done` event for an already-notified pane sends
 * nothing at any interval, which dedupes strictly better than a timer would —
 * and, unlike a timer, it structurally cannot swallow the resolve that clears
 * the phone's notification.
 */

import type { FeedEvent } from "../herdr/feed.js";
import type { DeviceRegistry, FcmSender, PingKind } from "./fcm.js";
import * as v from "valibot";

// herdr emits status events in both dot-form and snake_case across versions.
const statusEventSchema = v.looseObject({
  pane_id: v.optional(v.string()),
  agent_status: v.optional(v.string()),
});
const STATUS_KINDS = new Set(["pane_agent_status_changed", "pane.agent_status_changed"]);

export class FcmPublisher {
  private readonly blockedPanes = new Set<string>();
  private readonly donePanes = new Set<string>();
  constructor(
    private readonly sender: FcmSender,
    private readonly devices: DeviceRegistry,
    private readonly hostId: string,
  ) {}

  /** Handle one feed event; returns true when a ping was attempted. */
  async handleEvent(event: FeedEvent): Promise<boolean> {
    if (!STATUS_KINDS.has(event.kind)) return false;
    const parsed = v.safeParse(statusEventSchema, event.data);
    if (!parsed.success) return false;
    const paneId = parsed.output.pane_id ?? "";
    if (!paneId) return false;
    const status = parsed.output.agent_status ?? "";

    if (status === "blocked") {
      this.donePanes.delete(paneId);
      if (this.blockedPanes.has(paneId)) return false;
      this.blockedPanes.add(paneId);
      return this.ping("blocked", paneId);
    }
    if (status === "done") {
      let didSomething = false;
      if (this.blockedPanes.delete(paneId)) {
        didSomething = (await this.ping("resolve", paneId)) || didSomething;
      }
      if (this.donePanes.has(paneId)) return didSomething;
      this.donePanes.add(paneId);
      return (await this.ping("done", paneId)) || didSomething;
    }
    if (!status) return false;
    this.donePanes.delete(paneId);
    // Any other status — working, idle, etc. — means the agent no longer
    // needs the user, so the phone's notification for this pane should go.
    if (!this.blockedPanes.delete(paneId)) return false;
    return this.ping("resolve", paneId);
  }

  /** Panes gone from the snapshot resolve, then are forgotten. */
  prune(paneIds: ReadonlySet<string>): void {
    const blocked = [...this.blockedPanes];
    for (const paneId of blocked) {
      if (paneIds.has(paneId)) continue;
      this.blockedPanes.delete(paneId);
      void this.ping("resolve", paneId);
    }
    const done = [...this.donePanes];
    for (const paneId of done) {
      if (paneIds.has(paneId)) continue;
      this.donePanes.delete(paneId);
    }
  }

  private async ping(kind: PingKind, paneId: string): Promise<boolean> {
    // Generations belong to registrations, not to the bridge event. Snapshot
    // the records before sending so a mixed old/new app population receives
    // either the legacy or qualified wire shape independently.
    const devices = [...this.devices.list()];
    if (devices.length === 0) return false;
    const stale = (
      await Promise.all(
        devices.map((device) =>
          this.sender.send([device.token], kind, paneId, this.hostId, device.profileGeneration),
        ),
      )
    ).flat();
    for (const token of stale) await this.devices.unregister(token);
    return true;
  }
}
