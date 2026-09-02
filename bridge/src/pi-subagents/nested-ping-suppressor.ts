import type { SessionSnapshot } from "../herdr/types.js";
import { liveNestedSubagentPaneIds } from "./nest-live-subagents.js";
import type { PiSubagentStoreOptions } from "./run-store.js";

/** Source of the current Herdr snapshot; satisfied by HerdrEventFeed. */
export interface NestedPingSnapshotSource {
  readonly snapshot: SessionSnapshot | null;
}

/**
 * FCM suppress probe: nested PI-workflow child pane ids return true.
 * Orphans return false. Does not wait for a Board poll.
 */
export function makeNestedSubagentPingSuppressor(
  source: NestedPingSnapshotSource,
  options?: PiSubagentStoreOptions,
): (paneId: string) => Promise<boolean> {
  return async (paneId) => {
    const nested = await liveNestedSubagentPaneIds(source.snapshot, options);
    return nested.has(paneId);
  };
}
