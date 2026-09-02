import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { readdir, readFile } from "node:fs/promises";
import * as v from "valibot";

/** Opaque run directory name; never a path. Invalid ids must not walk the store. */
export const PI_SUBAGENT_RUN_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._:-]*$/;

const ACTIVE_RUN_STATUSES = new Set(["queued", "running"]);

const optionalText = v.optional(v.string());

const runJsonSchema = v.looseObject({
  runId: optionalText,
  paneId: optionalText,
  sessionId: optionalText,
  agent: optionalText,
  label: optionalText,
  status: optionalText,
  createdAt: optionalText,
  task: optionalText,
  taskPreview: optionalText,
  error: optionalText,
  model: optionalText,
  thinking: optionalText,
});

const usageJsonSchema = v.looseObject({
  input: v.optional(v.number()),
  output: v.optional(v.number()),
  cacheRead: v.optional(v.number()),
  cacheWrite: v.optional(v.number()),
  cost: v.optional(v.number()),
  turns: v.optional(v.number()),
});

const toolCallJsonSchema = v.looseObject({
  tool: optionalText,
  args: optionalText,
  status: optionalText,
});

const progressFieldsSchema = v.looseObject({
  status: optionalText,
  lastMessage: optionalText,
  error: optionalText,
  model: optionalText,
  thinking: optionalText,
  contextTokens: v.optional(v.number()),
  durationMs: v.optional(v.number()),
  toolCount: v.optional(v.number()),
  usage: v.optional(usageJsonSchema),
  recentTools: v.optional(v.array(toolCallJsonSchema)),
});

const progressJsonSchema = v.looseObject({
  progress: v.optional(progressFieldsSchema),
  status: optionalText,
  lastMessage: optionalText,
  error: optionalText,
});

const resultJsonSchema = v.looseObject({
  status: optionalText,
  error: optionalText,
  output: optionalText,
  truncated: v.optional(v.boolean()),
});

type RunJson = v.InferOutput<typeof runJsonSchema>;
type ProgressJson = v.InferOutput<typeof progressJsonSchema>;
/** True when `runId` is a single safe path segment for `runs/<runId>/`. */
export function isValidPiSubagentRunId(runId: string): boolean {
  return PI_SUBAGENT_RUN_ID_RE.test(runId) && !runId.includes("/") && !runId.includes("\\");
}

/**
 * `$PI_SUBAGENTS_HOME/runs` or `~/.pi/subagents/runs`.
 * `PI_SUBAGENTS_HOME` is the test injection point; never walk a caller-supplied run id.
 */
export function piSubagentsRunsDir(env: NodeJS.ProcessEnv = process.env): string {
  const override = env.PI_SUBAGENTS_HOME?.trim();
  if (override) return join(resolve(override), "runs");
  return join(homedir(), ".pi", "subagents", "runs");
}

/** Fields mirrored from pi-workflow `run.json` for live Board nesting. */
export interface IndexedLiveRun {
  runId: string;
  paneId: string;
  sessionId: string | null;
  agent: string;
  label: string | null;
  status: string | null;
  createdAt: string | null;
  task: string;
  taskPreview: string;
}

/** Progress payload for GET /api/subagents/:runId. */
/** Token/cost accounting mirrored from pi-workflow `progress.json`. */
export interface PiSubagentUsage {
  input: number | null;
  output: number | null;
  cacheRead: number | null;
  cacheWrite: number | null;
  cost: number | null;
  turns: number | null;
}

/** One recent tool call from pi-workflow `progress.json`. */
export interface PiSubagentToolCall {
  tool: string;
  args: string | null;
  status: string | null;
}

/** Progress payload for GET /api/subagents/:runId. */
export interface PiSubagentProgress {
  runId: string;
  role: string;
  label: string | null;
  task: string;
  taskPreview: string;
  status: string;
  paneId: string | null;
  lastMessage: string | null;
  error: string | null;
  output: string | null;
  truncated: boolean;
  /** Resolved runtime model (progress.json) falling back to requested (run.json). */
  model: string | null;
  thinking: string | null;
  contextTokens: number | null;
  usage: PiSubagentUsage | null;
  durationMs: number | null;
  toolCount: number | null;
  recentTools: PiSubagentToolCall[];
}

export interface PiSubagentStoreOptions {
  runsDir?: string;
  env?: NodeJS.ProcessEnv;
}

function runsDirFrom(options?: PiSubagentStoreOptions): string {
  return options?.runsDir ?? piSubagentsRunsDir(options?.env);
}

function nonempty(value: string | null | undefined): string | null {
  return value && value.length > 0 ? value : null;
}

async function readParsed<TSchema extends v.GenericSchema>(
  path: string,
  schema: TSchema,
): Promise<v.InferOutput<TSchema> | null> {
  try {
    const raw: unknown = JSON.parse(await readFile(path, "utf8"));
    const parsed = v.safeParse(schema, raw);
    return parsed.success ? parsed.output : null;
  } catch {
    return null;
  }
}

function parseRunRecord(record: RunJson | null, fallbackRunId: string): IndexedLiveRun | null {
  if (!record) return null;
  const runId = nonempty(record.runId) ?? fallbackRunId;
  const paneId = nonempty(record.paneId);
  const agent = nonempty(record.agent);
  if (!isValidPiSubagentRunId(runId) || !paneId || !agent) return null;
  return {
    runId,
    paneId,
    sessionId: nonempty(record.sessionId),
    agent,
    label: nonempty(record.label),
    status: nonempty(record.status),
    createdAt: nonempty(record.createdAt),
    task: record.task ?? "",
    taskPreview: record.taskPreview ?? "",
  };
}

function preferLiveRun(current: IndexedLiveRun, next: IndexedLiveRun): IndexedLiveRun {
  const currentActive = current.status !== null && ACTIVE_RUN_STATUSES.has(current.status);
  const nextActive = next.status !== null && ACTIVE_RUN_STATUSES.has(next.status);
  if (nextActive !== currentActive) return nextActive ? next : current;
  if (current.createdAt && next.createdAt) {
    return next.createdAt > current.createdAt ? next : current;
  }
  if (next.createdAt && !current.createdAt) return next;
  return current;
}

function progressFields(
  file: ProgressJson | null,
): v.InferOutput<typeof progressFieldsSchema> | null {
  return file?.progress ?? file;
}

/** Bound on the recent tool tail sent to the progress screen. */
const RECENT_TOOLS_LIMIT = 8;

function finiteOrNull(value: number | undefined): number | null {
  return value !== undefined && Number.isFinite(value) ? value : null;
}

/** Token/turn counts stay integral on the wire so Kotlin can decode them as Long/Int. */
function integralOrNull(value: number | undefined): number | null {
  const finite = finiteOrNull(value);
  return finite === null ? null : Math.round(finite);
}

function usagePayload(
  usage: v.InferOutput<typeof usageJsonSchema> | undefined,
): PiSubagentUsage | null {
  if (!usage) return null;
  return {
    input: integralOrNull(usage.input),
    output: integralOrNull(usage.output),
    cacheRead: integralOrNull(usage.cacheRead),
    cacheWrite: integralOrNull(usage.cacheWrite),
    cost: finiteOrNull(usage.cost),
    turns: integralOrNull(usage.turns),
  };
}

function recentToolsPayload(
  tools: v.InferOutput<typeof toolCallJsonSchema>[] | undefined,
): PiSubagentToolCall[] {
  if (!tools || tools.length === 0) return [];
  return tools
    .slice(-RECENT_TOOLS_LIMIT)
    .map((call) => ({
      tool: nonempty(call.tool) ?? "",
      args: nonempty(call.args),
      status: nonempty(call.status),
    }))
    .filter((call) => call.tool.length > 0);
}

/**
 * Index `run.json` files whose `paneId` is in the current Herdr snapshot.
 * Corrupt files are skipped. A missing store is an empty index, not an error.
 */
export async function indexLiveRuns(
  paneIds: ReadonlySet<string>,
  options?: PiSubagentStoreOptions,
): Promise<Map<string, IndexedLiveRun>> {
  const byPane = new Map<string, IndexedLiveRun>();
  if (paneIds.size === 0) return byPane;
  const dir = runsDirFrom(options);
  let names: string[];
  try {
    names = await readdir(dir);
  } catch {
    return byPane;
  }
  for (const name of names) {
    if (!isValidPiSubagentRunId(name)) continue;
    const parsed = parseRunRecord(await readParsed(join(dir, name, "run.json"), runJsonSchema), name);
    if (!parsed || !paneIds.has(parsed.paneId)) continue;
    const existing = byPane.get(parsed.paneId);
    byPane.set(parsed.paneId, existing ? preferLiveRun(existing, parsed) : parsed);
  }
  return byPane;
}

/**
 * Read one run for the progress view. Missing `progress.json` / `result.json`
 * still yield a 200-shaped payload when `run.json` exists.
 */
export async function readPiSubagentProgress(
  runId: string,
  options?: PiSubagentStoreOptions,
): Promise<PiSubagentProgress | null> {
  if (!isValidPiSubagentRunId(runId)) return null;
  const dir = join(runsDirFrom(options), runId);
  const raw = await readParsed(join(dir, "run.json"), runJsonSchema);
  const run = parseRunRecord(raw, runId);
  if (!run) return null;
  const progress = progressFields(await readParsed(join(dir, "progress.json"), progressJsonSchema));
  const result = await readParsed(join(dir, "result.json"), resultJsonSchema);
  return {
    runId: run.runId,
    role: run.agent,
    label: run.label,
    task: run.task,
    taskPreview: run.taskPreview,
    status: nonempty(run.status)
      ?? nonempty(progress?.status)
      ?? nonempty(result?.status)
      ?? "unknown",
    paneId: run.paneId,
    lastMessage: nonempty(progress?.lastMessage),
    error: nonempty(result?.error)
      ?? nonempty(raw?.error)
      ?? nonempty(progress?.error),
    output: nonempty(result?.output),
    truncated: result?.truncated === true,
    model: nonempty(progress?.model) ?? nonempty(raw?.model),
    thinking: nonempty(progress?.thinking) ?? nonempty(raw?.thinking),
    contextTokens: integralOrNull(progress?.contextTokens),
    usage: usagePayload(progress?.usage),
    durationMs: integralOrNull(progress?.durationMs),
    toolCount: integralOrNull(progress?.toolCount),
    recentTools: recentToolsPayload(progress?.recentTools),
  };
}
