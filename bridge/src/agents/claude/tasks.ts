import * as v from "valibot";
import { activeTranscriptEntryIds, type AgentTask } from "../../agent-tasks.js";
import { type ContentBlock, type ToolCallBlock, type Transcript } from "../../transcript.js";

const CLAUDE_TASK_CREATE = "TaskCreate";
const CLAUDE_TASK_UPDATE = "TaskUpdate";
const taskStatusSchema = v.picklist(["pending", "in_progress", "completed", "deleted"]);
const taskIdSchema = v.union([v.string(), v.number()]);
const taskIdListSchema = v.array(taskIdSchema);

const claudeTaskCreateSchema = v.looseObject({
  subject: v.string(),
  description: v.optional(v.string()),
  activeForm: v.optional(v.string()),
});

const claudeTaskUpdateSchema = v.looseObject({
  taskId: taskIdSchema,
  subject: v.optional(v.string()),
  description: v.optional(v.string()),
  activeForm: v.optional(v.string()),
  status: v.optional(taskStatusSchema),
  addBlockedBy: v.optional(taskIdListSchema),
  removeBlockedBy: v.optional(taskIdListSchema),
  addBlocks: v.optional(taskIdListSchema),
  owner: v.optional(v.string()),
});

export const claudeTaskResultSchema = v.looseObject({
  success: v.optional(v.boolean()),
  taskId: v.optional(taskIdSchema),
  task: v.optional(v.looseObject({ id: taskIdSchema, subject: v.optional(v.string()) })),
});

export type ClaudeTaskResultDetails = v.InferOutput<typeof claudeTaskResultSchema>;

function claudeTaskId(id: string | number): string {
  return `claude-task:${String(id)}`;
}

function isToolCall(block: ContentBlock): block is ToolCallBlock {
  return block.type === "toolCall";
}

function applyClaudeTaskCreate(
  tasks: Map<string, AgentTask>,
  call: ToolCallBlock,
  result: ClaudeTaskResultDetails,
): void {
  const input = v.safeParse(claudeTaskCreateSchema, call.arguments);
  if (!input.success || !result.task || result.success === false) return;
  const id = claudeTaskId(result.task.id);
  tasks.set(id, {
    id,
    subject: input.output.subject,
    description: input.output.description,
    activeForm: input.output.activeForm,
    status: "pending",
    blockedBy: [],
  });
}

function applyClaudeTaskUpdate(
  tasks: Map<string, AgentTask>,
  call: ToolCallBlock,
  result: ClaudeTaskResultDetails,
): void {
  const input = v.safeParse(claudeTaskUpdateSchema, call.arguments);
  if (!input.success || result.success === false) return;
  const id = claudeTaskId(input.output.taskId);
  const current = tasks.get(id);
  if (!current) return;

  const blockedBy = new Set(current.blockedBy);
  for (const taskId of input.output.addBlockedBy ?? []) blockedBy.add(claudeTaskId(taskId));
  for (const taskId of input.output.removeBlockedBy ?? []) blockedBy.delete(claudeTaskId(taskId));
  tasks.set(id, {
    ...current,
    subject: input.output.subject ?? current.subject,
    description: input.output.description ?? current.description,
    activeForm: input.output.activeForm ?? current.activeForm,
    status: input.output.status ?? current.status,
    blockedBy: [...blockedBy],
    owner: input.output.owner ?? current.owner,
  });

  // Claude's addBlocks is the inverse edge: each named task becomes blocked by this one.
  for (const blockedTaskId of input.output.addBlocks ?? []) {
    const blockedId = claudeTaskId(blockedTaskId);
    const blockedTask = tasks.get(blockedId);
    if (!blockedTask || blockedTask.blockedBy.includes(id)) continue;
    tasks.set(blockedId, { ...blockedTask, blockedBy: [...blockedTask.blockedBy, id] });
  }
}

/** Reconstruct confirmed Claude TaskCreate and TaskUpdate state from a parsed transcript. */
export function extractClaudeAgentTasks(transcript: Transcript): AgentTask[] {
  const activeEntries = activeTranscriptEntryIds(transcript);
  const taskCalls = new Map<string, ToolCallBlock>();
  const tasks = new Map<string, AgentTask>();
  for (const entry of transcript.entries) {
    if (!activeEntries.has(entry.entryId)) continue;
    for (const block of entry.content) {
      if (!isToolCall(block)) continue;
      if (block.name === CLAUDE_TASK_CREATE || block.name === CLAUDE_TASK_UPDATE) {
        taskCalls.set(block.id, block);
      }
    }
    if (entry.role !== "toolResult" || entry.isError === true || !entry.toolCallId) continue;
    const call = taskCalls.get(entry.toolCallId);
    const result = v.safeParse(claudeTaskResultSchema, entry.details);
    if (!result.success) continue;
    if (call?.name === CLAUDE_TASK_CREATE) applyClaudeTaskCreate(tasks, call, result.output);
    if (call?.name === CLAUDE_TASK_UPDATE) applyClaudeTaskUpdate(tasks, call, result.output);
  }
  return [...tasks.values()];
}

