/**
 * Shared error base for the bridge's HTTP layer.
 *
 * Every feature error class carries an HTTP status; the route dispatcher maps
 * `BridgeError` to its status (anything else to 502) in one place instead of
 * each route branch re-implementing `error instanceof X ? error.status : 502`.
 */
export class BridgeError extends Error {
  constructor(
    message: string,
    /** HTTP status this error maps to. */
    public readonly status = 500,
  ) {
    super(message);
    this.name = "BridgeError";
  }
}

/**
 * An invalid or unusable session command: bad input (400 by default) or a
 * command that no longer matches the pane's live state (409). Shared by the
 * HTTP command routes and the legacy WS adapter so both surfaces reject the
 * same things for the same reasons.
 */
export class CommandError extends BridgeError {
  constructor(message: string, status = 400) {
    super(message, status);
    this.name = "CommandError";
  }
}
