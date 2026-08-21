import type { SessionSnapshot } from "../herdr/types.js";
import { controlSession, launchStoredSession } from "../sessions.js";
import {
  deleteStoredSession,
  listSessionCatalog,
  renameStoredSession,
  resolveCatalogSessionKey,
  SessionCatalogError,
  sessionCatalogRoots,
  type ActiveSessionRef,
} from "../session-catalog.js";
import { deriveSessionDescriptors } from "./agents.js";
import type { SessionDescriptor } from "../session-model.js";
import type { Route, RouteContext, RouteResult } from "./types.js";
import * as v from "valibot";

export const catalogRoutes: Route[] = [
  { method: "GET", path: "/api/session-catalog", handle: listCatalog },
  { method: "POST", path: "/api/session-catalog/:action", handle: storedSessionAction },
];

const CATALOG_ACTIONS = new Set(["resume", "fork", "rename", "delete"]);

const catalogActionBodySchema = v.looseObject({
  key: v.object({ agentKind: v.string(), path: v.string() }),
  text: v.optional(v.string()),
});

async function listCatalog(ctx: RouteContext): Promise<RouteResult> {
  const snapshot = ctx.deps.feed.snapshot;
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
    return { status: 404, body: { ok: false, error: "not found" } };
  }
  const parsed = v.safeParse(catalogActionBodySchema, ctx.body);
  if (!parsed.success) {
    return { status: 400, body: { ok: false, error: parsed.issues[0]?.message ?? "invalid catalog action body" } };
  }
  const body = parsed.output;
  const { path: target } = await resolveCatalogSessionKey(body.key);
  const snapshot = ctx.deps.feed.snapshot;
  // Id-kind session references (live claude panes) resolve through their
  // backend, so resume/rename/delete see the running session as active
  // instead of launching a duplicate workspace or unlinking a live transcript.
  const active = snapshot ? await findActiveSession(snapshot, target) : undefined;

  if (action === "resume" && active) {
    return {
      status: 200,
      body: {
        ok: true,
        workspaceId: active.live?.workspaceId,
        paneId: active.live?.paneId,
      },
    };
  }
  if (action === "resume" || action === "fork") {
    const created = await launchStoredSession(ctx.deps.herdr, {
      path: target,
      mode: action,
    });
    return { status: 201, body: { ok: true, ...created } };
  }
  if (action === "rename") {
    if (body.text === undefined) throw new SessionCatalogError("name is required");
    if (active) {
      await controlSession(ctx.deps.herdr, { paneId: active.live!.paneId, action: "rename", text: body.text });
    } else {
      await renameStoredSession(target, body.text);
    }
    return { status: 200, body: { ok: true } };
  }
  if (active) throw new SessionCatalogError("close the active session before deleting it", 409);
  await deleteStoredSession(target);
  return { status: 200, body: { ok: true } };
}

/** The live descriptor whose canonical transcript key equals `target`, if any. */
async function findActiveSession(snapshot: SessionSnapshot, target: string): Promise<SessionDescriptor | undefined> {
  return (await deriveSessionDescriptors(snapshot)).find((session) => session.key?.path === target);
}

/**
 * Active pane refs for the catalog join. Id-kind references (claude) are
 * resolved through the owning backend so live sessions still join as active.
 */
async function activeSessionRefs(snapshot: SessionSnapshot | null): Promise<ActiveSessionRef[]> {
  if (!snapshot) return [];
  const refs: ActiveSessionRef[] = [];
  for (const session of await deriveSessionDescriptors(snapshot)) {
    if (!session.key || !session.live) continue;
    refs.push({
      path: session.key.path,
      paneId: session.live.paneId,
      workspaceId: session.live.workspaceId,
      tabId: session.live.tabId,
      status: session.live.status,
      statusSinceMs: session.live.statusSinceMs ?? undefined,
      title: session.title,
    });
  }
  return refs;
}
