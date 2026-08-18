#!/usr/bin/env node
/**
 * One deployment seam for the supervised scoutr bridge process.
 *
 * The apps talk to a supervised, COMPILED bridge (`dist/cli.js`), never to a
 * `tsx src/cli.ts` scratch process. Which supervisor owns it depends on the
 * host: a systemd user service on Linux, a user LaunchAgent on macOS. This
 * script is the only place that knows the difference, so `npm run deploy`,
 * `scripts/deploy-bridge.sh`, and `bridge/scripts/check-deployed.mjs` can each
 * speak one contract:
 *
 *   node scripts/bridge-service.mjs install
 *   node scripts/bridge-service.mjs restart
 *   node scripts/bridge-service.mjs status --json
 *
 * `status --json` always prints a document and exits 0 (on a supported OS) so
 * callers read the failure out of `active`/`problem` instead of a thrown exec.
 *
 * cloudflared is NOT managed here: the tunnel is owned by Cloudflare's own
 * service tooling, and Scoutr only consumes the public URL it publishes.
 */
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import { homedir, userInfo } from "node:os";
import { delimiter, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export const SYSTEMD_UNIT = "scoutr-bridge.service";
export const LAUNCHD_LABEL = "dev.scoutr.bridge";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

/** Thrown for hosts with no bridge supervisor adapter; never a partial install. */
export class UnsupportedManagerError extends Error {
  constructor(platform) {
    super(
      `no bridge service adapter for platform "${platform}" — supported: linux (systemd user service), darwin (user LaunchAgent).\n` +
        "Run the bridge manually with `node dist/cli.js serve` under your own supervisor.",
    );
    this.name = "UnsupportedManagerError";
    this.platform = platform;
  }
}

export function selectManager(platform) {
  if (platform === "linux") return "systemd";
  if (platform === "darwin") return "launchd";
  throw new UnsupportedManagerError(platform);
}

/**
 * Absolute paths baked into the generated service definition. Everything the
 * supervisor executes is absolute because neither systemd --user nor launchd
 * can be assumed to inherit an interactive shell's PATH.
 */
export function resolveServicePaths({
  platform,
  repoRoot = REPO_ROOT,
  home = homedir(),
  xdgConfigHome,
  nodePath,
  herdrBin,
} = {}) {
  const manager = selectManager(platform);
  const bridgeDir = join(repoRoot, "bridge");
  const node = nodePath ?? realpathSafe(process.execPath);
  if (manager === "systemd") {
    const configHome = xdgConfigHome || join(home, ".config");
    return {
      manager,
      service: SYSTEMD_UNIT,
      bridgeDir,
      nodePath: node,
      herdrBin: herdrBin ?? null,
      definitionPath: join(configHome, "systemd", "user", SYSTEMD_UNIT),
    };
  }
  return {
    manager,
    service: LAUNCHD_LABEL,
    bridgeDir,
    nodePath: node,
    herdrBin: herdrBin ?? null,
    definitionPath: join(home, "Library", "LaunchAgents", `${LAUNCHD_LABEL}.plist`),
    stdoutPath: join(home, "Library", "Logs", "scoutr", "scoutr-bridge.log"),
    stderrPath: join(home, "Library", "Logs", "scoutr", "scoutr-bridge.err.log"),
  };
}

/**
 * The systemd unit deliberately matches the definition Linux users already run
 * (README's "Run as a systemd user unit"), so installing the helper over an
 * existing hand-written unit is a no-op rather than a surprise rewrite.
 */
export function renderSystemdUnit(paths) {
  return `[Unit]
Description=Scoutr bridge (herdr socket -> local HTTP/WSS API)
Wants=ntfy.service
After=herdr.service ntfy.service

[Service]
Type=simple
WorkingDirectory=${paths.bridgeDir}
ExecStart=${paths.nodePath} dist/cli.js serve
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
`;
}

function plistString(value) {
  return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/**
 * launchd equivalent of the systemd unit. `KeepAlive/SuccessfulExit=false` is
 * launchd's `Restart=on-failure`; `HERDR_BIN` plus an explicit PATH replace the
 * login-shell environment a LaunchAgent never gets.
 */
export function renderLaunchAgentPlist(paths) {
  const searchPath = [
    dirname(paths.nodePath),
    ...(paths.herdrBin ? [dirname(paths.herdrBin)] : []),
    "/usr/local/bin",
    "/opt/homebrew/bin",
    "/usr/bin",
    "/bin",
    "/usr/sbin",
    "/sbin",
  ]
    .filter((dir, index, all) => all.indexOf(dir) === index)
    .join(delimiter);
  const env = [`      <key>PATH</key>\n      <string>${plistString(searchPath)}</string>`];
  if (paths.herdrBin) {
    env.push(`      <key>HERDR_BIN</key>\n      <string>${plistString(paths.herdrBin)}</string>`);
  }
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${plistString(LAUNCHD_LABEL)}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${plistString(paths.nodePath)}</string>
    <string>dist/cli.js</string>
    <string>serve</string>
  </array>
  <key>WorkingDirectory</key>
  <string>${plistString(paths.bridgeDir)}</string>
  <key>EnvironmentVariables</key>
  <dict>
${env.join("\n")}
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key>
    <false/>
  </dict>
  <key>ProcessType</key>
  <string>Background</string>
  <key>StandardOutPath</key>
  <string>${plistString(paths.stdoutPath)}</string>
  <key>StandardErrorPath</key>
  <string>${plistString(paths.stderrPath)}</string>
</dict>
</plist>
`;
}

export function renderDefinition(paths) {
  return paths.manager === "systemd" ? renderSystemdUnit(paths) : renderLaunchAgentPlist(paths);
}

function realpathSafe(path) {
  try {
    return realpathSync(path);
  } catch {
    return path;
  }
}

function run(file, args, options = {}) {
  return execFileSync(file, args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], ...options });
}

function tryRun(file, args, options = {}) {
  try {
    return { ok: true, out: run(file, args, options) };
  } catch (e) {
    return { ok: false, out: e.stdout ?? "", error: e };
  }
}

/** Absolute herdr path for launchd; `null` when it cannot be resolved here. */
function discoverHerdrBin(env) {
  const configured = env.HERDR_BIN?.trim();
  if (configured) return realpathSafe(configured);
  const which = tryRun("which", ["herdr"], { env });
  const first = which.ok ? which.out.trim().split("\n")[0] : "";
  return first ? realpathSafe(first) : null;
}

function currentPaths(env = process.env, platform = process.platform) {
  return resolveServicePaths({
    platform,
    home: env.HOME || homedir(),
    xdgConfigHome: env.XDG_CONFIG_HOME,
    herdrBin: platform === "darwin" ? discoverHerdrBin(env) : null,
  });
}

function readIfPresent(path) {
  try {
    return readFileSync(path, "utf8");
  } catch {
    return null;
  }
}

function guiDomain(env) {
  return `gui/${env.SCOUTR_LAUNCHD_UID ?? userInfo().uid}`;
}

// ---------------------------------------------------------------- systemd ---

function systemctl(args, env) {
  return tryRun("systemctl", ["--user", ...args], { env });
}

function systemdStatus(paths, env) {
  const shown = systemctl(
    ["show", paths.service, "-p", "ActiveState", "-p", "SubState", "-p", "MainPID", "-p", "ExecMainStartTimestamp"],
    env,
  );
  const values = Object.fromEntries(
    (shown.out || "")
      .trim()
      .split("\n")
      .map((line) => line.split("="))
      .filter(([key]) => key),
  );
  const pid = Number(values.MainPID ?? 0) || null;
  const stamp = values.ExecMainStartTimestamp?.trim() || "";
  const startedAtMs = stamp ? Date.parse(stamp) : NaN;
  const active = values.ActiveState === "active" && values.SubState === "running" && pid !== null;
  return {
    active,
    pid: active ? pid : null,
    startedAtMs: Number.isNaN(startedAtMs) ? null : startedAtMs,
    detail: { activeState: values.ActiveState ?? "unknown", subState: values.SubState ?? "unknown" },
    problem: shown.ok ? null : `systemctl show ${paths.service} failed`,
  };
}

function systemdInstall(paths, env, definition, changed) {
  mkdirSync(dirname(paths.definitionPath), { recursive: true });
  if (changed) writeFileSync(paths.definitionPath, definition);
  if (changed) systemctl(["daemon-reload"], env);
  systemctl(["enable", paths.service], env);
  const state = systemdStatus(paths, env);
  if (changed && state.active) systemctl(["restart", paths.service], env);
  else if (!state.active) systemctl(["start", paths.service], env);
}

function systemdRestart(paths, env) {
  const result = systemctl(["restart", paths.service], env);
  if (!result.ok) throw new Error(`systemctl --user restart ${paths.service} failed: ${result.error.message}`);
}

// ---------------------------------------------------------------- launchd ---

function launchctl(args, env) {
  return tryRun("launchctl", args, { env });
}

function launchdLoaded(paths, env) {
  return launchctl(["print", `${guiDomain(env)}/${paths.service}`], env);
}

/** `launchctl print` is line-oriented; pid/state are all the status contract needs. */
export function parseLaunchdPrint(text) {
  return {
    pid: Number(text.match(/^\s*pid = (\d+)$/m)?.[1] ?? 0) || null,
    state: text.match(/^\s*state = (\S+)$/m)?.[1] ?? "unknown",
  };
}

function launchdStatus(paths, env) {
  const printed = launchdLoaded(paths, env);
  if (!printed.ok) {
    return {
      active: false,
      pid: null,
      startedAtMs: null,
      detail: { state: "not-loaded" },
      problem: `LaunchAgent ${paths.service} is not loaded — run \`node scripts/bridge-service.mjs install\``,
    };
  }
  const { pid, state } = parseLaunchdPrint(printed.out);
  // launchctl reports no start time; the process table does.
  let startedAtMs = null;
  if (pid) {
    const ps = tryRun("ps", ["-o", "lstart=", "-p", String(pid)], { env });
    const parsed = ps.ok ? Date.parse(ps.out.trim()) : NaN;
    startedAtMs = Number.isNaN(parsed) ? null : parsed;
  }
  return {
    active: pid !== null && state === "running",
    pid,
    startedAtMs,
    detail: { state },
    problem: null,
  };
}

function launchdInstall(paths, env, definition, changed) {
  mkdirSync(dirname(paths.definitionPath), { recursive: true });
  mkdirSync(dirname(paths.stdoutPath), { recursive: true });
  if (changed) writeFileSync(paths.definitionPath, definition);
  const domain = guiDomain(env);
  const loaded = launchdLoaded(paths, env).ok;
  if (changed && loaded) launchctl(["bootout", `${domain}/${paths.service}`], env);
  if (changed || !loaded) {
    const boot = launchctl(["bootstrap", domain, paths.definitionPath], env);
    if (!boot.ok) throw new Error(`launchctl bootstrap ${paths.definitionPath} failed: ${boot.error.message}`);
  }
  launchctl(["enable", `${domain}/${paths.service}`], env);
}

function launchdRestart(paths, env) {
  const result = launchctl(["kickstart", "-k", `${guiDomain(env)}/${paths.service}`], env);
  if (!result.ok) throw new Error(`launchctl kickstart ${paths.service} failed: ${result.error.message}`);
}

// ------------------------------------------------------------------- core ---

function readStatus(paths, env) {
  const installedDefinition = readIfPresent(paths.definitionPath);
  const state = paths.manager === "systemd" ? systemdStatus(paths, env) : launchdStatus(paths, env);
  return {
    manager: paths.manager,
    service: paths.service,
    active: state.active,
    pid: state.pid,
    startedAtMs: state.startedAtMs,
    definitionPath: paths.definitionPath,
    installed: installedDefinition !== null,
    definitionCurrent: installedDefinition === renderDefinition(paths),
    detail: state.detail,
    problem: state.problem,
  };
}

/**
 * Idempotent: the definition is only rewritten (and the service only reloaded)
 * when the generated text differs from what is installed, so an existing valid
 * service is never churned just because deploy ran.
 */
function install(paths, env) {
  const definition = renderDefinition(paths);
  const changed = readIfPresent(paths.definitionPath) !== definition;
  if (paths.manager === "systemd") systemdInstall(paths, env, definition, changed);
  else launchdInstall(paths, env, definition, changed);
  return { changed };
}

/** Synchronous sleep: the whole helper is execFileSync-shaped, so is this. */
function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function waitForActive(paths, env, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  let status = readStatus(paths, env);
  while (!status.active && Date.now() < deadline) {
    sleepSync(250);
    status = readStatus(paths, env);
  }
  return status;
}

function restart(paths, env) {
  install(paths, env);
  if (paths.manager === "systemd") systemdRestart(paths, env);
  else launchdRestart(paths, env);
  return waitForActive(paths, env);
}

function main(argv, env) {
  const command = argv[0];
  const json = argv.includes("--json");
  let paths;
  try {
    paths = currentPaths(env);
  } catch (e) {
    console.error(e instanceof UnsupportedManagerError ? e.message : String(e));
    return 2;
  }
  switch (command) {
    case "install": {
      const { changed } = install(paths, env);
      const status = waitForActive(paths, env);
      console.log(
        `${paths.manager}: ${changed ? "installed/updated" : "already current"} ${paths.definitionPath}\n` +
          `${paths.service} ${status.active ? `active (pid ${status.pid})` : `NOT active${status.problem ? ` — ${status.problem}` : ""}`}`,
      );
      return status.active ? 0 : 1;
    }
    case "restart": {
      const status = restart(paths, env);
      console.log(
        `${paths.manager}: restarted ${paths.service} — ` +
          (status.active ? `active (pid ${status.pid})` : `NOT active${status.problem ? ` — ${status.problem}` : ""}`),
      );
      return status.active ? 0 : 1;
    }
    case "status": {
      const status = readStatus(paths, env);
      if (json) {
        console.log(JSON.stringify(status, null, 2));
        return 0;
      }
      console.log(
        `manager ${status.manager}\nservice ${status.service}\nactive  ${status.active}\npid     ${status.pid ?? "none"}\n` +
          `started ${status.startedAtMs ? new Date(status.startedAtMs).toISOString() : "unknown"}\n` +
          `definition ${status.definitionPath} (${status.installed ? (status.definitionCurrent ? "current" : "STALE") : "missing"})` +
          (status.problem ? `\nproblem ${status.problem}` : ""),
      );
      return status.active ? 0 : 1;
    }
    default:
      console.error("usage: bridge-service.mjs install | restart | status [--json]");
      return 2;
  }
}

if (process.argv[1] && realpathSafe(process.argv[1]) === realpathSafe(fileURLToPath(import.meta.url))) {
  try {
    process.exit(main(process.argv.slice(2), process.env));
  } catch (e) {
    console.error(String(e?.message ?? e));
    process.exit(1);
  }
}
