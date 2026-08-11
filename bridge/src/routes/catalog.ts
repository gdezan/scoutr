import { canonicalPath } from "../dirs.js";
import type { SessionSnapshot } from "../herdr/types.js";
import { controlSession, launchStoredSession } from "../sessions.js";
import {
  deleteStoredSession,
  listSessionCatalog,
  renameStoredSession,
  resolveCatalogSessionPath,
  SessionCatalogError,
  sessionCatalogRoots,
  type ActiveSessionRef,
} from "../session-catalog.js";
import { backendForAgentSessionInfo } from "../agents/registry.js";
import { deriveAgentCards, type AgentCard } from "./agents.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const catalogRoutes: Route[] = [
  { method: "GET", path: "/api/session-catalog", handle: listCatalog },
  { method: "POST", path: "/api/session-catalog/:action", handle: storedSessionAction },
];

const CATALOG_ACTIONS = new Set(["resume", "fork", "rename", "delete"]);

async function listCatalog(ctx: RouteContext): Promise<RouteResult> {
  const snapshot = ctx.deps.feed.snapshot as SessionSnapshot | null;
  const active = await activeSessionRefs(snapshot);
  try {
    const limitValue = ctx.query.get("limit");
    return {
      status: 200,
      body: {
        ok: true,
        ...(await listSessionCatalog({
          roots: sessionCatalogRoots(),
          active,
          query: ctx.query.get("q") ?? undefined,
          limit: limitValue === null ? undefined : Number(limitValue),
        })),
      },
    };
  } catch (error) {
    if (error instanceof SessionCatalogError) {
      return { status: error.status, body: { ok: false, error: error.message } };
    }
    console.error("session catalog failed", error);
    return { status: 500, body: { ok: false, error: "session catalog unavailable" } };
  }
}

async function storedSessionAction(ctx: RouteContext): Promise<RouteResult> {
  const action = ctx.params.action ?? "";
  if (!CATALOG_ACTIONS.has(action)) {
    // The old if-chain only matched the four verbs; anything else fell
    // through to the 404 fallback.
    return { status: 404, body: { ok: false, error: "not found" } };
  }
  const body = ctx.body;
  if (typeof body.path !== "string" || !body.path) {
    return { status: 400, body: { ok: false, error: "path is required" } };
  }
  const { path: target } = await resolveCatalogSessionPath(body.path);
  const snapshot = ctx.deps.feed.snapshot as SessionSnapshot | null;
  // Id-kind session references (live claude panes) resolve through their
  // backend, so resume/rename/delete see the running session as active
  // instead of launching a duplicate workspace or unlinking a live transcript.
  const active = snapshot ? await findActiveCard(snapshot, target) : undefined;

  if (action === "resume" && active) {
    return { status: 200, body: { ok: true, workspaceId: active.workspaceId, paneId: active.paneId } };
  }
  if (action === "resume" || action === "fork") {
    const created = await launchStoredSession(ctx.deps.herdr, {
      path: target,
      mode: action,
    });
    return { status: 201, body: { ok: true, ...created } };
  }
  if (action === "rename") {
    if (typeof body.text !== "string") throw new SessionCatalogError("name is required");
    if (active) {
      await controlSession(ctx.deps.herdr, { paneId: active.paneId, action: "rename", text: body.text });
    } else {
      await renameStoredSession(target, body.text);
    }
    return { status: 200, body: { ok: true } };
  }
  if (active) throw new SessionCatalogError("close the active session before deleting it", 409);
  await deleteStoredSession(target);
  return { status: 200, body: { ok: true } };
}

/**
 * The transcript path behind a live card. Path-kind references come straight
 * from the pane; id-kind references (claude) are resolved through the owning
 * backend so live sessions are still recognized.
 */
async function resolveCardSessionPath(card: AgentCard, snapshot: SessionSnapshot): Promise<string | undefined> {
  if (card.sessionPath) return card.sessionPath;
  const agent = snapshot.agents.find((candidate) => candidate.pane_id === card.paneId);
  const backend = agent ? backendForAgentSessionInfo(agent.agent_session) : null;
  if (!backend || !agent?.agent_session) return undefined;
  return (await backend.resolveSessionPath(agent.agent_session, agent.cwd ?? undefined).catch(() => null)) ?? undefined;
}

/** The live card whose resolved transcript path equals `target`, if any. */
async function findActiveCard(snapshot: SessionSnapshot, target: string): Promise<AgentCard | undefined> {
  for (const card of deriveAgentCards(snapshot)) {
    const path = await resolveCardSessionPath(card, snapshot);
    if (path && canonicalPath(path) === target) return card;
  }
  return undefined;
}

/**
 * Active pane refs for the catalog join. Id-kind references (claude) are
 * resolved through the owning backend so live sessions still join as active.
 */
async function activeSessionRefs(snapshot: SessionSnapshot | null): Promise<ActiveSessionRef[]> {
  if (!snapshot) return [];
  const refs: ActiveSessionRef[] = [];
  for (const card of deriveAgentCards(snapshot)) {
    const path = await resolveCardSessionPath(card, snapshot);
    if (!path) continue;
    refs.push({
      path,
      paneId: card.paneId,
      workspaceId: card.workspaceId,
      status: card.status,
      title: card.title,
    });
  }
  return refs;
}
