import { canonicalPath } from "../dirs.js";
import type { SessionSnapshot } from "../herdr/types.js";
import { gitRepoRoot, reviewArtifacts, reviewDiff, reviewOverview, ReviewError } from "../review.js";
import { listSessionCatalog, sessionCatalogRoots } from "../session-catalog.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const reviewRoutes: Route[] = [
  { method: "GET", path: "/api/repo", handle: repoOverview },
  { method: "GET", path: "/api/repo/diff", handle: repoDiff },
  { method: "GET", path: "/api/repo/artifacts", handle: repoArtifacts },
];

/**
 * Distinct realpaths of every agent workspace currently tracked by the
 * bridge. These are the implicit review roots for fix 5: the user already
 * authorizes an agent to run in each, so read-only git review of the same
 * repo adds no privilege, and it removes the 403 that blocked reviewing
 * real repos without COCKPIT_REPO_ROOTS.
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
  const seen = new Set<string>();
  const roots: string[] = [];
  const add = async (cwd: string | null | undefined) => {
    if (!cwd) return;
    const repoRoot = await gitRepoRoot(cwd);
    if (!repoRoot) return;
    const canonical = canonicalPath(repoRoot);
    if (!seen.has(canonical)) {
      seen.add(canonical);
      roots.push(canonical);
    }
  };
  if (snapshot) {
    for (const agent of snapshot.agents) {
      await add(agent.cwd);
      await add(agent.foreground_cwd);
    }
  }
  for (const cwd of catalogCwds) await add(cwd);
  return roots;
}

async function reviewRoots(ctx: RouteContext): Promise<string[]> {
  // Live agent workspaces AND any bridge-known session workspace are
  // implicitly allowed: the user already authorized an agent to run in
  // that cwd (active or historical), so read-only git review adds no
  // privilege. Each cwd is narrowed to its git repo root so a
  // cwd=/home/gdezan session never makes the whole home reviewable.
  // COCKPIT_REPO_ROOTS still works and is joined in.
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
