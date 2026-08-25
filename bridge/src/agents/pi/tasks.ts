import * as v from "valibot";
import { activeTranscriptEntryIds, type AgentTask } from "../../agent-tasks.js";
import { readTranscriptText, type Transcript } from "../../transcript.js";
import { parsePiTranscript } from "./transcript.js";

const PI_TODO_TOOL = "todo";
const taskStatusSchema = v.picklist(["pending", "in_progress", "completed", "deleted"]);
const taskIdSchema = v.union([v.string(), v.number()]);
const piTodoTaskSchema = v.looseObject({
  id: taskIdSchema,
  subject: v.string(),
  description: v.optional(v.string()),
  activeForm: v.optional(v.string()),
  status: taskStatusSchema,
  blockedBy: v.optional(v.array(taskIdSchema)),
  owner: v.optional(v.string()),
});
const piTodoSnapshotSchema = v.looseObject({
  tasks: v.array(piTodoTaskSchema),
  nextId: v.number(),
});

function piTaskId(id: string | number): string {
  return `pi-todo:${String(id)}`;
}

/** Reconstruct the latest complete rpiv-todo snapshot in a parsed Pi transcript. */
export function extractPiAgentTasks(transcript: Transcript): AgentTask[] {
  const activeEntries = activeTranscriptEntryIds(transcript);
  let latest: v.InferOutput<typeof piTodoSnapshotSchema> | null = null;
  for (const entry of transcript.entries) {
    if (!activeEntries.has(entry.entryId)) continue;
    if (entry.role !== "toolResult" || entry.toolName !== PI_TODO_TOOL || entry.isError === true) continue;
    const parsed = v.safeParse(piTodoSnapshotSchema, entry.details);
    if (parsed.success) latest = parsed.output;
  }
  if (!latest) return [];
  return latest.tasks.map((task) => ({
    id: piTaskId(task.id),
    subject: task.subject,
    description: task.description,
    activeForm: task.activeForm,
    status: task.status,
    blockedBy: (task.blockedBy ?? []).map(piTaskId),
    owner: task.owner,
  }));
}

/** Read rpiv-todo state on the transcript's active parent branch. */
export async function readPiAgentTasks(path: string): Promise<AgentTask[]> {
  return extractPiAgentTasks(parsePiTranscript(await readTranscriptText(path)));
}
