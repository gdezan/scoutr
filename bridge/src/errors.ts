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
