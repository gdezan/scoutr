import type { AgentBackend } from "./types.js";
import type { AgentSessionInfo } from "../herdr/types.js";
import { piBackend } from "./pi/index.js";
import { claudeBackend } from "./claude/index.js";

export function backendFor(agentId: string): AgentBackend {
  const backend = REGISTRY.get(agentId);
  if (!backend) throw new Error(`unknown agent backend: ${agentId}`);
  return backend;
}

export function registerBackend(backend: AgentBackend): void {
  REGISTRY.set(backend.id, backend);
}

export function getBackendOrNull(agentId: string): AgentBackend | null {
  return REGISTRY.get(agentId) ?? null;
}

export function backendForAgentSessionInfo(session: AgentSessionInfo | null | undefined): AgentBackend | null {
  if (!session) return null;
  return REGISTRY.get(session.agent) ?? null;
}

/** The first registered backend whose sandbox owns this transcript path. */
export function backendForSessionPath(path: string): AgentBackend | null {
  for (const backend of REGISTRY.values()) {
    if (backend.ownsSessionPath(path)) return backend;
  }
  return null;
}

export function knownBackends(): readonly AgentBackend[] {
  return [...REGISTRY.values()];
}

const REGISTRY = new Map<string, AgentBackend>();

// The registry is populated at module load, so any module that imports a
// lookup (directly or transitively) sees every registered backend.
registerBackend(piBackend);
registerBackend(claudeBackend);
