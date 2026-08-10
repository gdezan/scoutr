import { readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { basename, extname, join } from "node:path";

/**
 * Image-attachment uploads for the chat composer.
 *
 * Safety invariants:
 *  - Only image/* content types with an allow-listed extension are accepted.
 *  - Bodies are capped (10 MiB) while streaming, so a huge upload cannot OOM.
 *  - Files land in a dedicated uploads dir next to the bridge config and are
 *    pruned (max count + max total bytes) so the surface stays bounded.
 *  - The returned path is host-absolute; pi's `@path` prompt syntax attaches
 *    it, so the agent can see the image without any extra transport.
 */

export const ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024;
const ATTACHMENTS_MAX_FILES = 200;
const ATTACHMENTS_MAX_TOTAL_BYTES = 200 * 1024 * 1024;
const ALLOWED_EXTENSIONS = new Set([".png", ".jpg", ".jpeg", ".gif", ".webp"]);

export class AttachmentError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
  }
}

export function uploadsDir(configPath: string): string {
  return join(configPath, "..", "uploads");
}

function sanitizeName(name: string): string {
  const base = basename(name).replace(/[^\w.-]+/g, "_").slice(0, 80);
  const ext = extname(base).toLowerCase();
  if (!ALLOWED_EXTENSIONS.has(ext)) throw new AttachmentError("unsupported image type", 400);
  return base;
}

/** Collect the request body up to the cap; rejects with 413 when exceeded. */
export async function readAttachmentBody(
  chunks: AsyncIterable<Buffer>,
  maxBytes = ATTACHMENT_MAX_BYTES,
): Promise<Buffer> {
  const parts: Buffer[] = [];
  let total = 0;
  for await (const chunk of chunks) {
    total += chunk.length;
    if (total > maxBytes) throw new AttachmentError("attachment too large", 413);
    parts.push(chunk);
  }
  if (total === 0) throw new AttachmentError("empty attachment", 400);
  return Buffer.concat(parts);
}

/** Save the body and prune old uploads; returns the host path to attach. */
export function storeAttachment(
  dir: string,
  name: string,
  body: Buffer,
  contentType: string,
): string {
  if (!contentType.startsWith("image/")) throw new AttachmentError("not an image", 400);
  const safe = sanitizeName(name);
  const filePath = join(dir, `${Date.now()}_${Math.random().toString(36).slice(2, 8)}_${safe}`);
  try {
    writeFileSync(filePath, body);
  } catch {
    throw new AttachmentError("could not store attachment", 500);
  }
  pruneUploads(dir);
  return filePath;
}

function pruneUploads(dir: string) {
  let entries: Array<{ path: string; size: number; mtimeMs: number }> = [];
  try {
    entries = readdirSync(dir)
      .map((name) => {
        const full = join(dir, name);
        try {
          const stat = statSync(full);
          return { path: full, size: stat.size, mtimeMs: stat.mtimeMs };
        } catch {
          return null;
        }
      })
      .filter((e): e is { path: string; size: number; mtimeMs: number } => e !== null);
  } catch {
    return; // uploads dir missing: nothing to prune
  }
  entries.sort((a, b) => b.mtimeMs - a.mtimeMs);
  let total = 0;
  for (const entry of entries) total += entry.size;
  // Walk from the oldest entry and drop files until both caps are satisfied,
  // so the newest attachment always survives a prune.
  for (let i = entries.length - 1; i >= 0; i--) {
    const overCount = entries.length > ATTACHMENTS_MAX_FILES;
    const overBytes = total > ATTACHMENTS_MAX_TOTAL_BYTES;
    if (!overCount && !overBytes) break;
    const entry = entries[i]!;
    total -= entry.size;
    entries.splice(i, 1);
    try {
      rmSync(entry.path, { force: true });
    } catch {
      // best-effort prune
    }
  }
}

