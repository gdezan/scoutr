import { readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve, sep } from "node:path";

export interface DirListing {
  /** Absolute, resolved directory the listing is for. */
  path: string;
  /** Child directory names, sorted, symlinks not followed. */
  dirs: string[];
}

export class DirListingError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DirListingError";
  }
}

/**
 * List the immediate subdirectories of `requested` (resolved against
 * `baseRoot`), refusing to go outside the root. Root defaults to the user's
 * home so the phone can only browse the user's own files.
 */
export function listDirs(requested: string, baseRoot = homedir()): DirListing {
  const root = resolve(baseRoot);
  const target = resolve(requested);
  if (target !== root && !target.startsWith(root + sep)) {
    throw new DirListingError("path outside allowed root");
  }
  let stat;
  try {
    stat = statSync(target);
  } catch {
    throw new DirListingError("no such directory");
  }
  if (!stat.isDirectory()) {
    throw new DirListingError("not a directory");
  }
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

export function rootWithTrailingSep(root = homedir()): string {
  const r = resolve(root);
  return r.endsWith(sep) ? r : r + sep;
}

/** Convenience for callers that want a path joined to the home root. */
export function homeJoin(...parts: string[]): string {
  return join(homedir(), ...parts);
}
