import * as v from "valibot";
import { readFile, rename, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { canonicalPath } from "./dirs.js";

/**
 * Bridge-owned record of which project root each Scoutr-managed Herdr
 * workspace represents. Herdr's snapshot carries no workspace cwd, so without
 * this registry the only grounding for "which project is this workspace for"
 * is its root pane's live cwd — which breaks the moment that pane cd's
 * elsewhere. Records here are advisory indexing: the live Herdr snapshot stays
 * authoritative for whether a workspace exists, and Scoutr only ever records
 * workspaces it deliberately created or reused.
 */

export interface WorkspaceRootRecord {
  workspaceId: string;
  /** Canonical absolute project path. */
  cwd: string;
  updatedAtMs: number;
}

export interface WorkspaceRootStore {
  list(): Promise<WorkspaceRootRecord[]>;
  record(workspaceId: string, cwd: string): Promise<void>;
  remove(workspaceId: string): Promise<void>;
  prune(liveWorkspaceIds: ReadonlySet<string>): Promise<void>;
}

const WORKSPACE_ROOTS_FILENAME = "workspace-roots.json";

/** Upper bound on persisted records so stale history cannot grow forever. */
const MAX_RECORDS = 512;

/** Shape of one persisted record; entries that do not match are skipped. */
const workspaceRootRecordSchema = v.object({
  workspaceId: v.pipe(v.string(), v.minLength(1)),
  cwd: v.pipe(v.string(), v.minLength(1)),
  updatedAtMs: v.pipe(v.number(), v.finite()),
});

/** A registry file is a JSON array; individual entries may be stale-shaped. */
const registryFileSchema = v.array(v.unknown());

/**
 * File-backed registry under the bridge config dir. Every method degrades
 * safely: a missing or malformed file reads as an empty registry and write
 * failures warn instead of throwing, because a broken registry must never
 * keep the bridge from launching a session.
 */
export class FileWorkspaceRootStore implements WorkspaceRootStore {
  private writes: Promise<void> = Promise.resolve();

  constructor(private readonly configDir: string) {}

  private get filePath(): string {
    return join(this.configDir, WORKSPACE_ROOTS_FILENAME);
  }

  async list(): Promise<WorkspaceRootRecord[]> {
    let entries: unknown[];
    try {
      const parsed: unknown = JSON.parse(await readFile(this.filePath, "utf8"));
      entries = v.parse(registryFileSchema, parsed);
    } catch (error) {
      // ENOENT is the steady state before the first record ever lands.
      if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) {
        console.error(
          `warning: workspace roots unreadable, treating as empty — ${
            error instanceof Error ? error.message : String(error)
          }`,
        );
      }
      return [];
    }
    const records: WorkspaceRootRecord[] = [];
    for (const entry of entries) {
      const record = v.safeParse(workspaceRootRecordSchema, entry);
      if (record.success) records.push(record.output);
    }
    return records;
  }

  async record(workspaceId: string, cwd: string): Promise<void> {
    if (!workspaceId || !cwd) return;
    await this.enqueue(async () => {
      const records = await this.list();
      const canonical = canonicalPath(cwd);
      const now = Date.now();
      const remaining = records.filter((record) => record.workspaceId !== workspaceId);
      remaining.push({ workspaceId, cwd: canonical, updatedAtMs: now });
      // Bounded by recency: drop the oldest updates when over capacity.
      remaining.sort((a, b) => a.updatedAtMs - b.updatedAtMs);
      await this.persist(remaining.slice(-MAX_RECORDS));
    });
  }

  async remove(workspaceId: string): Promise<void> {
    if (!workspaceId) return;
    await this.enqueue(async () => {
      const records = await this.list();
      await this.persist(records.filter((record) => record.workspaceId !== workspaceId));
    });
  }

  async prune(liveWorkspaceIds: ReadonlySet<string>): Promise<void> {
    await this.enqueue(async () => {
      const records = await this.list();
      const live = records.filter((record) => liveWorkspaceIds.has(record.workspaceId));
      if (live.length === records.length) return;
      await this.persist(live);
    });
  }

  /**
   * Serializes mutations: each write re-reads the file under the queue, so
   * concurrent launches cannot clobber each other's records.
   */
  private enqueue(operation: () => Promise<void>): Promise<void> {
    const run = this.writes.then(operation, operation);
    this.writes = run.catch(() => {});
    return run;
  }

  /** Atomic replace (temp file → rename) so a crash mid-write never truncates. */
  private async persist(records: WorkspaceRootRecord[]): Promise<void> {
    const temp = `${this.filePath}.tmp`;
    try {
      await writeFile(temp, `${JSON.stringify(records, null, 2)}\n`);
      await rename(temp, this.filePath);
    } catch (error) {
      console.error(
        `warning: could not persist workspace roots — ${
          error instanceof Error ? error.message : String(error)
        }`,
      );
    }
  }
}
