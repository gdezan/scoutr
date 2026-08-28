import { createReadStream } from "node:fs";
import type { ServerResponse } from "node:http";
import type { RouteFile } from "./routes/types.js";

/**
 * A satisfiable open-ended byte range, resolved against a known file size.
 * `end` is inclusive, matching the Content-Range wire form.
 */
export interface ByteRange {
  start: number;
  end: number;
}

/**
 * Parses the one Range form the update client ever sends: `bytes=N-`.
 *
 * Everything else — absent, malformed, a non-bytes unit, `bytes=N-M`, or a
 * multi-range list — resolves to `null`, meaning "serve the whole file". A
 * malformed range is deliberately not an error: RFC 9110 lets a server ignore
 * a Range it does not understand, and a plain 200 is always a correct answer.
 * `null` is distinct from "unsatisfiable", which is reported separately.
 */
export function parseByteRange(header: string | undefined, size: number): ByteRange | "unsatisfiable" | null {
  if (!header) return null;
  const match = /^bytes=(\d+)-$/.exec(header.trim());
  if (!match) return null;
  const start = Number(match[1]);
  if (!Number.isSafeInteger(start)) return null;
  if (start >= size) return "unsatisfiable";
  return { start, end: size - 1 };
}

/** What a sendFile call actually put on the wire, for the request metric. */
export interface SentFile {
  status: number;
  bytes: number;
}

/**
 * Streams a route's file straight from disk (the update APK is far too large
 * to serialize through JSON). Headers go out before the first byte is read, so
 * a mid-stream read error can only destroy the socket — the client sees a
 * truncated body, which its content-length check catches.
 *
 * Resume lives here rather than in the route layer because Range is a
 * transport concern: RouteContext has no access to request headers by design.
 */
export function sendFile(response: ServerResponse, file: RouteFile, range?: string): Promise<SentFile> {
  const requested = parseByteRange(range, file.size);
  const common = {
    "content-type": file.contentType,
    "content-disposition": `attachment; filename="${file.filename}"`,
    "cache-control": "no-store",
    // Advertised on every response, not just partial ones: that is how a
    // client learns it may resume a transfer it has not started yet.
    "accept-ranges": "bytes",
  };

  if (requested === "unsatisfiable") {
    response.writeHead(416, { ...common, "content-range": `bytes */${file.size}`, "content-length": 0 });
    response.end();
    return Promise.resolve({ status: 416, bytes: 0 });
  }

  const status = requested === null ? 200 : 206;
  const start = requested === null ? 0 : requested.start;
  const bytes = file.size - start;
  const headers =
    requested === null
      ? { ...common, "content-length": bytes }
      : {
          ...common,
          "content-range": `bytes ${requested.start}-${requested.end}/${file.size}`,
          "content-length": bytes,
        };

  return new Promise((resolve, reject) => {
    response.writeHead(status, headers);
    const stream = createReadStream(file.path, { start });
    stream.on("error", (error) => {
      response.destroy();
      reject(error);
    });
    response.on("close", () => stream.destroy());
    stream.pipe(response);
    response.on("finish", () => resolve({ status, bytes }));
  });
}
