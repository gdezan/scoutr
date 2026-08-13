#!/usr/bin/env node
/**
 * Deployment freshness gate for the self-hosted cockpit bridge.
 *
 * The Android/desktop apps talk to the systemd `cockpit-bridge.service`,
 * which runs the COMPILED `dist/` output — NOT the `tsx src/cli.ts` scratch
 * bridge that development and tests use. A `dist/` built before a source
 * change, or a service that was never restarted after a build, silently
 * serves stale code to the real apps. That happened with the review
 * allow-list fixes: `dist/` was built 17:41, fixes 5/13 landed later, and
 * users kept getting `bridge 403: path outside allowed repo roots` while
 * every source-level test passed.
 *
 * Every bridge change ends with:
 *   npm run deploy          # build dist + restart the service
 *   npm run check:deployed  # this script (also run by `deploy`)
 *
 * Fails if (1) dist/ is older than any src file, (2) the service started
 * before dist/ was built, or (3) the real local bridge does not answer
 * /api/health with the deployed token.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { homedir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

function newestMtime(dir, ext) {
  let newest = 0;
  for (const f of readdirSync(dir)) {
    if (!f.endsWith(ext)) continue;
    const m = statSync(join(dir, f)).mtimeMs;
    if (m > newest) newest = m;
  }
  return newest;
}

let failed = false;
const fail = (msg) => {
  console.error(`FAIL: ${msg}`);
  failed = true;
};

const newestSrc = newestMtime(join(root, "src"), ".ts");
const newestDist = newestMtime(join(root, "dist"), ".js");
console.log(
  `src newest  ${new Date(newestSrc).toISOString()}\n` +
    `dist newest ${new Date(newestDist).toISOString()}`,
);
if (newestDist < newestSrc) {
  fail("dist/ is older than src/ — run `npm run deploy` (build + restart the service)");
}

// The running service must have started after the dist build, else it serves
// the previous build even though dist/ on disk is current. It must also be the
// process that owns the configured listening socket; a stray manual bridge can
// otherwise make the health probe succeed while systemd crash-loops.
let serviceMainPid = "";
try {
  const out = execFileSync(
    "systemctl",
    [
      "--user",
      "show",
      "cockpit-bridge.service",
      "-p",
      "ActiveState",
      "-p",
      "SubState",
      "-p",
      "MainPID",
      "-p",
      "ExecMainStartTimestamp",
    ],
    { encoding: "utf8" },
  );
  const values = Object.fromEntries(
    out
      .trim()
      .split("\n")
      .map((line) => line.split("="))
      .filter(([key]) => key),
  );
  serviceMainPid = values.MainPID ?? "";
  if (values.ActiveState !== "active" || values.SubState !== "running" || !serviceMainPid || serviceMainPid === "0") {
    fail(
      `cockpit-bridge.service is not running (state=${values.ActiveState ?? "unknown"}/${values.SubState ?? "unknown"}, pid=${serviceMainPid || "none"})`,
    );
  }
  const stamp = values.ExecMainStartTimestamp?.trim();
  if (!stamp) {
    fail("cannot read cockpit-bridge.service start time");
  } else {
    const started = Date.parse(stamp);
    console.log(`service started ${stamp}`);
    if (Number.isNaN(started)) {
      fail(`cannot parse cockpit-bridge.service start time: ${stamp}`);
    } else if (started < newestDist) {
      fail("cockpit-bridge.service started BEFORE dist/ was built — restart it (`npm run deploy`)");
    }
  }
} catch (e) {
  fail(`cannot query cockpit-bridge.service: ${e.message}`);
}

// Probe the real local bridge (the process the apps reach via tailscale serve).
try {
  const cfg = JSON.parse(readFileSync(join(homedir(), ".config/cockpit/config.json"), "utf8"));
  const port = cfg.port ?? 8737;
  const token = cfg.token;
  const listeners = execFileSync("ss", ["-ltnp"], { encoding: "utf8" });
  const serviceOwnsPort = listeners.split("\n").some((line) => {
    const fields = line.trim().split(/\s+/);
    return fields[0] === "LISTEN" && fields[3]?.endsWith(`:${port}`) && line.includes(`pid=${serviceMainPid},`);
  });
  if (!serviceOwnsPort) {
    fail(`cockpit-bridge.service PID ${serviceMainPid || "none"} does not own listening port ${port}`);
  }
  const resp = execFileSync(
    "curl",
    ["-s", "-m", "8", "-H", `Authorization: Bearer ${token}`, `http://127.0.0.1:${port}/api/health`],
    { encoding: "utf8" },
  );
  const health = JSON.parse(resp);
  if (!health.ok) {
    fail(`real bridge health check failed: ${resp}`);
  } else {
    console.log(
      `real bridge healthy: ${health.service} v${health.version} (herdr ${health.herdr?.connected ? "connected" : "DISCONNECTED"})`,
    );
  }
} catch (e) {
  fail(`real bridge probe failed: ${e.message}`);
}

if (failed) {
  console.error(
    "\nDeployed bridge is STALE or DOWN — the apps talk to this process, not to tsx.\n" +
      "Fix it (`npm run deploy`) before verifying anything in the app.",
  );
  process.exit(1);
}
console.log("\nDeployed bridge is current. OK.");
