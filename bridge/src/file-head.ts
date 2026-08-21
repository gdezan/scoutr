import { closeSync, openSync, readSync, statSync } from "node:fs";

/** Maximum bytes returned by a single working-tree file read. */
export const FILE_HEAD_MAX_BYTES = 256 * 1024;

export interface FileHead {
  content: string;
  truncated: boolean;
  binary: boolean;
  exists: boolean;
}

export interface FilePage extends FileHead {
  /** Byte offset used for this page. */
  offset: number;
  /** Byte offset for the next page, or null when this is the final page. */
  nextOffset: number | null;
  /** Total file size in bytes. */
  totalBytes: number;
}

interface CappedText {
  text: string;
  truncated: boolean;
}

interface CappedBuffer {
  text: string;
  bytes: number;
  truncated: boolean;
}

export function capUtf8(text: string, maxBytes: number): CappedText {
  const capped = capUtf8Buffer(Buffer.from(text, "utf8"), maxBytes);
  return { text: capped.text, truncated: capped.truncated };
}

function capUtf8Buffer(data: Buffer, maxBytes: number): CappedBuffer {
  if (data.length <= maxBytes) return { text: data.toString("utf8"), bytes: data.length, truncated: false };
  const bytes = completeUtf8Prefix(data, maxBytes);
  return { text: data.subarray(0, bytes).toString("utf8"), bytes, truncated: true };
}

function completeUtf8Prefix(data: Buffer, maxBytes: number): number {
  const limit = Math.min(data.length, maxBytes);
  let continuationBytes = 0;
  while (continuationBytes < limit && isUtf8Continuation(data[limit - continuationBytes - 1]!)) {
    continuationBytes++;
  }
  const leadIndex = limit - continuationBytes - 1;
  if (leadIndex < 0) return limit;
  const sequenceBytes = utf8SequenceBytes(data[leadIndex]!);
  return sequenceBytes > continuationBytes + 1 ? leadIndex : limit;
}

function isUtf8Continuation(byte: number): boolean {
  return (byte & 0xc0) === 0x80;
}

function utf8SequenceBytes(byte: number): number {
  if (byte <= 0x7f) return 1;
  if (byte >= 0xc2 && byte <= 0xdf) return 2;
  if (byte >= 0xe0 && byte <= 0xef) return 3;
  if (byte >= 0xf0 && byte <= 0xf4) return 4;
  return 1;
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
 * Read one bounded UTF-8 page from a regular file. Missing paths and non-files
 * are represented as exists:false; filesystem errors other than ENOENT/ENOTDIR
 * propagate so callers can report an unreadable file instead of hiding it.
 */
export function readFilePage(file: string, offset = 0, maxBytes = FILE_HEAD_MAX_BYTES): FilePage {
  if (!Number.isSafeInteger(offset) || offset < 0) throw new RangeError("invalid file offset");
  if (!Number.isSafeInteger(maxBytes) || maxBytes <= 0) throw new RangeError("invalid file page size");

  let size: number;
  try {
    const info = statSync(file);
    if (!info.isFile()) return missingFilePage(offset);
    size = info.size;
  } catch (error) {
    if (isMissingFileError(error)) return missingFilePage(offset);
    throw error;
  }

  const pageOffset = Math.min(offset, size);
  let fd: number;
  try {
    fd = openSync(file, "r");
  } catch (error) {
    if (isMissingFileError(error)) return missingFilePage(offset);
    throw error;
  }
  try {
    const data = Buffer.alloc(Math.min(size - pageOffset, maxBytes + 1));
    const read = readSync(fd, data, 0, data.length, pageOffset);
    const bytes = data.subarray(0, read);
    if (pageOffset === 0 && isBinaryBuffer(bytes)) {
      return { content: "", truncated: false, binary: true, exists: true, offset: pageOffset, nextOffset: null, totalBytes: size };
    }
    const capped = capUtf8Buffer(bytes, maxBytes);
    const nextOffset = pageOffset + capped.bytes;
    return {
      content: capped.text,
      truncated: nextOffset < size,
      binary: false,
      exists: true,
      offset: pageOffset,
      nextOffset: nextOffset < size ? nextOffset : null,
      totalBytes: size,
    };
  } finally {
    closeSync(fd);
  }
}

/** Read the first page while preserving the original head-read response shape. */
export function readFileHead(file: string): FileHead {
  const page = readFilePage(file);
  return {
    content: page.content,
    truncated: page.truncated,
    binary: page.binary,
    exists: page.exists,
  };
}

function missingFilePage(offset: number): FilePage {
  return { content: "", truncated: false, binary: false, exists: false, offset, nextOffset: null, totalBytes: 0 };
}

export function isMissingFileError(cause: unknown): boolean {
  // SAFETY: only Error instances carry the node errno `code`; checking the
  // code after the instanceof guard is what distinguishes a missing file
  // (ENOENT/ENOTDIR) from any other filesystem failure.
  return cause instanceof Error && "code" in cause && ((cause as NodeJS.ErrnoException).code === "ENOENT" || (cause as NodeJS.ErrnoException).code === "ENOTDIR");
}
