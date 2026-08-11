import { defaultConfigPath } from "../config.js";
import { readAttachmentBody, storeAttachment, uploadsDir } from "../attachments.js";
import type { Route } from "./types.js";

/**
 * POST /api/attachments — raw binary image upload for the chat composer.
 * Declared `rawBody: true` so the dispatcher skips JSON parsing and hands the
 * unconsumed request stream to the handler (readAttachmentBody enforces its
 * own 10 MiB cap while streaming).
 */
export const attachmentRoutes: Route[] = [
  {
    method: "POST",
    path: "/api/attachments",
    rawBody: true,
    async handle(ctx) {
      const body = await readAttachmentBody(ctx.rawBody ?? noBody());
      const name = ctx.query.get("name") ?? "image.png";
      const filePath = storeAttachment(uploadsDir(defaultConfigPath()), name, body, ctx.contentType ?? "");
      return { status: 201, body: { ok: true, path: filePath } };
    },
  },
];

async function* noBody(): AsyncIterable<Buffer> {}
