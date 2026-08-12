import type { FeedEvent } from "./herdr/feed.js";

/**
 * Publishes agent-status events to a self-hosted ntfy server.
 *
 * blocked -> "needs you" (high priority), done -> "finished" (default). Both
 * are throttled to one notification per pane per minute, and snapshots are
 * ignored for publishing; we only publish on live status events.
 */

const THROTTLE_MS = 60_000;

export interface NtfyConfig {
  baseUrl: string; // e.g. https://artemis.tail7dc568.ts.net/ntfy
  topic: string;
}

export class NtfyPublisher {
  private lastPublishedAt = new Map<string, number>();

  constructor(private readonly config: NtfyConfig | null) {}

  /** Handle one feed event; returns true if a notification was attempted. */
  async handleEvent(event: FeedEvent): Promise<boolean> {
    if (!this.config) return false;
    // herdr emits status events in both dot-form and snake_case across versions.
    if (event.kind !== "pane_agent_status_changed" && event.kind !== "pane.agent_status_changed") return false;
    const data = event.data;
    const status = typeof data.agent_status === "string" ? data.agent_status : "";
    if (status !== "blocked" && status !== "done") return false;

    const paneId = typeof data.pane_id === "string" ? data.pane_id : "";
    if (!paneId) return false;
    const now = Date.now();
    const last = this.lastPublishedAt.get(paneId) ?? 0;
    if (now - last < THROTTLE_MS) return false;
    this.lastPublishedAt.set(paneId, now);

    const agent = typeof data.agent === "string" ? data.agent : "Agent";
    const displayAgent = typeof data.display_agent === "string" ? data.display_agent : agent;
    const title = typeof data.title === "string" && data.title ? data.title :
      (typeof data.message === "string" && data.message ? data.message : paneId);
    const headline = status === "blocked" ? `${displayAgent} needs you` : `${displayAgent} finished`;
    // ntfy drops unknown JSON fields, so the deep link travels in its
    // documented `click` field; paneId is kept for callers that read the
    // publish payload directly (tests).
    const click = `cockpit://chat/${paneId}?status=${status === "blocked" ? "blocked" : "working"}`;
    await this.publish({ title: headline, message: title, priority: status === "blocked" ? 4 : 3, paneId, click });
    return true;
  }

  async publish({
    title,
    message,
    priority = 3,
    paneId,
    click,
  }: { title: string; message: string; priority?: number; paneId?: string; click?: string }): Promise<void> {
    if (!this.config) return;
    // ntfy only parses JSON bodies when POSTed to the root path, with the
    // topic inside the body; POSTing to /<topic> stores the raw JSON as text.
    const url = `${this.config.baseUrl.replace(/\/+$/, "")}/`;
    try {
      // JSON body keeps unicode titles (e.g. the π display name) intact.
      await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ topic: this.config.topic, title, message, priority, paneId, click }),
        // A hung ntfy server must not pin a socket forever.
        signal: AbortSignal.timeout(10_000),
      });
    } catch (error) {
      // Push is best-effort; never let a notification failure break the bridge.
      console.error(`[ntfy] publish failed: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  /** Forget panes that no longer exist (called on pane close events). */
  prune(paneIds: ReadonlySet<string>): void {
    for (const id of this.lastPublishedAt.keys()) {
      if (!paneIds.has(id)) this.lastPublishedAt.delete(id);
    }
  }
}
