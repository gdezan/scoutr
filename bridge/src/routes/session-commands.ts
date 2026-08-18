import {
  answerSessionAsk,
  dismissSessionAsk,
  runSlashCommand,
  sendSessionText,
  steerSession,
  type AnswerAskRequest,
  type AskAnswerInput,
} from "../commands.js";
import { CommandError } from "../errors.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

/**
 * One-shot session commands over ordinary authenticated HTTP — the
 * `commands.http.v1` surface the app uses instead of opening a WebSocket per
 * mutation.
 *
 * These handlers are thin on purpose: decode the path params, hand the body
 * to the shared operation in `src/commands.ts`, and shape the `{ ok: true }`
 * reply. Every length, control-character, and ask rule lives in that shared
 * operation, so this module must never grow validation of its own. Errors
 * come back through the dispatcher with the operation's deliberate status
 * (400 invalid input, 409 an ask that no longer matches the pane, 401 auth,
 * 502 a herdr/backend failure, 404 a pane that is not in the live topology).
 *
 * `POST /api/sessions/:paneId/send-text` has no app caller: it exists for
 * parity with the legacy `send_text` frame during the rollout, so a reader
 * should not treat it as live app API.
 */
export const sessionCommandsRoutes: Route[] = [
  { method: "POST", path: "/api/sessions/:paneId/steer", handle: steerRoute },
  { method: "POST", path: "/api/sessions/:paneId/slash-command", handle: slashCommandRoute },
  { method: "POST", path: "/api/sessions/:paneId/send-text", handle: sendTextRoute },
  { method: "POST", path: "/api/sessions/:paneId/asks/:callId/answer", handle: answerAskRoute },
  // A plain blocked prompt has no ask to name. It gets its own literal route
  // rather than an empty `:callId` segment, which no URL can express.
  { method: "POST", path: "/api/sessions/:paneId/asks/answer", handle: answerPromptRoute },
  { method: "POST", path: "/api/sessions/:paneId/asks/dismiss", handle: dismissAskRoute },
];

/** A `:param` as the caller wrote it; a malformed escape is a 400, not a crash. */
function decodeParam(ctx: RouteContext, name: string): string {
  const raw = ctx.params[name] ?? "";
  try {
    return decodeURIComponent(raw);
  } catch {
    throw new CommandError(`invalid ${name}`);
  }
}

/** `{ text }` bodies: present and a string, or an actionable 400. */
function bodyText(ctx: RouteContext): string {
  const { text } = ctx.body;
  if (typeof text !== "string") throw new CommandError("text must be a string");
  return text;
}

async function steerRoute(ctx: RouteContext): Promise<RouteResult> {
  const paneId = decodeParam(ctx, "paneId");
  await steerSession(ctx.deps, paneId, bodyText(ctx));
  return { status: 200, body: { ok: true, paneId } };
}

async function slashCommandRoute(ctx: RouteContext): Promise<RouteResult> {
  const paneId = decodeParam(ctx, "paneId");
  const text = await runSlashCommand(ctx.deps, paneId, bodyText(ctx));
  return { status: 200, body: { ok: true, paneId, text } };
}

async function sendTextRoute(ctx: RouteContext): Promise<RouteResult> {
  const paneId = decodeParam(ctx, "paneId");
  await sendSessionText(ctx.deps, paneId, bodyText(ctx));
  return { status: 200, body: { ok: true, paneId } };
}

async function answerAskRoute(ctx: RouteContext): Promise<RouteResult> {
  return await answer(ctx, decodeParam(ctx, "callId"));
}

async function answerPromptRoute(ctx: RouteContext): Promise<RouteResult> {
  return await answer(ctx, undefined);
}

/**
 * Both answer routes deliver the same normalized request; only where the ask
 * identity comes from differs (path segment vs "there is no ask").
 */
async function answer(ctx: RouteContext, callId: string | undefined): Promise<RouteResult> {
  const paneId = decodeParam(ctx, "paneId");
  const body = ctx.body as { answers?: unknown; text?: unknown };
  if (body.answers !== undefined && !Array.isArray(body.answers)) {
    throw new CommandError("answers must be an array");
  }
  if (body.text !== undefined && typeof body.text !== "string") {
    throw new CommandError("text must be a string");
  }
  const request: AnswerAskRequest = {
    paneId,
    callId,
    answers: body.answers as AskAnswerInput[] | undefined,
    text: body.text as string | undefined,
  };
  const answered = await answerSessionAsk(ctx.deps, request);
  return { status: 200, body: { ok: true, paneId, callId: answered } };
}

async function dismissAskRoute(ctx: RouteContext): Promise<RouteResult> {
  const paneId = decodeParam(ctx, "paneId");
  await dismissSessionAsk(ctx.deps, paneId);
  return { status: 200, body: { ok: true, paneId } };
}
