import type { HerdrEventFeed, FeedMessage } from "../../src/herdr/feed.js";
import type { SessionSnapshot } from "../../src/herdr/types.js";

export interface FakeFeedExtras {
  setSnapshot(next: SessionSnapshot | null): void;
  /** Deliver a feed message to every registered handler (WS streaming tests). */
  emit(message: FeedMessage): void;
}

/**
 * In-memory HerdrEventFeed: a settable snapshot and handler fan-out, so the
 * server's WS streaming and snapshot-dependent routes run offline.
 */
export function fakeFeed(initial: SessionSnapshot | null = null): HerdrEventFeed & FakeFeedExtras {
  let snapshot = initial;
  const handlers = new Set<(message: FeedMessage) => void>();
  return {
    get snapshot(): SessionSnapshot | null {
      return snapshot;
    },
    setSnapshot(next) {
      snapshot = next;
    },
    onMessage(handler) {
      handlers.add(handler);
      return () => {
        handlers.delete(handler);
      };
    },
    removeMessage(handler) {
      handlers.delete(handler);
    },
    emit(message) {
      for (const handler of handlers) handler(message);
    },
    start: async () => {},
    stop: async () => {},
  } as unknown as HerdrEventFeed & FakeFeedExtras;
}
