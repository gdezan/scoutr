import { closeSync, openSync, readSync, statSync } from "node:fs";

/** Maximum bytes returned by a working-tree file read. */
export const FILE_HEAD_MAX_BYTES = 256 * 1024;

export interface FileHead {
  content: string;
  truncated: boolean;
  binary: boolean;
  exists: boolean;
}

export function capUtf8(text: string, maxBytes: number): { text: string; truncated: boolean } {
  const bytes = Buffer.from(text, "utf8");
  if (bytes.length <= maxBytes) return { text, truncated: false };
  // Keep the head and strip a trailing partial code point.
  return { text: bytes.subarray(0, maxBytes).toString("utf8").replace(/\uFFFD$/, ""), truncated: true };
}

/** True when the buffer holds binary data (NUL in the first 8 KiB). */
export function isBinaryBuffer(data: Buffer): boolean {
  const probe = Math.min(data.length, 8192);
  for (let i = 0; i < probe; i++) {
    if (data[i] === 0) return true;
  }
  return false;
}

/**
 * Read the head of a regular file. Missing paths and non-files are represented
 * as exists:false; filesystem errors other than ENOENT/ENOTDIR propagate so
 * callers can report an unreadable file instead of hiding it as missing.
 */
export function readFileHead(file: string): FileHead {
  let size: number;
  try {
    const info = statSync(file);
    if (!info.isFile()) return { content: "", truncated: false, binary: false, exists: false };
    size = info.size;
  } catch (error) {
    if (isMissingFileError(error)) return { content: "", truncated: false, binary: false, exists: false };
    throw error;
  }

  let fd: number;
  try {
    fd = openSync(file, "r");
  } catch (error) {
    if (isMissingFileError(error)) return { content: "", truncated: false, binary: false, exists: false };
    throw error;
  }
  try {
    const data = Buffer.alloc(Math.min(size, FILE_HEAD_MAX_BYTES + 1));
    const read = readSync(fd, data, 0, data.length, 0);
    const bytes = data.subarray(0, read);
    if (isBinaryBuffer(bytes)) return { content: "", truncated: false, binary: true, exists: true };
    const capped = capUtf8(bytes.toString("utf8"), FILE_HEAD_MAX_BYTES);
    return { content: capped.text, truncated: capped.truncated, binary: false, exists: true };
  } finally {
    closeSync(fd);
  }
}

export function isMissingFileError(error: unknown): boolean {
  return error instanceof Error && "code" in error && ((error as NodeJS.ErrnoException).code === "ENOENT" || (error as NodeJS.ErrnoException).code === "ENOTDIR");
}
