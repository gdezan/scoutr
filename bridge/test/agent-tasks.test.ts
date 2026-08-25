import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { extractClaudeAgentTasks } from "../src/agents/claude/tasks.js";
import { parseClaudeTranscript } from "../src/agents/claude/transcript.js";
import { extractPiAgentTasks } from "../src/agents/pi/tasks.js";
import { parsePiTranscript } from "../src/agents/pi/transcript.js";

interface PiTodoTaskFixture {
  id: string | number;
  subject: string;
  description?: string;
  activeForm?: string;
  status: "pending" | "in_progress" | "completed" | "deleted";
  blockedBy?: Array<string | number>;
  owner?: string;
}

interface PiTodoDetailsFixture {
  tasks: PiTodoTaskFixture[];
  nextId?: number;
}

interface ClaudeTaskInputFixture {
  taskId?: string | number;
  subject?: string;
  description?: string;
  activeForm?: string;
  status?: "pending" | "in_progress" | "completed" | "deleted";
  addBlockedBy?: Array<string | number>;
  removeBlockedBy?: Array<string | number>;
  addBlocks?: Array<string | number>;
  owner?: string;
}

interface ClaudeTaskResultFixture {
  success?: boolean;
  taskId?: string | number;
  updatedFields?: string[];
  task?: { id: string | number; subject?: string };
}

type FixtureRecord = { type: string } & (
  { id: string; parentId?: string | null } |
  { uuid: string; parentUuid?: string | null }
);

function jsonl(records: FixtureRecord[]): string {
  let parent: string | null = null;
  const linked = records.map((record) => {
    if ("id" in record) {
      const linkedRecord = { ...record, parentId: parent };
      parent = record.id;
      return linkedRecord;
    }
    const linkedRecord = { ...record, parentUuid: parent };
    parent = record.uuid;
    return linkedRecord;
  });
  return `${linked.map((record) => JSON.stringify(record)).join("\n")}\n`;
}

function piTodoResult(id: string, details: PiTodoDetailsFixture, isError = false) {
  return {
    type: "message",
    id,
    parentId: null,
    timestamp: "2026-01-01T00:00:00Z",
    message: {
      role: "toolResult",
      toolCallId: `call-${id}`,
      toolName: "todo",
      isError,
      details: { nextId: 100, ...details },
      content: [],
    },
  };
}

function claudeTaskCall(
  id: string,
  name: "TaskCreate" | "TaskUpdate",
  input: ClaudeTaskInputFixture,
) {
  return {
    type: "assistant",
    uuid: `assistant-${id}`,
    timestamp: "2026-01-01T00:00:00Z",
    message: { role: "assistant", content: [{ type: "tool_use", id, name, input }] },
  };
}

function claudeTaskResult(
  id: string,
  toolUseResult: ClaudeTaskResultFixture,
  isError = false,
) {
  return {
    type: "user",
    uuid: `result-${id}`,
    timestamp: "2026-01-01T00:00:01Z",
    message: {
      role: "user",
      content: [{ type: "tool_result", tool_use_id: id, is_error: isError, content: "done" }],
    },
    toolUseResult,
  };
}

describe("Pi rpiv-todo task snapshots", () => {
  it("uses the latest successful complete snapshot and namespaces dependency ids", () => {
    const transcript = parsePiTranscript(jsonl([
      piTodoResult("old", { tasks: [{ id: 1, subject: "Old", status: "pending", blockedBy: [] }] }),
      piTodoResult("failed", { tasks: [{ id: 99, subject: "Wrong", status: "pending" }] }, true),
      piTodoResult("latest", {
        tasks: [
          { id: 1, subject: "Ship tasks", description: "Read only", activeForm: "shipping tasks", status: "in_progress", blockedBy: [2], owner: "bridge" },
          { id: 2, subject: "Review", status: "completed", blockedBy: [] },
          { id: 3, subject: "Discarded", status: "deleted", blockedBy: [] },
        ],
      }),
    ]));

    assert.deepEqual(extractPiAgentTasks(transcript), [
      {
        id: "pi-todo:1",
        subject: "Ship tasks",
        description: "Read only",
        activeForm: "shipping tasks",
        status: "in_progress",
        blockedBy: ["pi-todo:2"],
        owner: "bridge",
      },
      {
        id: "pi-todo:2",
        subject: "Review",
        description: undefined,
        activeForm: undefined,
        status: "completed",
        blockedBy: [],
        owner: undefined,
      },
      {
        id: "pi-todo:3",
        subject: "Discarded",
        description: undefined,
        activeForm: undefined,
        status: "deleted",
        blockedBy: [],
        owner: undefined,
      },
    ]);
  });

  it("ignores snapshots from an abandoned parent branch", () => {
    const baseline = { ...piTodoResult("baseline", { tasks: [{ id: 1, subject: "Active", status: "pending" }] }), parentId: null };
    const abandoned = { ...piTodoResult("abandoned", { tasks: [{ id: 2, subject: "Wrong branch", status: "pending" }] }), parentId: "baseline" };
    const compaction = { type: "compaction", id: "compaction", parentId: "baseline", summary: "Earlier work" };
    const transcript = parsePiTranscript(
      `${[baseline, abandoned, compaction].map((record) => JSON.stringify(record)).join("\n")}\n`,
    );

    assert.deepEqual(extractPiAgentTasks(transcript).map((task) => task.subject), ["Active"]);
  });
});

describe("Claude implementation tasks", () => {
  it("applies only successful TaskCreate and TaskUpdate results in transcript order", () => {
    const transcript = parseClaudeTranscript(jsonl([
      claudeTaskCall("create-1", "TaskCreate", { subject: "Build adapter", description: "Transcript-confirmed", activeForm: "building adapter" }),
      claudeTaskResult("create-1", { task: { id: "1", subject: "Build adapter" } }),
      claudeTaskCall("create-2", "TaskCreate", { subject: "Review adapter", description: "Check edges" }),
      claudeTaskResult("create-2", { task: { id: "2", subject: "Review adapter" } }),
      claudeTaskCall("update-1", "TaskUpdate", { taskId: "1", status: "completed", addBlocks: ["2"], owner: "claude" }),
      claudeTaskResult("update-1", { success: true, taskId: "1", updatedFields: ["status"] }),
      claudeTaskCall("failed-update", "TaskUpdate", { taskId: "2", status: "deleted" }),
      claudeTaskResult("failed-update", { success: false, taskId: "2" }, true),
    ]));

    assert.deepEqual(extractClaudeAgentTasks(transcript), [
      {
        id: "claude-task:1",
        subject: "Build adapter",
        description: "Transcript-confirmed",
        activeForm: "building adapter",
        status: "completed",
        blockedBy: [],
        owner: "claude",
      },
      {
        id: "claude-task:2",
        subject: "Review adapter",
        description: "Check edges",
        activeForm: undefined,
        status: "pending",
        blockedBy: ["claude-task:1"],
      },
    ]);
  });

  it("ignores unconfirmed creates and updates for tasks not created in this transcript", () => {
    const transcript = parseClaudeTranscript(jsonl([
      claudeTaskCall("missing-create", "TaskCreate", { subject: "Never confirmed" }),
      claudeTaskCall("unknown-update", "TaskUpdate", { taskId: "9", status: "completed" }),
      claudeTaskResult("unknown-update", { success: true, taskId: "9" }),
    ]));

    assert.deepEqual(extractClaudeAgentTasks(transcript), []);
  });

  it("keeps task events connected through a non-display Claude record", () => {
    const call = { ...claudeTaskCall("create", "TaskCreate", { subject: "Survives compaction" }), parentUuid: null };
    const result = { ...claudeTaskResult("create", { task: { id: "1" } }), parentUuid: "assistant-create" };
    const compaction = { type: "system", uuid: "compaction", parentUuid: "result-create" };
    const transcript = parseClaudeTranscript(
      `${[call, result, compaction].map((record) => JSON.stringify(record)).join("\n")}\n`,
    );

    assert.deepEqual(extractClaudeAgentTasks(transcript).map((task) => task.subject), ["Survives compaction"]);
  });
});
