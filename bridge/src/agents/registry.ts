import type { AgentBackend } from "./types.js";
import type { AgentSessionInfo, SessionSnapshot } from "../herdr/types.js";
import { piBackend } from "./pi/index.js";
import { claudeBackend } from "./claude/index.js";
import { agyBackend } from "./agy/index.js";

export function backendFor(agentId: string): AgentBackend {
  const backend = REGISTRY.get(agentId);
  if (!backend) throw new Error(`unknown agent backend: ${agentId}`);
  return backend;
}

export function registerBackend(backend: AgentBackend, aliases: string[] = []): void {
  REGISTRY.set(backend.id, backend);
  for (const alias of aliases) {
    REGISTRY.set(alias, backend);
  }
}

export function getBackendOrNull(agentId: string): AgentBackend | null {
  return REGISTRY.get(agentId) ?? null;
}

export function backendForAgentSessionInfo(session: AgentSessionInfo | null | undefined): AgentBackend | null {
  if (!session) return null;
  return REGISTRY.get(session.agent) ?? null;
}

/**
 * The registered backend that owns a live pane, from a snapshot. A pane can
 * identify its agent either via the herdr agent session (`agent_session.agent`)
 * or the plain agent label; a third backend needs to learn nothing beyond
 * its own registration.
 */
export function resolveBackendForPane(
  snapshot: SessionSnapshot | null | undefined,
  paneId: string,
): AgentBackend | null {
  const pane = snapshot?.panes.find((p) => p.pane_id === paneId);
  if (pane) return backendForAgentSessionInfo(pane.agent_session) ?? getBackendOrNull(pane.agent ?? "");
  const agent = snapshot?.agents.find((a) => a.pane_id === paneId);
  if (agent) return backendForAgentSessionInfo(agent.agent_session) ?? getBackendOrNull(agent.agent);
  return null;
}

/** The first registered backend whose sandbox owns this transcript path. */
export function backendForSessionPath(path: string): AgentBackend | null {
  for (const backend of REGISTRY.values()) {
    if (backend.ownsSessionPath(path)) return backend;
  }
  return null;
}

export function knownBackends(): readonly AgentBackend[] {
  return [...new Set(REGISTRY.values())];
}

const REGISTRY = new Map<string, AgentBackend>();

// The registry is populated at module load, so any module that imports a
// lookup (directly or transitively) sees every registered backend.
registerBackend(piBackend);
registerBackend(claudeBackend);
registerBackend(agyBackend, ["gemini", "antigravity_cli", "antigravity"]);
