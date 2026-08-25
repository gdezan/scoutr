import type { Transcript } from "./transcript.js";

/** The task lifecycle Scoutr can render consistently across supported agents. */
export type AgentTaskStatus = "pending" | "in_progress" | "completed" | "deleted";

/** One implementation task confirmed by an agent transcript. */
export interface AgentTask {
  /** Backend-namespaced stable id; opaque to API clients. */
  id: string;
  subject: string;
  description?: string;
  activeForm?: string;
  status: AgentTaskStatus;
  /** Backend-namespaced ids of tasks that must finish first. */
  blockedBy: string[];
  owner?: string;
}

/** Entry ids on the parent chain ending at the transcript's current leaf. */
export function activeTranscriptEntryIds(transcript: Transcript): ReadonlySet<string> {
  const parents = new Map(transcript.parentById);
  for (const entry of transcript.entries) parents.set(entry.entryId, entry.parentId);
  const active = new Set<string>();
  let entryId = transcript.branchLeafId ?? transcript.lastEntryId;
  while (entryId && !active.has(entryId)) {
    active.add(entryId);
    entryId = parents.get(entryId) ?? null;
  }
  return active;
}
