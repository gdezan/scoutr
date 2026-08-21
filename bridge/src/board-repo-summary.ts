import {
  gitHasHead,
  gitRepoRoot,
  reviewDiff,
  reviewOverview,
  reviewUnbornOverview,
  type ReviewDiffResult,
  type ReviewOverview,
} from "./review.js";

/**
 * Deterministic repo evidence for a Board card, Done or live.
 *
 * Every field maps to a git fact; nothing here is a quality judgment. The UI
 * labels the facts (branch, dirty/clean, change counts) and never concludes
 * "safe to ship" — see .plans/p1-done-ship-readiness-summary.md. On a Done
 * card the facts are final; on a live card they are only as fresh as the last
 * TTL-bounded computation, so UI labels them as of that moment.
 */
export interface RepoSummary {
  repoRoot: string;
  branch: string | null;
  /** Upstream tracking branch when known; ahead/behind are meaningful only then. */
  upstream: string | null;
  ahead: number;
  behind: number;
  /** Union of paths from status and diff stat, so untracked files count. */
  changedFiles: number;
  additions: number;
  deletions: number;
  /** Porcelain status has entries (includes untracked files). */
  dirty: boolean;
  statusTruncated: boolean;
  diffTruncated: boolean;
}

/** Pure derivation from already-fetched review primitives. */
export function deriveRepoSummary(
  repoRoot: string,
  overview: ReviewOverview,
  diff: ReviewDiffResult,
): RepoSummary {
  const paths = new Set<string>();
  for (const entry of overview.status) {
    // Porcelain renames/copies render as "old -> new"; both sides are real
    // paths. Any other entry is one path verbatim, even when the filename
    // itself contains " -> ".
    const isRenameOrCopy = entry.code.includes("R") || entry.code.includes("C");
    for (const part of isRenameOrCopy ? entry.path.split(" -> ") : [entry.path]) {
      if (part) paths.add(part);
    }
  }
  let additions = 0;
  let deletions = 0;
  for (const stat of diff.stat) {
    paths.add(stat.path);
    additions += stat.additions;
    deletions += stat.deletions;
  }
  return {
    repoRoot,
    branch: overview.branch,
    upstream: overview.upstream,
    ahead: overview.ahead,
    behind: overview.behind,
    changedFiles: paths.size,
    additions,
    deletions,
    dirty: overview.status.length > 0,
    statusTruncated: overview.statusTruncated,
    diffTruncated: diff.truncated,
  };
}

interface CacheEntry {
  at: number;
  summary: RepoSummary | null;
}

interface RootEntry {
  at: number;
  root: string | null;
}

const MEMO_CAP = 64;

/**
 * Shared, TTL-bounded repo summaries for Board cards, Done or live.
 *
 * The 3-second Board poll must not spawn a git subprocess storm, so work is
 * memoized by canonical repo root with a short TTL and concurrent callers
 * share one in-flight computation. Multiple agents in one repo cost one
 * summary per TTL window — which also bounds how fresh a live card's facts
 * can be. Failures degrade to null (cached for the same TTL) and never
 * propagate to the route.
 */
export class BoardRepoSummaryCache {
  private readonly roots = new Map<string, RootEntry>();
  private readonly rootInFlight = new Map<string, Promise<string | null>>();
  private readonly entries = new Map<string, CacheEntry>();
  private readonly inFlight = new Map<string, Promise<RepoSummary | null>>();

  constructor(
    private readonly options: { ttlMs?: number; now?: () => number } = {},
  ) {}

  private get ttlMs(): number {
    return this.options.ttlMs ?? 8_000;
  }

  private now(): number {
    return (this.options.now ?? Date.now)();
  }

  async summaryFor(cwd: string): Promise<RepoSummary | null> {
    if (!cwd) return null;
    const root = await this.rootFor(cwd);
    if (!root) return null;

    const cached = this.entries.get(root);
    if (cached && this.now() - cached.at < this.ttlMs) return cached.summary;

    const pending = this.inFlight.get(root);
    if (pending) return pending;

    const computation = this.compute(root);
    this.inFlight.set(root, computation);
    try {
      return await computation;
    } finally {
      this.inFlight.delete(root);
    }
  }

  /** Resolve (and memoize) the canonical repo root for a session cwd. */
  private async rootFor(cwd: string): Promise<string | null> {
    const cached = this.roots.get(cwd);
    if (cached && this.now() - cached.at < this.ttlMs) return cached.root;
    // Concurrent callers for the same cwd share one root resolution, so a
    // single poll tick cannot fan out one `rev-parse` per Done card.
    const pending = this.rootInFlight.get(cwd);
    if (pending) return pending;
    const computation = gitRepoRoot(cwd)
      .catch(() => null)
      .then((root) => {
        this.roots.set(cwd, { at: this.now(), root });
        this.evict(this.roots);
        return root;
      })
      .finally(() => this.rootInFlight.delete(cwd));
    this.rootInFlight.set(cwd, computation);
    return computation;
  }

  /**
   * One bounded read-only pass over the repo. The resolved root is passed as
   * its own extra allowed root — it came from a live agent cwd, which review
   * already treats as user-authorized, so no route-layer catalog scan is needed.
   * A missing HEAD (fresh repo) degrades to status-only facts via the unborn
   * overview; a failing diff with a live HEAD omits the whole summary rather
   * than presenting absent diff evidence as exact "+0 −0".
   */
  private async compute(root: string): Promise<RepoSummary | null> {
    const at = this.now();
    const summary = await this.summarize(root).catch(() => null);
    this.entries.set(root, { at, summary });
    this.evict(this.entries);
    return summary;
  }

  private async summarize(root: string): Promise<RepoSummary | null> {
    const hasHead = await gitHasHead(root);
    const [overview, diff] = await Promise.all([
      hasHead ? reviewOverview(root, [root]) : reviewUnbornOverview(root, [root]),
      hasHead ? reviewDiff(root, "HEAD", "working", [root]).catch(() => null) : null,
    ]);
    if (!overview || (hasHead && !diff)) return null;
    // Unborn HEAD: nothing is committed, so an empty diff stat is a fact,
    // not an invention.
    return deriveRepoSummary(root, overview, diff ?? { stat: [], truncated: false });
  }

  private evict(map: Map<string, { at: number }>): void {
    while (map.size > MEMO_CAP) {
      const oldest = map.keys().next().value;
      if (oldest === undefined) return;
      map.delete(oldest);
    }
  }

  /** Forget entries whose repo root is no longer live (called on pane close). */
  prune(liveRoots: ReadonlySet<string>): void {
    for (const root of this.entries.keys()) {
      if (!liveRoots.has(root)) this.entries.delete(root);
    }
    for (const cwd of this.roots.keys()) {
      if (![...liveRoots].some((root) => cwd === root || cwd.startsWith(root + "/"))) {
        this.roots.delete(cwd);
      }
    }
  }

  get size(): number {
    return this.entries.size;
  }
}
