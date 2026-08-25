/**
 * Turns herdr status events into contentless FCM pings.
 *
 * Publishing is edge-triggered: the publisher remembers which panes are
 * currently blocked, which have already triggered a done ping, and which are
 * sitting stopped on an error, sending only on transitions in and out of
 * those states.
 * A repeated `blocked` or `done` event for an already-notified pane sends
 * nothing at any interval, which dedupes strictly better than a timer would —
 * and, unlike a timer, it structurally cannot swallow the resolve that clears
 * the phone's notification.
 */

import type { FeedEvent } from "../herdr/feed.js";
import type { DeviceRegistry, FcmSender, PingKind } from "./fcm.js";
import type { ErrorStopProbe } from "./stopped-on-error.js";
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
  private readonly erroredPanes = new Set<string>();
  constructor(
    private readonly sender: FcmSender,
    private readonly devices: DeviceRegistry,
    private readonly hostId: string,
    private readonly errorStop?: ErrorStopProbe,
  ) {}

  /**
   * Per-pane event order: herdr events arrive over one socket, but a probe
   * awaited inside an idle event opens a window a following working event
   * could slip through — pinging errored after the agent already moved on.
   * Chaining per pane keeps transitions strictly ordered.
   */
  readonly #perPane = new Map<string, Promise<unknown>>();

  /** Handle one feed event; returns true when a ping was attempted. */
  async handleEvent(event: FeedEvent): Promise<boolean> {
    if (!STATUS_KINDS.has(event.kind)) return false;
    const parsed = v.safeParse(statusEventSchema, event.data);
    if (!parsed.success) return false;
    const paneId = parsed.output.pane_id ?? "";
    if (!paneId) return false;
    const status = parsed.output.agent_status ?? "";

    return this.enqueue(paneId, () => this.applyStatus(paneId, status));
  }

  /** Runs one task after every earlier task for this pane, in order. */
  private enqueue<T>(paneId: string, task: () => Promise<T>): Promise<T> {
    const prev = this.#perPane.get(paneId) ?? Promise.resolve();
    const run = prev.catch(() => undefined).then(task);
    this.#perPane.set(paneId, run);
    void run.then(() => {
      if (this.#perPane.get(paneId) === run) this.#perPane.delete(paneId);
    });
    return run;
  }

  private async applyStatus(paneId: string, status: string): Promise<boolean> {
    if (status === "blocked") {
      this.donePanes.delete(paneId);
      const clearedErrored = await this.clearErrorStop(paneId);
      if (this.blockedPanes.has(paneId)) return clearedErrored;
      this.blockedPanes.add(paneId);
      return (await this.ping("blocked", paneId)) || clearedErrored;
    }
    if (status === "done") {
      // herdr derives `done` for every completed turn — clean or fatal. The
      // transcript tail tells them apart: an error record last means the
      // agent gave up on a failing model call and needs the user, not a
      // Finished ping. Probing here costs one bounded tail read per turn end.
      if (this.errorStop && !this.erroredPanes.has(paneId)) {
        const stoppedOnError = await this.errorStop(paneId).catch(() => false);
        if (stoppedOnError) {
          this.erroredPanes.add(paneId);
          return this.ping("errored", paneId);
        }
      }
      let didSomething = await this.clearErrorStop(paneId);
      if (this.blockedPanes.delete(paneId)) {
        didSomething = (await this.ping("resolve", paneId)) || didSomething;
      }
      if (this.donePanes.has(paneId)) return didSomething;
      this.donePanes.add(paneId);
      return (await this.ping("done", paneId)) || didSomething;
    }
    if (!status) return false;
    this.donePanes.delete(paneId);
    let didSomething = false;
    // Any other status — working, or a settle this pane has no probe answer
    // for — means the agent no longer needs the user, so the phone's
    // notification for this pane should go.
    if (this.blockedPanes.delete(paneId)) {
      didSomething = (await this.ping("resolve", paneId)) || didSomething;
    }
    if (status === "idle") {
      // Fallback for herdr builds that surface the raw hook state instead:
      // settling onto a failed model call lands here too, and a clean finish
      // never sees an error record last in its transcript.
      if (this.errorStop && !this.erroredPanes.has(paneId)) {
        const stoppedOnError = await this.errorStop(paneId).catch(() => false);
        if (stoppedOnError) {
          this.erroredPanes.add(paneId);
          didSomething = (await this.ping("errored", paneId)) || didSomething;
        }
      }
    } else {
      // Working, or any state change off idle, means the agent moved on from
      // an error stop and the phone's notification for it should go.
      didSomething = (await this.clearErrorStop(paneId)) || didSomething;
    }
    return didSomething;
  }

  /** The pane moved on from an error stop; clear the phone's notification. */
  private async clearErrorStop(paneId: string): Promise<boolean> {
    if (!this.erroredPanes.delete(paneId)) return false;
    return this.ping("resolve", paneId);
  }

  /**
   * Panes gone from the snapshot resolve, then are forgotten. Chained per
   * pane like status events: a close arriving while an errored transition
   * is still probing must wait, or the late ping would resurrect a
   * notification nothing will ever resolve.
   */
  async prune(paneIds: ReadonlySet<string>): Promise<void> {
    const targets = new Set([
      ...this.#perPane.keys(),
      ...this.blockedPanes,
      ...this.erroredPanes,
    ]);
    await Promise.all(
      [...targets].map((paneId) =>
        this.enqueue(paneId, () => this.pruneOne(paneId, paneIds)),
      ),
    );
  }

  private pruneOne(paneId: string, live: ReadonlySet<string>): Promise<boolean> {
    if (live.has(paneId)) return Promise.resolve(false);
    let needsResolve = false;
    if (this.blockedPanes.delete(paneId)) needsResolve = true;
    if (this.erroredPanes.delete(paneId)) needsResolve = true;
    this.donePanes.delete(paneId);
    if (needsResolve) void this.ping("resolve", paneId);
    return Promise.resolve(needsResolve);
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
