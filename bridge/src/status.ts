/**
 * Tracks when each pane last entered its current agent status.
 *
 * herdr's snapshot has no status timestamps, so the bridge stamps them from
 * live `pane_agent_status_changed` events. Cards expose statusSinceMs and the
 * app renders "time in state" (e.g. 12m) from it.
 */
export class StatusTracker {
  private readonly entries = new Map<string, { status: string; at: number }>();

  /** Record a status observation for a pane. */
  note(paneId: string, status: string): void {
    this.entries.set(paneId, { status, at: Date.now() });
  }

  /** Epoch ms when the pane entered its current status, or undefined. */
  since(paneId: string): number | undefined {
    return this.entries.get(paneId)?.at;
  }

  /** Forget panes that no longer exist (called on pane close events). */
  prune(paneIds: ReadonlySet<string>): void {
    for (const id of this.entries.keys()) {
      if (!paneIds.has(id)) this.entries.delete(id);
    }
  }
}
