import { readdirSync, realpathSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { BridgeError } from "./errors.js";
import { join, resolve, sep } from "node:path";

export interface DirListing {
  /** Absolute, resolved directory the listing is for. */
  path: string;
  /** Child directory names, sorted, symlinks not followed. */
  dirs: string[];
}

export class DirListingError extends BridgeError {
  constructor(message: string) {
    super(message, 400);
    this.name = "DirListingError";
  }
}

/** Resolve an existing directory without allowing access outside the user's home. */
export function resolveAllowedDir(requested: string, baseRoot = homedir()): string {
  let root: string;
  let target: string;
  let stat;
  try {
    root = realpathSync(resolve(baseRoot));
    target = realpathSync(resolve(requested));
    stat = statSync(target);
  } catch {
    throw new DirListingError("no such directory");
  }
  if (target !== root && !target.startsWith(root + sep)) {
    throw new DirListingError("path outside allowed root");
  }
  if (!stat.isDirectory()) {
    throw new DirListingError("not a directory");
  }
  return target;
}

export function listDirs(requested: string, baseRoot = homedir()): DirListing {
  const target = resolveAllowedDir(requested, baseRoot);
  let entries: string[] = [];
  try {
    entries = readdirSync(target, { withFileTypes: true })
      .filter((e) => e.isDirectory() && !e.name.startsWith("."))
      .map((e) => e.name)
      .sort((a, b) => a.localeCompare(b));
  } catch {
    // Unreadable directory (e.g. no permission): treat as empty, not fatal.
  }
  return { path: target, dirs: entries };
}


/** realpath when the path exists, otherwise its resolved form. */
export function canonicalPath(path: string): string {
  try {
    return realpathSync(path);
  } catch {
    return resolve(path);
  }
}
