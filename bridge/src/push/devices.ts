/**
 * The set of phones the bridge pushes to.
 *
 * A device token is a bearer-ish credential for reaching one phone, so the
 * file is written 0600 beside config.json and never logged. The list is small
 * (one entry per paired phone) and read on every send, so it lives in memory
 * and the file is only the durable copy.
 */

import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { isProfileGeneration, type DeviceRegistry, type PushDevice } from "./fcm.js";
import * as v from "valibot";

/** FCM tokens are ~163 chars today; the cap only rejects obvious junk. */
export const MAX_TOKEN_LENGTH = 4096;

export class JsonDeviceRegistry implements DeviceRegistry {
  private devices: PushDevice[] = [];

  private constructor(private readonly path: string, devices: PushDevice[]) {
    this.devices = devices;
  }

  static async open(configDir: string): Promise<JsonDeviceRegistry> {
    const path = join(configDir, "devices.json");
    return new JsonDeviceRegistry(path, await load(path));
  }

  list(): readonly PushDevice[] {
    return this.devices;
  }

  /**
   * Idempotent on token + generation equality. A changed generation replaces
   * the old registration so one token cannot receive stale and current push
   * identities at the same time.
   */
  async register(token: string, profileGeneration?: string): Promise<void> {
    const generation = profileGeneration ?? null;
    const existing = this.devices.find((device) => device.token === token);
    if (existing) {
      existing.profileGeneration = generation;
      existing.updatedAtMs = Date.now();
    } else {
      this.devices.push({ token, profileGeneration: generation, updatedAtMs: Date.now() });
    }
    await this.persist();
  }

  async unregister(token: string): Promise<void> {
    const before = this.devices.length;
    this.devices = this.devices.filter((device) => device.token !== token);
    if (this.devices.length !== before) await this.persist();
  }

  private async persist(): Promise<void> {
    try {
      await writeFile(this.path, `${JSON.stringify(this.devices, null, 2)}\n`, { mode: 0o600 });
    } catch (error) {
      // Keep the in-memory list: push works until restart, which beats
      // dropping a just-registered phone because the disk was full.
      console.error(`[push] could not persist ${this.path}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
}

const deviceEntrySchema = v.looseObject({
  token: v.string(),
  // Older files contain only token + updatedAtMs. Invalid generations are
  // treated as legacy rather than allowing malformed data onto the FCM wire.
  profileGeneration: v.optional(v.nullable(v.string())),
  updatedAtMs: v.optional(v.number()),
});

async function load(path: string): Promise<PushDevice[]> {
  try {
    const parsed: unknown = JSON.parse(await readFile(path, "utf8"));
    if (!Array.isArray(parsed)) return [];
    return parsed.flatMap((entry) => {
      const decoded = v.safeParse(deviceEntrySchema, entry);
      if (!decoded.success || !decoded.output.token) return [];
      const profileGeneration = isProfileGeneration(decoded.output.profileGeneration)
        ? decoded.output.profileGeneration
        : null;
      return [{ token: decoded.output.token, profileGeneration, updatedAtMs: decoded.output.updatedAtMs ?? 0 }];
    });
  } catch {
    // Missing or corrupt: start empty. The app re-registers on next launch.
    return [];
  }
}
