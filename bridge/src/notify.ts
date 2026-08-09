import type { FeedEvent } from "./herdr/feed.js";

/**
 * Publishes blocked-agent events to a self-hosted ntfy server.
 *
 * Only sends when an agent transitions to blocked (needs the user). Repeated
 * blocked reports for the same pane are throttled to one notification per
 * minute, and a snapshot-derived state is only used to seed the last-known
 * state (snapshots are ignored for publishing; we only publish on live events).
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
    if (status !== "blocked") return false;

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
    await this.publish({ title: `${displayAgent} needs you`, message: title });
    return true;
  }

  async publish({ title, message }: { title: string; message: string }): Promise<void> {
    if (!this.config) return;
    const url = `${this.config.baseUrl.replace(/\/+$/, "")}/${this.config.topic}`;
    try {
      // JSON body keeps unicode titles (e.g. the π display name) intact.
      await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title, message, priority: 4 }),
      });
    } catch (error) {
      // Push is best-effort; never let a notification failure break the bridge.
      console.error(`[ntfy] publish failed: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
}
