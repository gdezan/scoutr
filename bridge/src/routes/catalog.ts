import { canonicalPath } from "../dirs.js";
import type { SessionSnapshot } from "../herdr/types.js";
import { controlSession, launchStoredSession } from "../sessions.js";
import {
  deleteStoredSession,
  listSessionCatalog,
  renameStoredSession,
  resolveCatalogSessionPath,
  SessionCatalogError,
} from "../session-catalog.js";
import { deriveAgentCards } from "./agents.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const catalogRoutes: Route[] = [
  { method: "GET", path: "/api/session-catalog", handle: listCatalog },
  { method: "POST", path: "/api/session-catalog/:action", handle: storedSessionAction },
];

const CATALOG_ACTIONS = new Set(["resume", "fork", "rename", "delete"]);

async function listCatalog(ctx: RouteContext): Promise<RouteResult> {
  const snapshot = ctx.deps.feed.snapshot as SessionSnapshot | null;
  const active = snapshot
    ? deriveAgentCards(snapshot, (paneId) => ctx.deps.tracker.since(paneId)).flatMap((card) =>
        card.sessionPath
          ? [{
              path: card.sessionPath,
              paneId: card.paneId,
              workspaceId: card.workspaceId,
              status: card.status,
              title: card.title,
            }]
          : [],
      )
    : [];
  try {
    const limitValue = ctx.query.get("limit");
    return {
      status: 200,
      body: {
        ok: true,
        ...(await listSessionCatalog({
          root: ctx.deps.sessionCatalogRoot,
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
  const target = await resolveCatalogSessionPath(body.path, ctx.deps.sessionCatalogRoot);
  const snapshot = ctx.deps.feed.snapshot as SessionSnapshot | null;
  const active = snapshot
    ? deriveAgentCards(snapshot).find((card) => card.sessionPath && canonicalPath(card.sessionPath) === target)
    : undefined;

  if (action === "resume" && active) {
    return { status: 200, body: { ok: true, workspaceId: active.workspaceId, paneId: active.paneId } };
  }
  if (action === "resume" || action === "fork") {
    const created = await launchStoredSession(ctx.deps.herdr, {
      path: target,
      mode: action,
      sessionRoot: ctx.deps.sessionCatalogRoot,
    });
    return { status: 201, body: { ok: true, ...created } };
  }
  if (action === "rename") {
    if (typeof body.text !== "string") throw new SessionCatalogError("name is required");
    if (active) {
      await controlSession(ctx.deps.herdr, { paneId: active.paneId, action: "rename", text: body.text });
    } else {
      await renameStoredSession(target, body.text, ctx.deps.sessionCatalogRoot);
    }
    return { status: 200, body: { ok: true } };
  }
  if (active) throw new SessionCatalogError("close the active session before deleting it", 409);
  await deleteStoredSession(target, ctx.deps.sessionCatalogRoot);
  return { status: 200, body: { ok: true } };
}
