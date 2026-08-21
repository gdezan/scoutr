import type { BoardDetailCache } from "../board-detail.js";
import type { BoardRepoSummaryCache } from "../board-repo-summary.js";
import type { SessionSnapshot } from "../herdr/types.js";
import { backendForAgentSessionInfo, getBackendOrNull, knownBackends } from "../agents/registry.js";
import {
  descriptorForLiveAgent,
  keyForAgent,
  type SessionDescriptor,
} from "../session-model.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const agentsRoutes: Route[] = [
  { method: "GET", path: "/api/agents", handle: agents },
  { method: "GET", path: "/api/agents/kinds", handle: agentKinds },
];

/** Build canonical descriptors for every current Herdr agent. */
export async function deriveSessionDescriptors(
  snapshot: SessionSnapshot,
  statusSince: (paneId: string) => number | undefined = () => undefined,
  cache?: BoardDetailCache,
): Promise<SessionDescriptor[]> {
  return Promise.all((snapshot.agents ?? []).map(async (agent) => {
    const backend = backendForAgentSessionInfo(agent.agent_session) ?? getBackendOrNull(agent.agent);
    const key = backend && agent.agent_session
      ? await keyForAgent(backend, agent.agent_session, agent.cwd ?? undefined)
      : null;
    const detail = key && cache ? await cache.detailFor(key.path).catch(() => null) : null;
    return descriptorForLiveAgent(agent, key, detail, statusSince(agent.pane_id));
  }));
}

/**
 * Attach deterministic repo summaries to Done cards, best-effort. Only Done
 * agents with a cwd incur git work; a summary failure degrades that one card
 * to no summary instead of failing the Board response.
 */
export async function attachDoneSummaries(
  sessions: SessionDescriptor[],
  cache: BoardRepoSummaryCache,
): Promise<SessionDescriptor[]> {
  return Promise.all(sessions.map(async (session) => {
    if (session.live?.status !== "done" || !session.cwd) return session;
    const doneSummary = await cache.summaryFor(session.cwd).catch(() => null);
    return doneSummary ? { ...session, doneSummary } : session;
  }));
}

async function agents(ctx: RouteContext): Promise<RouteResult> {
  const current = ctx.deps.feed.snapshot;
  if (!current) {
    return { status: 503, body: { ok: false, error: "no herdr snapshot yet" } };
  }
  const sessions = await deriveSessionDescriptors(
    current,
    (paneId) => ctx.deps.tracker.since(paneId),
    ctx.deps.boardDetail,
  );
  const enriched = await attachDoneSummaries(sessions, ctx.deps.boardRepoSummary);
  return { status: 200, body: { ok: true, agents: enriched } };
}

async function agentKinds(_ctx: RouteContext): Promise<RouteResult> {
  return {
    status: 200,
    body: {
      ok: true,
      kinds: knownBackends().map((backend) => ({
        id: backend.id,
        displayName: backend.displayName,
        capabilities: [...backend.capabilities],
        hasModelCatalog: backend.hasModelCatalog,
        hasSlashCommands: backend.hasSlashCommands,
      })),
    },
  };
}
