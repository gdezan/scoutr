import { canonicalPath } from "../dirs.js";
import type { SessionSnapshot } from "../herdr/types.js";
import {
  gitRepoRoot,
  reviewArtifacts,
  reviewDiff,
  reviewFileContent,
  reviewFileDiff,
  reviewOverview,
  ReviewError,
} from "../review.js";
import { listSessionCatalog, sessionCatalogRoots } from "../session-catalog.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const reviewRoutes: Route[] = [
  { method: "GET", path: "/api/repo", handle: repoOverview },
  { method: "GET", path: "/api/repo/diff", handle: repoDiff },
  { method: "GET", path: "/api/repo/diff/file", handle: repoFileDiff },
  { method: "GET", path: "/api/repo/file", handle: repoFile },
  { method: "GET", path: "/api/repo/artifacts", handle: repoArtifacts },
];

/**
 * A cwd's git repository root does not move while the workspace exists, so
 * git resolution is memoized per canonical cwd. Each entry is the result of
 * a git subprocess, which the catalog can otherwise spawn hundreds of times
 * per scan.
 */
const GIT_ROOT_MEMO_CAP = 500;
const gitRootMemo = new Map<string, string | null>();

function gitRepoRootCached(cwd: string): Promise<string | null> {
  const canonical = canonicalPath(cwd);
  const cached = gitRootMemo.get(canonical);
  if (cached !== undefined) return Promise.resolve(cached);
  return gitRepoRoot(canonical).then((root) => {
    gitRootMemo.set(canonical, root);
    if (gitRootMemo.size > GIT_ROOT_MEMO_CAP) {
      const oldest = gitRootMemo.keys().next().value;
      if (oldest !== undefined) gitRootMemo.delete(oldest);
    }
    return root;
  });
}

/**
 * Distinct realpaths of every agent workspace currently tracked by the
 * bridge. These are the implicit review roots for fix 5: the user already
 * authorizes an agent to run in each, so read-only git review of the same
 * repo adds no privilege, and it removes the 403 that blocked reviewing
 * real repos without SCOUTR_REPO_ROOTS.
 *
 * Least-privilege narrowing: each session cwd is resolved to its git
 * repository root (git rev-parse --show-toplevel); non-repo cwds — e.g.
 * $HOME, scratch dirs — contribute nothing, so a cwd=/home/gdezan agent
 * never makes the whole home directory reviewable.
 */
export async function sessionWorkspaceRoots(
  snapshot: SessionSnapshot | null,
  catalogCwds: string[] = [],
): Promise<string[]> {
  // Collect every candidate cwd first, then resolve the repo roots in
  // parallel — sequential awaits made each cwd wait on the previous git
  // subprocess, and the review screen can present hundreds of sessions.
  const cwds: string[] = [];
  if (snapshot) {
    for (const agent of snapshot.agents) {
      if (agent.cwd) cwds.push(agent.cwd);
      if (agent.foreground_cwd) cwds.push(agent.foreground_cwd);
    }
  }
  cwds.push(...catalogCwds);
  const roots = await Promise.all(cwds.map((cwd) => gitRepoRootCached(cwd)));
  const seen = new Set<string>();
  const deduped: string[] = [];
  for (const root of roots) {
    if (!root) continue;
    const canonical = canonicalPath(root);
    if (!seen.has(canonical)) {
      seen.add(canonical);
      deduped.push(canonical);
    }
  }
  return deduped;
}

/**
 * The implicit-root derivation (full catalog scan + per-cwd git resolution)
 * is cached for a short TTL because the Review screen fires all three routes
 * on open. The TTL must stay ≤ a few seconds: the root set is a security
 * allow-list, and a stale entry would keep a closed workspace reviewable.
 */
const REVIEW_ROOTS_TTL_MS = 2_000;
export { REVIEW_ROOTS_TTL_MS };
let reviewRootsCache: string[] | null = null;
let reviewRootsCachedAt = 0;
/** In-flight computation shared by concurrent first-wave requests. */
let reviewRootsInFlight: Promise<string[]> | null = null;

async function reviewRoots(ctx: RouteContext): Promise<string[]> {
  const now = Date.now();
  if (reviewRootsCache && now - reviewRootsCachedAt < REVIEW_ROOTS_TTL_MS) {
    return reviewRootsCache;
  }
  if (!reviewRootsInFlight) {
    reviewRootsInFlight = computeReviewRoots(ctx)
      .then((roots) => {
        reviewRootsCache = roots;
        reviewRootsCachedAt = Date.now();
        return roots;
      })
      .finally(() => {
        reviewRootsInFlight = null;
      });
  }
  return reviewRootsInFlight;
}

async function computeReviewRoots(ctx: RouteContext): Promise<string[]> {
  // Live agent workspaces AND any bridge-known session workspace are
  // implicitly allowed: the user already authorized an agent to run in
  // that cwd (active or historical), so read-only git review adds no
  // privilege. Each cwd is narrowed to its git repo root so a
  // cwd=/home/gdezan session never makes the whole home reviewable.
  // SCOUTR_REPO_ROOTS still works and is joined in.
  let catalogCwds: string[] = [];
  try {
    const catalog = await listSessionCatalog({ roots: sessionCatalogRoots(), active: [] });
    catalogCwds = catalog.sessions.map((s) => s.cwd).filter((cwd): cwd is string => Boolean(cwd));
  } catch {
    // A catalog failure must not take down review; live roots still apply.
  }
  return sessionWorkspaceRoots(ctx.deps.feed.snapshot as SessionSnapshot | null, catalogCwds);
}

async function repoOverview(ctx: RouteContext): Promise<RouteResult> {
  const requestedPath = ctx.query.get("path") ?? "";
  try {
    return { status: 200, body: { ok: true, ...(await reviewOverview(requestedPath, await reviewRoots(ctx))) } };
  } catch (error) {
    return reviewError(error);
  }
}

async function repoDiff(ctx: RouteContext): Promise<RouteResult> {
  const requestedPath = ctx.query.get("path") ?? "";
  try {
    const base = ctx.query.get("base") ?? "HEAD";
    const kind = ctx.query.get("kind") === "commit" ? "commit" : "working";
    return {
      status: 200,
      body: { ok: true, ...(await reviewDiff(requestedPath, base, kind, await reviewRoots(ctx))) },
    };
  } catch (error) {
    return reviewError(error);
  }
}

async function repoFileDiff(ctx: RouteContext): Promise<RouteResult> {
  const requestedPath = ctx.query.get("path") ?? "";
  try {
    const base = ctx.query.get("base") ?? "HEAD";
    const kind = ctx.query.get("kind") === "commit" ? "commit" : "working";
    const file = ctx.query.get("file") ?? "";
    return {
      status: 200,
      body: { ok: true, ...(await reviewFileDiff(requestedPath, base, kind, file, await reviewRoots(ctx))) },
    };
  } catch (error) {
    return reviewError(error);
  }
}

async function repoFile(ctx: RouteContext): Promise<RouteResult> {
  const requestedPath = ctx.query.get("path") ?? "";
  try {
    const base = ctx.query.get("base") ?? "HEAD";
    const kind = ctx.query.get("kind") === "commit" ? "commit" : "working";
    const file = ctx.query.get("file") ?? "";
    return {
      status: 200,
      body: { ok: true, ...(await reviewFileContent(requestedPath, base, kind, file, await reviewRoots(ctx))) },
    };
  } catch (error) {
    return reviewError(error);
  }
}

async function repoArtifacts(ctx: RouteContext): Promise<RouteResult> {
  const requestedPath = ctx.query.get("path") ?? "";
  try {
    return { status: 200, body: { ok: true, ...reviewArtifacts(requestedPath, await reviewRoots(ctx)) } };
  } catch (error) {
    return reviewError(error);
  }
}

function reviewError(error: unknown): RouteResult {
  const status = error instanceof ReviewError ? error.status : 500;
  return {
    status,
    body: { ok: false, error: error instanceof Error ? error.message : String(error) },
  };
}
