import { readLiveOutput } from "../live-output.js";
import { cleanActivity } from "../board-detail.js";
import type { BoardDetailCache } from "../board-detail.js";
import type { SessionSnapshot, AgentInfo } from "../herdr/types.js";
import { backendForAgentSessionInfo, getBackendOrNull, knownBackends } from "../agents/registry.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const agentsRoutes: Route[] = [
  { method: "GET", path: "/api/agents", handle: agents },
  { method: "GET", path: "/api/agents/kinds", handle: agentKinds },
  { method: "GET", path: "/api/agents/:paneId/read", handle: readAgentOutput },
];

export interface AgentCard {
  paneId: string;
  workspaceId: string;
  tabId: string;
  agent: string;
  /** Registry backend id (same as `agent` for known backends). */
  agentKind: string;
  /** Human-readable backend name (e.g. "Claude Code"). */
  displayName: string;
  /** Control actions the backend supports; the app gates its menus on this. */
  capabilities: string[];
  status: string;
  cwd?: string;
  title?: string;
  sessionPath?: string;
  terminalTitle?: string;
  blocked?: boolean;
  statusSinceMs?: number;
  /** Active model from the session file (bounded tail read). */
  model?: string | null;
  /** Latest meaningful transcript line (bounded). */
  latestActivity?: string | null;
  /** Epoch ms of the latest activity record. */
  latestActivityAtMs?: number | null;
}

export function deriveAgentCards(
  snapshot: SessionSnapshot,
  statusSince: (paneId: string) => number | undefined = () => undefined,
): AgentCard[] {
  const cards: AgentCard[] = [];
  for (const agent of snapshot.agents ?? []) {
    const backend = getBackendOrNull(agent.agent);
    const card: AgentCard = {
      paneId: agent.pane_id,
      workspaceId: agent.workspace_id,
      tabId: agent.tab_id,
      agent: agent.agent,
      agentKind: backend?.id ?? agent.agent,
      displayName: backend?.displayName ?? agent.agent,
      capabilities: backend ? [...backend.capabilities] : [],
      status: agent.agent_status,
      cwd: agent.cwd ?? undefined,
      title: agent.terminal_title ?? undefined,
      terminalTitle: agent.terminal_title_stripped ?? undefined,
      blocked: agent.agent_status === "blocked",
    };
    const since = statusSince(agent.pane_id);
    if (since !== undefined) card.statusSinceMs = since;
    if (agent.agent_session?.kind === "path") card.sessionPath = agent.agent_session.value;
    cards.push(card);
  }
  return cards;
}

/** Cards enriched with bounded model + latest activity from their session files. */
export async function deriveAgentCardsWithDetail(
  snapshot: SessionSnapshot,
  statusSince: (paneId: string) => number | undefined,
  cache: BoardDetailCache,
): Promise<AgentCard[]> {
  const cards = deriveAgentCards(snapshot, statusSince);
  await Promise.all(cards.map(async (card) => {
    const agentInfo = snapshot.agents.find((candidate) => candidate.pane_id === card.paneId);
    let sessionPath = card.sessionPath;
    // Agents that report an id-kind session reference (e.g. claude) need the
    // backend to resolve the reference to a transcript path first.
    if (!sessionPath && agentInfo?.agent_session) {
      const backend = backendForAgentSessionInfo(agentInfo.agent_session);
      if (backend) {
        sessionPath = (await backend.resolveSessionPath(agentInfo.agent_session, agentInfo.cwd ?? undefined).catch(() => null)) ?? undefined;
        if (sessionPath) card.sessionPath = sessionPath;
      }
    }
    if (!sessionPath) return;
    const detail = await cache.detailFor(sessionPath).catch(() => null);
    // Stable shape: fields always present on cards with a session path,
    // values may be null (a live agent whose session file is missing/empty
    // — e.g. a just-launched session — must still produce well-typed cards).
    card.model = detail?.model ?? null;
    card.latestActivity = detail?.latestActivity ? cleanActivity(detail.latestActivity) : null;
    card.latestActivityAtMs = detail?.latestActivityAtMs ?? null;
  }));
  return cards;
}

async function agents(ctx: RouteContext): Promise<RouteResult> {
  const current = ctx.deps.feed.snapshot as SessionSnapshot | null;
  if (!current) {
    return { status: 503, body: { ok: false, error: "no herdr snapshot yet" } };
  }
  const cards = await deriveAgentCardsWithDetail(
    current,
    (paneId) => ctx.deps.tracker.since(paneId),
    ctx.deps.boardDetail,
  );
  return { status: 200, body: { ok: true, agents: cards } };
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

async function readAgentOutput(ctx: RouteContext): Promise<RouteResult> {
  let paneId: string;
  try {
    paneId = decodeURIComponent(ctx.params.paneId ?? "");
  } catch {
    return { status: 400, body: { ok: false, error: "invalid pane id" } };
  }
  const output = await readLiveOutput(ctx.deps.herdr, paneId, ctx.query.get("lines"));
  return { status: 200, body: { ok: true, output } };
}
