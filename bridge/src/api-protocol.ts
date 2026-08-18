/**
 * Scoutr Android-to-bridge API protocol supported by this bridge.
 * Additive optional fields keep the current number. Removing or renaming a
 * required field, changing required semantics, or requiring new command or
 * response behavior bumps it.
 */
export const SCOUTR_API_PROTOCOL = 1;

/** Additive capabilities advertised by the current Scoutr API protocol. */
export const SCOUTR_API_FEATURES = ["terminal.v1", "asks.v2", "update.pull.v1"] as const;
