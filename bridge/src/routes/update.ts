import { execFile, spawn } from "node:child_process";
import { homedir } from "node:os";
import { join } from "node:path";
import { BridgeError } from "../errors.js";
import { gitRepoRoot } from "../review.js";
import type { Route, RouteContext, RouteResult } from "./types.js";

/**
 * Phone-triggered self-update. Two authenticated endpoints behind the existing
 * bearer token (the dispatcher enforces auth) plus the tailnet TLS front:
 *
 *   GET  /api/update/status?commit=…&version=…&dirty=…  -> host identity +
 *        updateAvailable, computed by the shared scripts/version.mjs.
 *   POST /api/update/install { deviceModel }             -> resolve the adb
 *        device and spawn scripts/install-app.sh detached (fire-and-forget).
 *
 * App-only install: this runs install-app.sh, never `make release`, because a
 * bridge restart would kill the request mid-flight. Deploys stay a host-side
 * `make deploy-bridge`.
 */

const VERSION_SCRIPT = "scripts/version.mjs";
const INSTALL_SCRIPT = "scripts/install-app.sh";
const EXEC_TIMEOUT_MS = 10_000;

export interface HostIdentity {
  version: string;
  versionCode: number;
  commit: string;
  dirty: boolean;
  buildTime: string;
}

interface ExecCapture {
  stdout: string;
  stderr: string;
}

function execCapture(cmd: string, args: string[]): Promise<ExecCapture> {
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
        resolve({ stdout, stderr });
      },
    );
  });
}

/** adb lives under the SDK, which is not on the bridge's minimal PATH. */
function adbPath(): string {
  const sdkRoot = process.env.ANDROID_HOME?.trim() || join(homedir(), "Android", "sdk");
  return join(sdkRoot, "platform-tools", "adb");
}

async function checkoutRoot(): Promise<string> {
  const root = await gitRepoRoot(process.cwd());
  if (!root) throw new BridgeError("bridge is not running inside a git checkout", 500);
  return root;
}

async function hostIdentity(): Promise<HostIdentity> {
  const root = await checkoutRoot();
  const { stdout } = await execCapture(process.execPath, [join(root, VERSION_SCRIPT), "--json"]);
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

interface AdbDevice {
  serial: string;
  model: string | null;
}

async function listPhysicalDevices(): Promise<AdbDevice[]> {
  const { stdout } = await execCapture(adbPath(), ["devices", "-l"]);
  const devices: AdbDevice[] = [];
  for (const line of stdout.split("\n")) {
    const fields = line.trim().split(/\s+/);
    const serial = fields[0];
    const state = fields[1];
    if (!serial || state !== "device") continue;
    if (serial.startsWith("emulator-")) continue;
    const model = fields.slice(2).find((field) => field.startsWith("model:"))?.slice("model:".length) ?? null;
    devices.push({ serial, model });
  }
  return devices;
}

export function normalizeModel(model: string): string {
  return model.replace(/[^a-z0-9]/gi, "").toLowerCase();
}

/**
 * Pick "the current phone" without guessing: one physical device wins;
 * otherwise the app's Build.MODEL must uniquely match a device, else fail.
 * Pure (no adb I/O) so the disambiguation matrix is unit-testable.
 */
export function pickDevice(devices: AdbDevice[], deviceModel: string | undefined): string {
  if (devices.length === 0) throw new BridgeError("no physical device attached to adb", 409);
  if (devices.length === 1) return devices[0]!.serial;
  if (!deviceModel) {
    throw new BridgeError(
      `multiple physical devices attached (${devices.map((d) => d.serial).join(", ")}) and no device model was provided`,
      409,
    );
  }
  const want = normalizeModel(deviceModel);
  const matches = devices.filter((device) => device.model !== null && normalizeModel(device.model!) === want);
  if (matches.length === 1) return matches[0]!.serial;
  throw new BridgeError(
    `cannot disambiguate device: ${devices.length} physical devices attached, none uniquely matches model ${deviceModel}`,
    409,
  );
}

async function resolveDevice(deviceModel: string | undefined): Promise<string> {
  return pickDevice(await listPhysicalDevices(), deviceModel);
}

async function startInstall(serial: string): Promise<void> {
  const root = await checkoutRoot();
  const child = spawn("bash", [join(root, INSTALL_SCRIPT), "--serial", serial], {
    detached: true,
    stdio: "ignore",
    env: process.env,
  });
  // Fire-and-forget: adb install -r kills the app mid-install, so the request
  // must complete before the install finishes. The script's own `am start`
  // relaunches the app afterwards.
  child.unref();
}

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

async function updateInstall(ctx: RouteContext): Promise<RouteResult> {
  const deviceModel = ctx.body.deviceModel?.trim() || undefined;
  const serial = await resolveDevice(deviceModel);
  await startInstall(serial);
  return { status: 202, body: { ok: true, started: true, serial } };
}

export const updateRoutes: Route[] = [
  { method: "GET", path: "/api/update/status", handle: updateStatus },
  { method: "POST", path: "/api/update/install", handle: updateInstall },
];
