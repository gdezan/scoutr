import { backendForAgentSessionInfo, getBackendOrNull } from "../agents/registry.js";
import { keyForAgent, type SessionDescriptor } from "../session-model.js";
import type { SessionSnapshot } from "../herdr/types.js";
import {
  indexLiveRuns,
  type IndexedLiveRun,
  type PiSubagentStoreOptions,
} from "./run-store.js";
import { piWorkflowSessionIdentity } from "./session-identity.js";

/** Compact PI-workflow child nested under a live parent Board card. */
export interface NestedPiSubagent {
  runId: string;
  paneId: string;
  role: string;
  label: string | null;
  /** Herdr `agent_status` of the child pane. */
  status: string;
}

function childFrom(run: IndexedLiveRun, status: string): NestedPiSubagent {
  return {
    runId: run.runId,
    paneId: run.paneId,
    role: run.agent,
    label: run.label,
    status,
  };
}

function parentIdentity(descriptor: SessionDescriptor): string | null {
  if (descriptor.agentKind !== "pi" || !descriptor.key?.path) return null;
  return piWorkflowSessionIdentity(descriptor.key.path);
}

/**
 * Attach live PI-workflow children onto their parent descriptors and drop
 * nested children from the top-level Board list. Unmatched `sessionId` stays
 * an orphan card. Does not copy parent activity or git summaries onto children.
 */
export function nestLiveSubagents(
  descriptors: SessionDescriptor[],
  runsByPaneId: Map<string, IndexedLiveRun>,
): SessionDescriptor[] {
  if (runsByPaneId.size === 0) return descriptors;

  const parentPaneByIdentity = new Map<string, string>();
  for (const descriptor of descriptors) {
    const identity = parentIdentity(descriptor);
    const paneId = descriptor.live?.paneId;
    if (identity && paneId) parentPaneByIdentity.set(identity, paneId);
  }

  const nestedPaneIds = new Set<string>();
  const childrenByParentPane = new Map<string, NestedPiSubagent[]>();
  for (const descriptor of descriptors) {
    const paneId = descriptor.live?.paneId;
    if (!paneId) continue;
    const run = runsByPaneId.get(paneId);
    if (!run) continue;
    const parentPaneId = run.sessionId ? parentPaneByIdentity.get(run.sessionId) : undefined;
    if (!parentPaneId || parentPaneId === paneId) continue;
    nestedPaneIds.add(paneId);
    const children = childrenByParentPane.get(parentPaneId) ?? [];
    children.push(childFrom(run, descriptor.live?.status ?? ""));
    childrenByParentPane.set(parentPaneId, children);
  }

  const result: SessionDescriptor[] = [];
  for (const descriptor of descriptors) {
    const paneId = descriptor.live?.paneId;
    if (paneId && nestedPaneIds.has(paneId)) continue;
    const children = paneId ? childrenByParentPane.get(paneId) : undefined;
    if (children?.length) {
      result.push({ ...descriptor, subagents: children });
      continue;
    }
    const run = paneId ? runsByPaneId.get(paneId) : undefined;
    if (run) {
      result.push({
        ...descriptor,
        title: run.label || run.agent,
        subagent: {
          runId: run.runId,
          role: run.agent,
          label: run.label,
          orphan: true,
        },
      });
      continue;
    }
    result.push(descriptor);
  }
  return result;
}

/**
 * Board-poll nest pass. A run-store read failure leaves descriptors unchanged
 * and logs at most once for this cycle.
 */
export async function nestLiveSubagentsFromStore(
  descriptors: SessionDescriptor[],
  options?: PiSubagentStoreOptions,
): Promise<SessionDescriptor[]> {
  const paneIds = new Set<string>();
  for (const descriptor of descriptors) {
    if (descriptor.live?.paneId) paneIds.add(descriptor.live.paneId);
  }
  try {
    return nestLiveSubagents(descriptors, await indexLiveRuns(paneIds, options));
  } catch (error) {
    console.error(
      "pi subagents run store unreadable",
      error instanceof Error ? error.message : String(error),
    );
    return descriptors;
  }
}

/**
 * Nested child pane ids for FCM suppression. Orphans are not nested.
 * Uses the same `run.json.paneId` index and parent `SessionKey.path` hash as Board.
 */
export async function liveNestedSubagentPaneIds(
  snapshot: SessionSnapshot | null,
  options?: PiSubagentStoreOptions,
): Promise<Set<string>> {
  const agents = snapshot?.agents ?? [];
  if (agents.length === 0) return new Set();
  const paneIds = new Set(agents.map((agent) => agent.pane_id));
  let runs: Map<string, IndexedLiveRun>;
  try {
    runs = await indexLiveRuns(paneIds, options);
  } catch {
    return new Set();
  }
  if (runs.size === 0) return new Set();

  const parentPaneByIdentity = new Map<string, string>();
  await Promise.all(agents.map(async (agent) => {
    const backend = backendForAgentSessionInfo(agent.agent_session) ?? getBackendOrNull(agent.agent);
    const key = backend && agent.agent_session
      ? await keyForAgent(backend, agent.agent_session, agent.cwd ?? undefined)
      : null;
    if (backend?.id !== "pi" || !key?.path) return;
    parentPaneByIdentity.set(piWorkflowSessionIdentity(key.path), agent.pane_id);
  }));

  const nested = new Set<string>();
  for (const [paneId, run] of runs) {
    if (!run.sessionId) continue;
    const parentPaneId = parentPaneByIdentity.get(run.sessionId);
    if (parentPaneId && parentPaneId !== paneId) nested.add(paneId);
  }
  return nested;
}
