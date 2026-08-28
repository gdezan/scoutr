import type { BridgeConfig } from "../config.js";
import { resolveExposure, type ExposureDeps, type ResolvedExposure } from "../exposure.js";

/**
 * The Scoutr-awareness text injected into agent sessions launched or resumed
 * through Scoutr. This module is the one place Scoutr-specific context lives:
 * adapters only know which CLI flag carries it (`--append-system-prompt` for
 * pi and claude; agy has no equivalent and gets nothing), and every word the
 * agent reads about Scoutr is authored here.
 *
 * The text travels inside the launch command, shell-quoted through a PTY, so
 * it must stay a single line and compact. It never carries the bridge token
 * or any credential — only the public host, which is the same host an
 * existing remote-access mechanism such as `tailscale serve` publishes under.
 */

/**
 * Build the injected context from the bridge configuration. Best effort by
 * contract: exposure resolution problems (unconfigured tunnel, missing
 * binary, malformed URL) degrade to the exposure-agnostic wording instead of
 * failing a launch.
 */
/**
 * How long one exposure resolution is reused. Sessions launch often enough that
 * the launch path must not wait on `tailscale status` every time; the text is
 * guidance, so a few minutes of staleness in the host name is harmless. Only
 * the production path (no injected discovery stub) memoizes, keeping tests
 * deterministic.
 */
const CONTEXT_TTL_MS = 5 * 60_000;
let memo: { key: string; text: string; at: number } | null = null;

export async function buildScoutrContext(
  config: BridgeConfig,
  deps: ExposureDeps = {},
): Promise<string> {
  // Same effective value resolveExposure reads (trim + env fallback), so a
  // changed SCOUTR_PUBLIC_HOST cannot serve stale memoized text.
  const key = `${config.exposure?.kind ?? ""}|${
    config.exposure?.publicUrl?.trim() || process.env.SCOUTR_PUBLIC_HOST?.trim() || ""
  }`;
  const now = Date.now();
  if (!deps.discoverTailscaleUrl && memo && memo.key === key && now - memo.at < CONTEXT_TTL_MS) {
    return memo.text;
  }
  let exposure: ResolvedExposure | null = null;
  try {
    exposure = await resolveExposure(config, deps);
  } catch {
    exposure = null;
  }
  const text = scoutrContextText(exposure);
  if (!deps.discoverTailscaleUrl) memo = { key, text, at: now };
  return text;
}

/**
 * Public host of a resolved exposure; null when none is usable.
 *
 * Parsed, not string-munged, so userinfo, path, and query can never ride into
 * the injected text, and a malformed or hostile URL degrades to the
 * exposure-agnostic wording.
 */
export function exposureHost(exposure: ResolvedExposure): string | null {
  if (exposure.loopbackFallback) return null;
  try {
    const { host } = new URL(exposure.publicUrl);
    if (!host || /\s|\p{Cc}/u.test(host)) return null;
    return host;
  } catch {
    return null;
  }
}

/** The context for one exposure result; null means "no usable exposure resolved". */
export function scoutrContextText(exposure: ResolvedExposure | null): string {
  const host = exposure ? exposureHost(exposure) : null;
  const supervision =
    "You are running in a session supervised through Scoutr: the user may be watching and answering " +
    "from the Scoutr mobile app instead of the host terminal. When you need a decision, use your " +
    "structured question tool (e.g. AskUserQuestion) so Scoutr renders it as a card the user answers " +
    "on the phone. Deliver anything the user must see or answer through agent-native channels — " +
    "question cards, shared links, reports — because terminal output can scroll past unseen.";

  const remote =
    "The phone is remote: it reaches this host through " +
    exposureClause(exposure, host) +
    " Publish every link you share (dev server, report, web UI) through the remote-access mechanism " +
    "this host already provides" +
    (exposure?.kind === "tailscale" && host
      ? ` — \`tailscale serve --bg <port>\` publishes it under ${host}`
      : "") +
    " — host-only URLs like localhost never reach the phone. Do not provision new public " +
    "infrastructure or expose new services just to make a link work.";

  return `${supervision} ${remote}`;
}

function exposureClause(exposure: ResolvedExposure | null, host: string | null): string {
  if (exposure?.kind === "tailscale" && host) return `the Tailscale tailnet (this host is ${host}).`;
  if (exposure?.kind === "cloudflare" && host) return `a Cloudflare Tunnel fronting ${host}.`;
  if (exposure?.kind === "custom" && host) return `a configured exposure fronting ${host}.`;
  // No usable exposure resolved: keep the remote warning, promise no mechanism.
  return "a remote-access path configured on this host.";
}
