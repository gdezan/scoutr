import * as v from "valibot";
import { resolveAllowedDir } from "../dirs.js";
import { BridgeError } from "../errors.js";
import type { SessionSnapshot } from "../herdr/types.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

export const terminalRoutes: Route[] = [
  { method: "POST", path: "/api/terminal/hierarchy", handle: terminalHierarchy },
];

/**
 * Discriminated hierarchy commands (design contract, Slice 4). `operation`
 * selects the exact herdr call; `selectedPaneId` is the Android selection at
 * tap/confirmation time and only influences cwd fallback and post-mutation
 * selection — it never changes herdr desktop focus.
 */
type TerminalHierarchyCommand =
  | { operation: "create_tab"; workspaceId: string; selectedPaneId?: string }
  | { operation: "create_workspace"; cwd: string; label?: string; selectedPaneId?: string }
  | { operation: "rename_pane"; paneId: string; label: string; selectedPaneId?: string }
  | { operation: "rename_tab"; tabId: string; label: string; selectedPaneId?: string }
  | { operation: "rename_workspace"; workspaceId: string; label: string; selectedPaneId?: string }
  | { operation: "close_pane"; paneId: string; selectedPaneId?: string }
  | { operation: "close_tab"; tabId: string; selectedPaneId?: string; expectedPaneCount: number }
  | { operation: "close_workspace"; workspaceId: string; selectedPaneId?: string; expectedPaneCount: number };
type CloseTarget = { kind: "pane" | "tab" | "workspace"; targetId: string };
const OPERATIONS = new Set<unknown>([
  "create_tab",
  "create_workspace",
  "rename_pane",
  "rename_tab",
  "rename_workspace",
  "close_pane",
  "close_tab",
  "close_workspace",
]);

const terminalHierarchyBodySchema = v.looseObject({
  operation: v.optional(v.unknown()),
  workspaceId: v.optional(v.string()),
  cwd: v.optional(v.string()),
  label: v.optional(v.string()),
  paneId: v.optional(v.string()),
  tabId: v.optional(v.string()),
  selectedPaneId: v.optional(v.string()),
  expectedPaneCount: v.optional(v.number("expectedPaneCount must be a non-negative integer")),
});

interface TabCreateParams {
  workspace_id: string;
  cwd?: string;
  focus: boolean;
}

interface WorkspaceCreateParams {
  cwd: string;
  label?: string;
  focus: boolean;
}
const MAX_ID_LENGTH = 1024;
const MAX_LABEL_LENGTH = 200;

function badRequest(message: string): never {
  throw new BridgeError(message, 400);
}

function requireId(value: string | undefined, name: string): string {
  if (value === undefined || value.trim().length === 0) badRequest(`missing ${name}`);
  if (value.length > MAX_ID_LENGTH) badRequest(`${name} too long`);
  return value;
}

function requireLabel(value: string | undefined): string {
  if (value === undefined || value.trim().length === 0) badRequest("label must be a non-empty string");
  if (value.length > MAX_LABEL_LENGTH) badRequest("label too long");
  return value;
}

function optionalSelection(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  if (value.trim().length === 0) badRequest("selectedPaneId must be a string");
  return value;
}

function requirePaneCount(value: number | undefined): number {
  if (value === undefined || !Number.isInteger(value) || value < 0) {
    badRequest("expectedPaneCount must be a non-negative integer");
  }
  return value;
}

async function terminalHierarchy(ctx: RouteContext): Promise<RouteResult> {
  const parsed = v.safeParse(terminalHierarchyBodySchema, ctx.body);
  if (!parsed.success) badRequest(parsed.issues[0]?.message ?? "invalid hierarchy body");
  const body = parsed.output;
  const operation = body.operation;
  if (!OPERATIONS.has(operation)) badRequest(`unknown hierarchy operation: ${String(operation)}`);
  const selectedPaneId = optionalSelection(body.selectedPaneId);
  const herdr = ctx.deps.herdr;

  switch (operation) {
    case "create_tab": {
      const workspaceId = requireId(body.workspaceId, "workspaceId");
      const pre = await herdr.snapshot();
      if (!pre.workspaces.some((w) => w.workspace_id === workspaceId)) {
        throw new BridgeError("workspace not found", 404);
      }
      const cwd = resolveCreateTabCwd(pre, workspaceId, selectedPaneId);
      const params: TabCreateParams = { workspace_id: workspaceId, focus: false };
      if (cwd !== undefined) params.cwd = cwd;
      const created = await herdr.tabCreate(params);
      const post = await herdr.snapshot();
      return hierarchyResult(selectAfterMutation({ requested: selectedPaneId, pre, post, createdPaneId: created.root_pane?.pane_id }), post);
    }
    case "create_workspace": {
      if (body.cwd === undefined || body.cwd.trim().length === 0) badRequest("cwd must be a non-empty string");
      const label = body.label === undefined ? undefined : requireLabel(body.label);
      // Directory contract matches the existing picker: only paths inside home.
      const cwd = resolveAllowedDir(body.cwd.trim());
      const pre = await herdr.snapshot();
      const workspaceParams: WorkspaceCreateParams = { cwd, focus: false };
      if (label !== undefined) workspaceParams.label = label;
      const created = await herdr.workspaceCreate(workspaceParams);
      const post = await herdr.snapshot();
      return hierarchyResult(selectAfterMutation({ requested: selectedPaneId, pre, post, createdPaneId: created.root_pane?.pane_id }), post);
    }
    case "rename_pane": {
      const paneId = requireId(body.paneId, "paneId");
      const label = requireLabel(body.label);
      const pre = await herdr.snapshot();
      if (!pre.panes.some((p) => p.pane_id === paneId)) throw new BridgeError("pane not found", 404);
      await herdr.paneRename(paneId, label);
      const post = await herdr.snapshot();
      return hierarchyResult(selectAfterMutation({ requested: selectedPaneId, pre, post }), post);
    }
    case "rename_tab": {
      const tabId = requireId(body.tabId, "tabId");
      const label = requireLabel(body.label);
      const pre = await herdr.snapshot();
      if (!pre.tabs.some((t) => t.tab_id === tabId)) throw new BridgeError("tab not found", 404);
      await herdr.tabRename(tabId, label);
      const post = await herdr.snapshot();
      return hierarchyResult(selectAfterMutation({ requested: selectedPaneId, pre, post }), post);
    }
    case "rename_workspace": {
      const workspaceId = requireId(body.workspaceId, "workspaceId");
      const label = requireLabel(body.label);
      const pre = await herdr.snapshot();
      if (!pre.workspaces.some((w) => w.workspace_id === workspaceId)) throw new BridgeError("workspace not found", 404);
      await herdr.workspaceRename(workspaceId, label);
      const post = await herdr.snapshot();
      return hierarchyResult(selectAfterMutation({ requested: selectedPaneId, pre, post }), post);
    }
    case "close_pane": {
      const paneId = requireId(body.paneId, "paneId");
      const pre = await herdr.snapshot();
      if (!pre.panes.some((p) => p.pane_id === paneId)) throw new BridgeError("pane not found", 404);
      await herdr.paneClose(paneId);
      const post = await herdr.snapshot();
      return hierarchyResult(selectAfterMutation({ requested: selectedPaneId, pre, post, close: { kind: "pane", targetId: paneId } }), post);
    }
    case "close_tab": {
      const tabId = requireId(body.tabId, "tabId");
      const expectedPaneCount = requirePaneCount(body.expectedPaneCount);
      const pre = await herdr.snapshot();
      const tab = pre.tabs.find((t) => t.tab_id === tabId);
      if (!tab) throw new BridgeError("tab not found", 404);
      const currentCount = pre.panes.filter((p) => p.tab_id === tabId).length;
      if (currentCount !== expectedPaneCount) {
        return {
          status: 409,
          body: { ok: false, error: "tab pane count changed", id: tabId, name: tab.label, count: currentCount, expectedPaneCount },
        };
      }
      await herdr.tabClose(tabId);
      const post = await herdr.snapshot();
      return hierarchyResult(selectAfterMutation({ requested: selectedPaneId, pre, post, close: { kind: "tab", targetId: tabId } }), post);
    }
    case "close_workspace": {
      const workspaceId = requireId(body.workspaceId, "workspaceId");
      const expectedPaneCount = requirePaneCount(body.expectedPaneCount);
      const pre = await herdr.snapshot();
      const workspace = pre.workspaces.find((w) => w.workspace_id === workspaceId);
      if (!workspace) throw new BridgeError("workspace not found", 404);
      const currentCount = pre.panes.filter((p) => p.workspace_id === workspaceId).length;
      if (currentCount !== expectedPaneCount) {
        return {
          status: 409,
          body: { ok: false, error: "workspace pane count changed", id: workspaceId, name: workspace.label, count: currentCount, expectedPaneCount },
        };
      }
      await herdr.workspaceClose(workspaceId);
      const post = await herdr.snapshot();
      return hierarchyResult(selectAfterMutation({ requested: selectedPaneId, pre, post, close: { kind: "workspace", targetId: workspaceId } }), post);
    }
    default:
      badRequest(`unknown hierarchy operation: ${String(operation)}`);
  }
}

function hierarchyResult(selectedPaneId: string | null, snapshot: SessionSnapshot): RouteResult {
  return { status: 200, body: { ok: true, selectedPaneId, snapshot } };
}

/**
 * New-tab cwd precedence (settled UX contract): selected pane foreground
 * cwd/cwd, then another pane's cwd in the target workspace, then herdr's home
 * default (no cwd param at all).
 */
function resolveCreateTabCwd(pre: SessionSnapshot, workspaceId: string, selectedPaneId: string | undefined): string | undefined {
  if (selectedPaneId !== undefined) {
    const pane = pre.panes.find((candidate) => candidate.pane_id === selectedPaneId);
    const cwd = pane?.foreground_cwd ?? pane?.cwd;
    if (cwd) return cwd;
  }
  const other = pre.panes.find((candidate) => candidate.workspace_id === workspaceId && (candidate.foreground_cwd ?? candidate.cwd) != null);
  if (!other) return undefined;
  return other.foreground_cwd ?? other.cwd ?? undefined;
}

/**
 * Deterministic post-mutation selection over the pre-close catalog order and
 * the fresh snapshot (design contract, Slice 4):
 *  - a create reports its root pane when herdr provides one;
 *  - a requested selection missing from the pre-close catalog is stale: the
 *    fresh catalog decides (first pane);
 *  - closes that do not touch the selected pane preserve it while it survives,
 *    otherwise the empty selector (null);
 *  - closing the active pane picks next then previous pane in its old tab,
 *    then old workspace, then globally;
 *  - closing the active tab picks next then previous surviving tab in its old
 *    workspace, then globally, selecting that tab's first pane;
 *  - closing the active workspace picks next then previous surviving
 *    workspace, selecting its first pane.
 */
function selectAfterMutation(options: {
  requested: string | undefined;
  pre: SessionSnapshot;
  post: SessionSnapshot;
  close?: CloseTarget;
  createdPaneId?: string | undefined;
}): string | null {
  const { requested, pre, post, close, createdPaneId } = options;
  if (createdPaneId) return createdPaneId;

  const requestedValid = requested !== undefined && pre.panes.some((pane) => pane.pane_id === requested);
  if (!requestedValid) return firstPane(post);

  if (!close || !closeTouchesSelection(pre, requested, close)) {
    return post.panes.some((pane) => pane.pane_id === requested) ? requested : null;
  }
  return fallbackForActiveClose(pre, post, requested, close);
}

function closeTouchesSelection(pre: SessionSnapshot, selectedPaneId: string, close: CloseTarget): boolean {
  const pane = pre.panes.find((candidate) => candidate.pane_id === selectedPaneId);
  if (!pane) return false;
  switch (close.kind) {
    case "pane":
      return close.targetId === selectedPaneId;
    case "tab":
      return pane.tab_id === close.targetId;
    case "workspace":
      return pane.workspace_id === close.targetId;
  }
}

function fallbackForActiveClose(pre: SessionSnapshot, post: SessionSnapshot, selectedPaneId: string, close: CloseTarget): string | null {
  const pane = pre.panes.find((candidate) => candidate.pane_id === selectedPaneId);
  if (!pane) return null;
  const prePaneOrder = pre.panes.map((candidate) => candidate.pane_id);
  const alive = (paneId: string) => post.panes.some((candidate) => candidate.pane_id === paneId);

  if (close.kind === "pane") {
    const inTab = pre.panes
      .filter((candidate) => candidate.tab_id === pane.tab_id && candidate.pane_id !== selectedPaneId && alive(candidate.pane_id))
      .map((candidate) => candidate.pane_id);
    const inWorkspace = pre.panes
      .filter((candidate) => candidate.workspace_id === pane.workspace_id && candidate.pane_id !== selectedPaneId && alive(candidate.pane_id))
      .map((candidate) => candidate.pane_id);
    const global = pre.panes.filter((candidate) => candidate.pane_id !== selectedPaneId && alive(candidate.pane_id)).map((candidate) => candidate.pane_id);
    return (
      nextThenPrevious(inTab, prePaneOrder, selectedPaneId) ??
      nextThenPrevious(inWorkspace, prePaneOrder, selectedPaneId) ??
      nextThenPrevious(global, prePaneOrder, selectedPaneId)
    );
  }

  const preTabOrder = pre.tabs.map((candidate) => candidate.tab_id);
  const tabSurvivor = (candidate: { tab_id: string }) => post.tabs.some((survivor) => survivor.tab_id === candidate.tab_id);
  if (close.kind === "tab") {
    const inWorkspace = pre.tabs
      .filter((candidate) => candidate.workspace_id === pane.workspace_id && candidate.tab_id !== close.targetId && tabSurvivor(candidate))
      .map((candidate) => candidate.tab_id);
    const global = pre.tabs.filter((candidate) => candidate.tab_id !== close.targetId && tabSurvivor(candidate)).map((candidate) => candidate.tab_id);
    return (
      firstPaneOfTab(pre, post, nextThenPrevious(inWorkspace, preTabOrder, close.targetId)) ??
      firstPaneOfTab(pre, post, nextThenPrevious(global, preTabOrder, close.targetId))
    );
  }

  const preWorkspaceOrder = pre.workspaces.map((candidate) => candidate.workspace_id);
  const inOrder = pre.workspaces
    .filter((candidate) => candidate.workspace_id !== close.targetId && post.workspaces.some((survivor) => survivor.workspace_id === candidate.workspace_id))
    .map((candidate) => candidate.workspace_id);
  return firstPaneOfWorkspace(pre, post, nextThenPrevious(inOrder, preWorkspaceOrder, close.targetId));
}

/** First pane of `tabId` by pre-close catalog order that survives in `post`. */
function firstPaneOfTab(pre: SessionSnapshot, post: SessionSnapshot, tabId: string | null): string | null {
  if (!tabId) return null;
  return (
    pre.panes.find((candidate) => candidate.tab_id === tabId && post.panes.some((survivor) => survivor.pane_id === candidate.pane_id))?.pane_id ?? null
  );
}

/** First pane of `workspaceId` by pre-close catalog order that survives in `post`. */
function firstPaneOfWorkspace(pre: SessionSnapshot, post: SessionSnapshot, workspaceId: string | null): string | null {
  if (!workspaceId) return null;
  return (
    pre.panes.find(
      (candidate) => candidate.workspace_id === workspaceId && post.panes.some((survivor) => survivor.pane_id === candidate.pane_id),
    )?.pane_id ?? null
  );
}

/**
 * Over a pre-close-ordered list of surviving ids, pick the id immediately
 * after `reference`, else the one immediately before it. The list must be in
 * ascending pre-close catalog order.
 */
function nextThenPrevious(orderedIds: string[], preOrder: string[], reference: string): string | null {
  const referenceIndex = preOrder.indexOf(reference);
  let previous: string | null = null;
  for (const id of orderedIds) {
    const index = preOrder.indexOf(id);
    if (index > referenceIndex) return id;
    previous = id;
  }
  return previous;
}

function firstPane(snapshot: SessionSnapshot): string | null {
  return snapshot.panes[0]?.pane_id ?? null;
}
