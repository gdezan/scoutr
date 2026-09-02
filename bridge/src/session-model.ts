import { canonicalPath } from "./dirs.js";
import type { AgentBackend } from "./agents/types.js";
import { getBackendOrNull } from "./agents/registry.js";
import type { AgentInfo, AgentSessionInfo } from "./herdr/types.js";
import { promptAttention, type AttentionSummary, type BoardDetail } from "./board-detail.js";
import type { RepoSummary } from "./board-repo-summary.js";
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
  /** Transcript revision used to order model metadata across API responses. */
  transcriptMtimeMs: number | null;
  transcriptSize: number | null;
  latestActivity: string | null;
  /**
   * Why this session wants the user, normalized and bounded: the open ask's
   * ids and authored options, never raw tool arguments. Null unless the
   * session is waiting.
   */
  attention: AttentionSummary | null;
  /**
   * Deterministic git evidence for a Done card (branch, dirty/clean, change
   * counts). Null unless this is a Done agent with a cwd and the summary
   * computation succeeded; never a safety verdict.
   */
  doneSummary: RepoSummary | null;
  /**
   * The same git evidence for a live (non-done) agent, as fresh as the last
   * TTL-bounded computation — the repo state keeps moving under a running
   * agent. Null for Done cards (they carry `doneSummary`) and whenever the
   * computation failed; never a safety verdict.
   */
  liveSummary: RepoSummary | null;
  live: SessionLiveAttachment | null;
  /**
   * Present on a top-level PI-workflow subagent card. Nested children are
   * omitted from `agents[]` and never carry this field.
   */
  subagent?: {
    runId: string;
    role: string;
    label: string | null;
    orphan: boolean;
  } | null;
  /** Compact children nested under a live parent. Empty/absent otherwise. */
  subagents?: Array<{
    runId: string;
    paneId: string;
    role: string;
    label: string | null;
    /** Herdr agent_status of the child pane. */
    status: string;
  }>;
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
    transcriptMtimeMs: detail?.transcriptMtimeMs ?? null,
    transcriptSize: detail?.transcriptSize ?? null,
    latestActivity: detail?.latestActivity || null,
    // A blocked pane with no structured ask still needs the user; only here is
    // the herdr status known, so the board detail cannot say it on its own.
    attention: detail?.attention
      ?? (agent.agent_status === "blocked" ? promptAttention() : null),
    // Only the /api/agents route fills these in; done cards get doneSummary,
    // every other live status gets liveSummary.
    doneSummary: null,
    liveSummary: null,
    live: {
      paneId: agent.pane_id,
      workspaceId: agent.workspace_id,
      tabId: agent.tab_id,
      status: agent.agent_status,
      statusSinceMs: statusSinceMs ?? null,
    },
  };
}
