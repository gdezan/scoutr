/**
 * Scoutr Android-to-bridge API protocol supported by this bridge.
 * Additive optional fields keep the current number. Removing or renaming a
 * required field, changing required semantics, or requiring new command or
 * response behavior bumps it.
 *
 * Features are additive, but a capability the app *requires* at handshake
 * (`REQUIRED_SCOUTR_API_FEATURES` in the app) is a compatibility gate too:
 * withdrawing an advertised feature is a breaking change, not a removal.
 */
export const SCOUTR_API_PROTOCOL = 2;

/** Additive capabilities advertised by the current Scoutr API protocol. */
export const SCOUTR_API_FEATURES = [
  "terminal.v1",
  "asks.v2",
  "update.pull.v1",
  "session-model.v3",
  // One-shot session commands (steer/slash/ask answer/dismiss/send-text) are
  // served over HTTP. The app requires this before issuing them; it never
  // falls back to the legacy /ws command frames.
  "commands.http.v1",
  // /api/health carries the bridge installation's stable opaque hostId; the
  // app persists it with the pairing to namespace device-local metadata.
  "host-identity.v1",
] as const;
