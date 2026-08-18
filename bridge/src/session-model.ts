import { canonicalPath } from "./dirs.js";
import type { AgentBackend } from "./agents/types.js";
import { getBackendOrNull } from "./agents/registry.js";
import type { AgentInfo, AgentSessionInfo } from "./herdr/types.js";
import type { BoardDetail } from "./board-detail.js";

/** Durable identity for one backend-owned coding-agent transcript. */
export interface SessionKey {
  agentKind: string;
  path: string;
}

/** Ephemeral Herdr attachment for a session that is currently running. */
export interface SessionLiveAttachment {
  paneId: string;
  workspaceId: string;
  tabId: string;
  status: string;
  statusSinceMs: number | null;
}

/** The one session shape shared by live Board and persisted catalog surfaces. */
export interface SessionDescriptor {
  key: SessionKey | null;
  agentKind: string;
  displayName: string;
  title: string;
  cwd: string | null;
  model: string | null;
  thinkingLevel: string | null;
  capabilities: string[];
  updatedAtMs: number | null;
  latestActivity: string | null;
  live: SessionLiveAttachment | null;
}

/** Resolve a live backend reference without allowing a path outside that backend's store. */
export async function keyForAgent(
  backend: AgentBackend,
  ref: AgentSessionInfo,
  cwd?: string,
): Promise<SessionKey | null> {
  const path = await backend.resolveSessionPath(ref, cwd).catch(() => null);
  return path ? keyForStoredSession(backend, path) : null;
}

/** Build the same key used for catalog files after the owning backend is known. */
export function keyForStoredSession(backend: AgentBackend, path: string): SessionKey | null {
  const canonical = canonicalPath(path);
  return backend.ownsSessionPath(canonical) ? { agentKind: backend.id, path: canonical } : null;
}

/** Normalize one current Herdr agent into the shared session descriptor. */
export function descriptorForLiveAgent(
  agent: AgentInfo,
  key: SessionKey | null,
  detail: BoardDetail | null,
  statusSinceMs?: number,
): SessionDescriptor {
  const backend = getBackendOrNull(agent.agent);
  const agentKind = backend?.id ?? agent.agent;
  const displayName = backend?.displayName ?? agent.agent;
  const title = detail?.title?.trim()
    || agent.terminal_title?.trim()
    || agent.terminal_title_stripped?.trim()
    || displayName;
  return {
    key,
    agentKind,
    displayName,
    title,
    cwd: agent.cwd,
    model: detail?.model ?? null,
    thinkingLevel: detail?.thinkingLevel ?? null,
    capabilities: backend ? [...backend.capabilities] : [],
    updatedAtMs: detail?.latestActivityAtMs ?? null,
    latestActivity: detail?.latestActivity || null,
    live: {
      paneId: agent.pane_id,
      workspaceId: agent.workspace_id,
      tabId: agent.tab_id,
      status: agent.agent_status,
      statusSinceMs: statusSinceMs ?? null,
    },
  };
}
