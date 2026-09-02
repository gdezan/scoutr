import { createHash } from "node:crypto";

/**
 * Short sha256 identity pi-workflow stores on `run.json.sessionId`.
 * Hash Scoutr's `SessionKey.path` the same way so a live parent joins its children.
 */
export function piWorkflowSessionIdentity(value: string): string {
  return createHash("sha256").update(value).digest("hex").slice(0, 24);
}
