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
 * Attach deterministic repo summaries to Board cards, best-effort. Only live
 * agents with a cwd incur git work; a summary failure degrades that one card
 * to no summary instead of failing the Board response. Done cards carry the
 * final facts in `doneSummary`; every other status carries the last
 * TTL-bounded snapshot in `liveSummary`.
 */
export async function attachRepoSummaries(
  sessions: SessionDescriptor[],
  cache: BoardRepoSummaryCache,
): Promise<SessionDescriptor[]> {
  return Promise.all(sessions.map(async (session) => {
    if (!session.live || !session.cwd) return session;
    const summary = await cache.summaryFor(session.cwd).catch(() => null);
    if (!summary) return session;
    return session.live.status === "done"
      ? { ...session, doneSummary: summary }
      : { ...session, liveSummary: summary };
  }));
}

/**
 * Give every open ask the background its transcript is still holding back.
 *
 * Session reads are file-bound on purpose, and Claude keeps the prose it
 * wrote above an ask in the pane alone until the round resolves (ADR 0012).
 * This poll is the one that already holds both halves — the pane and the
 * transcript path — so the pane read happens here and the result reaches Chat
 * through the sidecar the card is already served from. Chat polls this
 * endpoint immediately before the session read, so the background arrives
 * with the card rather than a tick behind it.
 *
 * At most one pane read per ask: the backend records that it looked, whether
 * or not it found anything.
 */
async function captureAskContexts(
  ctx: RouteContext,
  sessions: readonly SessionDescriptor[],
): Promise<void> {
  await Promise.all(sessions.map(async (session) => {
    const paneId = session.live?.paneId;
    const path = session.key?.path;
    if (!paneId || !path || session.attention?.kind !== "ask") return;
    await getBackendOrNull(session.agentKind)?.captureAskContext?.(ctx.deps.herdr, paneId, path);
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
  await captureAskContexts(ctx, sessions);
  const enriched = await attachRepoSummaries(sessions, ctx.deps.boardRepoSummary);
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
