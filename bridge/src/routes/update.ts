import { execFile } from "node:child_process";
import { join } from "node:path";
import { ApkBuilder } from "../apk.js";
import { BridgeError } from "../errors.js";
import { gitRepoRoot } from "../review.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

/**
 * Phone-triggered self-update, over the tailnet only — no adb, so no
 * `adb pair` and no USB cable. Four authenticated endpoints behind the
 * existing bearer token (the dispatcher enforces auth):
 *
 *   GET  /api/update/status?commit=…&version=…&dirty=…  -> host identity +
 *        updateAvailable, computed by the shared scripts/version.mjs.
 *   POST /api/update/apk/build   -> start a host-side gradle build (202).
 *   GET  /api/update/apk/status  -> host identity + build state, for polling.
 *   GET  /api/update/apk         -> stream the built APK to the phone.
 *
 * The phone installs the downloaded bytes itself through PackageInstaller,
 * which is why the build and the install are separate steps here: the bridge's
 * job ends at "here are the bytes".
 *
 * App-only install: this builds the app, never the bridge, because a bridge
 * restart would kill the request mid-flight. Deploys stay a host-side
 * `make deploy-bridge`.
 */

const VERSION_SCRIPT = "scripts/version.mjs";
const EXEC_TIMEOUT_MS = 10_000;
const APK_CONTENT_TYPE = "application/vnd.android.package-archive";

export interface HostIdentity {
  version: string;
  versionCode: number;
  commit: string;
  dirty: boolean;
  buildTime: string;
}

function execCapture(cmd: string, args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(
      cmd,
      args,
      { encoding: "utf8", maxBuffer: 1024 * 1024, timeout: EXEC_TIMEOUT_MS, env: process.env },
      (error, stdout, stderr) => {
        if (error) {
          const code = (error as NodeJS.ErrnoException).code;
          const detail =
            code === "ENOENT"
              ? `${cmd} not found`
              : (error as Error & { killed?: boolean }).killed
                ? `${cmd} timed out`
                : (stderr.trim() || error.message || `${cmd} failed`);
          reject(new BridgeError(detail, 502));
          return;
        }
        resolve(stdout);
      },
    );
  });
}

async function checkoutRoot(): Promise<string> {
  const root = await gitRepoRoot(process.cwd());
  if (!root) throw new BridgeError("bridge is not running inside a git checkout", 500);
  return root;
}

async function hostIdentity(): Promise<HostIdentity> {
  const root = await checkoutRoot();
  const stdout = await execCapture(process.execPath, [join(root, VERSION_SCRIPT), "--json"]);
  let parsed: unknown;
  try {
    parsed = JSON.parse(stdout);
  } catch {
    throw new BridgeError("version script returned invalid JSON", 502);
  }
  const identity = parsed as Partial<Record<keyof HostIdentity, unknown>>;
  return {
    version: String(identity.version ?? "0.0.0"),
    versionCode: Number(identity.versionCode ?? 0),
    commit: String(identity.commit ?? ""),
    dirty: Boolean(identity.dirty),
    buildTime: String(identity.buildTime ?? ""),
  };
}

/**
 * The routes, parameterized by the builder so tests can drive the state
 * machine without spawning gradle. Production wires the process-wide builder.
 */
export function createUpdateRoutes(builder: ApkBuilder): Route[] {
  async function updateStatus(ctx: RouteContext): Promise<RouteResult> {
    const host = await hostIdentity();
    const installed = {
      version: ctx.query.get("version")?.trim() ?? "",
      commit: ctx.query.get("commit")?.trim() ?? "",
      dirty: ctx.query.get("dirty")?.trim() === "true",
    };
    // Commit-based gating: semver is display-only. A dirty host tree also counts
    // as "something new" because the app cannot represent uncommitted changes.
    const updateAvailable = host.commit !== installed.commit || host.dirty;
    return { status: 200, body: { ok: true, host, installed, updateAvailable } };
  }

  async function apkBuild(): Promise<RouteResult> {
    const root = await checkoutRoot();
    const { commit, version, versionCode } = await hostIdentity();
    builder.start(root, { commit, version, versionCode });
    return { status: 202, body: { ok: true, build: builder.status } };
  }

  async function apkStatus(): Promise<RouteResult> {
    return { status: 200, body: { ok: true, host: await hostIdentity(), build: builder.status } };
  }

  function apkDownload(): RouteResult {
    const artifact = builder.readyArtifact();
    if (artifact === null) {
      const { state, error } = builder.status;
      if (state === "building") throw new BridgeError("APK build still running", 409);
      if (state === "failed") throw new BridgeError(error ?? "APK build failed", 409);
      throw new BridgeError("no APK has been built yet", 409);
    }
    return {
      status: 200,
      body: null,
      file: {
        path: artifact.path,
        size: artifact.size,
        contentType: APK_CONTENT_TYPE,
        filename: `scoutr-${artifact.version}.apk`,
      },
    };
  }

  return [
    { method: "GET", path: "/api/update/status", handle: updateStatus },
    { method: "POST", path: "/api/update/apk/build", handle: apkBuild },
    { method: "GET", path: "/api/update/apk/status", handle: apkStatus },
    { method: "GET", path: "/api/update/apk", handle: apkDownload },
  ];
}

/** One builder per bridge process: one checkout, one gradle build at a time. */
export const updateRoutes: Route[] = createUpdateRoutes(new ApkBuilder());
