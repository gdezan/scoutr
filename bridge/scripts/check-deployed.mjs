#!/usr/bin/env node
/**
 * Deployment freshness gate for the self-hosted scoutr bridge.
 *
 * The Android/desktop apps talk to the supervised bridge service (a systemd
 * user unit on Linux, a user LaunchAgent on macOS — `scripts/bridge-service.mjs`
 * owns that difference), which runs the COMPILED `dist/` output — NOT the `tsx src/cli.ts` scratch
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
const serviceHelper = join(root, "..", "scripts", "bridge-service.mjs");

/**
 * Newest mtime under a tree. This walks subdirectories: while it only read the
 * top level, every change confined to `agents/`, `routes/`, `herdr/`, or
 * `terminal/` — which is most of them — was invisible to the freshness gate.
 */
function newestMtime(dir, ext) {
  let newest = 0;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    const m = entry.isDirectory() ? newestMtime(path, ext) : entry.name.endsWith(ext) ? statSync(path).mtimeMs : 0;
    if (m > newest) newest = m;
  }
  return newest;
}

/**
 * Does the supervised PID hold the listening socket? `ss` is Linux-only, so
 * Darwin proves the same thing with `lsof`; both answer "which pid listens on
 * this port", which is the invariant that catches a stray manual bridge.
 */
function servicePidOwnsPort(pid, port) {
  if (!pid) return false;
  if (process.platform === "darwin") {
    const out = execFileSync("lsof", ["-nP", "-a", "-p", pid, `-iTCP:${port}`, "-sTCP:LISTEN"], {
      encoding: "utf8",
    });
    return out.split("\n").some((line) => line.trim().split(/\s+/)[1] === pid);
  }
  const listeners = execFileSync("ss", ["-ltnp"], { encoding: "utf8" });
  return listeners.split("\n").some((line) => {
    const fields = line.trim().split(/\s+/);
    return fields[0] === "LISTEN" && fields[3]?.endsWith(`:${port}`) && line.includes(`pid=${pid},`);
  });
}

function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function waitForServicePort(pid, port, timeoutMs = 8000, intervalMs = 200) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (servicePidOwnsPort(pid, port)) return true;
    sleepSync(intervalMs);
  }
  return servicePidOwnsPort(pid, port);
}

function redactSecrets(message) {
  return String(message).replace(/Bearer\s+\S+/g, "Bearer <redacted>");
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
// otherwise make the health probe succeed while the service crash-loops.
let service = null;
try {
  service = JSON.parse(
    execFileSync(process.execPath, [serviceHelper, "status", "--json"], { encoding: "utf8" }),
  );
  console.log(`service manager ${service.manager} (${service.service})`);
  if (!service.active || !service.pid) {
    fail(
      `${service.service} is not running (${JSON.stringify(service.detail)}, pid=${service.pid ?? "none"})` +
        (service.problem ? ` — ${service.problem}` : ""),
    );
  }
  if (!service.installed) {
    fail(`no service definition at ${service.definitionPath} — run \`node scripts/bridge-service.mjs install\``);
  } else if (!service.definitionCurrent) {
    // Only a warning: a hand-tuned but working definition is legitimate, and
    // the freshness invariants below still decide whether the deploy is good.
    console.warn(
      `WARN: ${service.definitionPath} differs from the generated definition ` +
        "(`node scripts/bridge-service.mjs install` regenerates it)",
    );
  }
  if (!service.startedAtMs) {
    fail(`cannot read ${service.service} start time`);
  } else {
    console.log(`service started ${new Date(service.startedAtMs).toISOString()}`);
    if (service.startedAtMs < newestDist) {
      fail(`${service.service} started BEFORE dist/ was built — restart it (\`npm run deploy\`)`);
    }
  }
} catch (e) {
  fail(`cannot query the bridge service: ${e.message}`);
}
const serviceMainPid = service?.pid ? String(service.pid) : "";

// Probe the real local bridge (the process the apps reach through the
// configured exposure). systemd reports active before Node binds the port
// (herdr connect happens first), so wait out that listen race.
try {
  const cfg = JSON.parse(readFileSync(join(homedir(), ".config/scoutr/config.json"), "utf8"));
  const port = cfg.port ?? 8737;
  const token = cfg.token;
  if (!waitForServicePort(serviceMainPid, port)) {
    fail(`bridge service PID ${serviceMainPid || "none"} does not own listening port ${port}`);
  } else {
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
  }
} catch (e) {
  fail(`real bridge probe failed: ${redactSecrets(e.message)}`);
}

if (failed) {
  console.error(
    "\nDeployed bridge is STALE or DOWN — the apps talk to this process, not to tsx.\n" +
      "Fix it (`npm run deploy`) before verifying anything in the app.",
  );
  process.exit(1);
}
console.log("\nDeployed bridge is current. OK.");
