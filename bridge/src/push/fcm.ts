/**
 * FCM HTTP v1 transport for contentless push.
 *
 * A legacy ping carries only `kind` and `paneId`; a generation-qualified
 * ping adds the bridge `hostId` and app-local `profileGeneration`. Neither
 * carries a `notification` block or agent-identifying text. Two things follow
 * from that: no notification content transits Google's infrastructure, and
 * Android always hands the message to the app rather than auto-posting a tray
 * item, so `onMessageReceived` fires even when Scoutr is backgrounded. The app
 * wakes on the ping and fetches the detail from the bridge over the tailnet.
 */

import { readFile } from "node:fs/promises";

export type PingKind = "blocked" | "resolve" | "done";

/** One registered phone. */
export interface PushDevice {
  token: string;
  /** Null/omitted means this is a registration from an older app version. */
  profileGeneration?: string | null;
  updatedAtMs: number;
}

/** Device tokens persisted at <configDir>/devices.json, mode 0600. */
export interface DeviceRegistry {
  list(): readonly PushDevice[];
  register(token: string, profileGeneration?: string): Promise<void>;
  unregister(token: string): Promise<void>;
}

/**
 * Sends one contentless ping. Returns tokens FCM rejected as unregistered.
 * `profileGeneration` is per registration; the publisher sends registration
 * records independently so one bridge event can target multiple app epochs.
 */
export interface FcmSender {
  send(
    tokens: readonly string[],
    kind: PingKind,
    paneId: string,
    hostId: string,
    profileGeneration?: string | null,
  ): Promise<string[]>;
}

/** The wire form Android sends for a current, generation-qualified profile. */
export function isProfileGeneration(value: string | null | undefined): value is string {
  return value != null && /^[1-9][0-9]*$/.test(value);
}

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const SEND_TIMEOUT_MS = 10_000;

/**
 * A `blocked` ping must wake a dozing phone, so it goes out high priority with
 * a short life — an alert nobody saw for 15 minutes is noise, not news. A
 * `resolve` only clears a notification, so it rides normal priority and lives
 * long enough to catch a phone that was off the network.
 */
const DELIVERY = {
  blocked: { priority: "high", ttl: "900s" },
  done: { priority: "high", ttl: "900s" },
  resolve: { priority: "normal", ttl: "3600s" },
};

export interface HttpV1SenderOptions {
  /** Firebase project id, from the service account's `project_id`. */
  projectId: string;
  /** Mints an OAuth2 bearer token for the FCM scope. */
  accessToken: () => Promise<string>;
  /** Injectable for tests; defaults to global fetch. */
  fetchImpl?: typeof fetch;
}

export class HttpV1FcmSender implements FcmSender {
  private readonly projectId: string;
  private readonly accessToken: () => Promise<string>;
  private readonly fetchImpl: typeof fetch;

  constructor(options: HttpV1SenderOptions) {
    this.projectId = options.projectId;
    this.accessToken = options.accessToken;
    this.fetchImpl = options.fetchImpl ?? fetch;
  }

  async send(
    tokens: readonly string[],
    kind: PingKind,
    paneId: string,
    hostId: string,
    profileGeneration?: string | null,
  ): Promise<string[]> {
    if (tokens.length === 0) return [];
    let bearer: string;
    try {
      bearer = await this.accessToken();
    } catch (error) {
      // Push is best-effort; a credential problem must never break the feed.
      console.error(`[fcm] could not mint an access token: ${describe(error)}`);
      return [];
    }
    const stale = await Promise.all(
      tokens.map((token) => this.sendOne(token, kind, paneId, hostId, profileGeneration, bearer)),
    );
    return stale.filter((token): token is string => token !== null);
  }

  /** Returns the device token when FCM says it is dead, otherwise null. */
  private async sendOne(
    token: string,
    kind: PingKind,
    paneId: string,
    hostId: string,
    profileGeneration: string | null | undefined,
    bearer: string,
  ): Promise<string | null> {
    const { priority, ttl } = DELIVERY[kind];
    const data = profileGeneration != null
      ? { kind, hostId, profileGeneration, paneId }
      : { kind, paneId };
    const body = JSON.stringify({
      message: {
        token,
        data,
        android: { priority, ttl },
      },
    });
    try {
      const response = await this.fetchImpl(
        `https://fcm.googleapis.com/v1/projects/${this.projectId}/messages:send`,
        {
          method: "POST",
          headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
          body,
          // A hung FCM endpoint must not pin a socket forever.
          signal: AbortSignal.timeout(SEND_TIMEOUT_MS),
        },
      );
      if (response.ok) return null;
      const text = await response.text().catch(() => "");
      // Never log the token itself — it identifies the user's device.
      console.error(`[fcm] send failed (${response.status}): ${text.slice(0, 300)}`);
      return isUnregistered(response.status, text) ? token : null;
    } catch (error) {
      console.error(`[fcm] send failed: ${describe(error)}`);
      return null;
    }
  }
}

/**
 * The silent-death mode this guards against: an uninstalled or reset app
 * leaves a token that accepts nothing forever. FCM reports it as 404, or as
 * UNREGISTERED in the error body.
 */
function isUnregistered(status: number, body: string): boolean {
  return status === 404 || body.includes("UNREGISTERED");
}

function describe(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

/**
 * Builds a sender from a service-account JSON key file. The project id comes
 * from the file rather than a network lookup, so a misconfigured path fails
 * here at startup instead of on the first blocked agent.
 */
export async function createFcmSender(serviceAccountPath: string): Promise<FcmSender> {
  // SAFETY: the service-account JSON is validated by google-auth-library; the
  // cast reads only the required `project_id` string field.
  const parsed = JSON.parse(await readFile(serviceAccountPath, "utf8")) as { project_id?: string };
  if (!parsed.project_id) {
    throw new Error(`${serviceAccountPath} has no "project_id"; is it a Firebase service-account key?`);
  }
  const { GoogleAuth } = await import("google-auth-library");
  const auth = new GoogleAuth({ keyFilename: serviceAccountPath, scopes: [FCM_SCOPE] });
  return new HttpV1FcmSender({
    projectId: parsed.project_id,
    accessToken: async () => {
      const client = await auth.getClient();
      const { token } = await client.getAccessToken();
      if (!token) throw new Error("google-auth-library returned no access token");
      return token;
    },
  });
}
