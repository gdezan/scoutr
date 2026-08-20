import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

// scripts/bridge-service.mjs is the deployment seam every host path goes
// through (npm run deploy, scripts/deploy-bridge.sh, check-deployed). These
// tests import it directly so manager selection and the generated service
// definitions can be inspected as text — nothing here installs a service.
const SERVICE_SCRIPT = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "scripts", "bridge-service.mjs");
const helper = await import(pathToFileURL(SERVICE_SCRIPT).href);

const darwinPaths = (overrides: Record<string, unknown> = {}) =>
  helper.resolveServicePaths({
    platform: "darwin",
    repoRoot: "/Users/dev/scoutr",
    home: "/Users/dev",
    nodePath: "/opt/node/bin/node",
    herdrBin: "/Users/dev/.local/bin/herdr",
    ...overrides,
  });

const linuxPaths = (overrides: Record<string, unknown> = {}) =>
  helper.resolveServicePaths({
    platform: "linux",
    repoRoot: "/home/dev/scoutr",
    home: "/home/dev",
    nodePath: "/opt/node/bin/node",
    herdrBin: "/home/dev/.local/bin/herdr",
    ...overrides,
  });

test("manager selection maps linux to systemd and darwin to launchd", () => {
  assert.equal(helper.selectManager("linux"), "systemd");
  assert.equal(helper.selectManager("darwin"), "launchd");
});

test("an unsupported OS is a clear unsupported-manager error, not a partial install", () => {
  assert.throws(() => helper.selectManager("win32"), (e: Error) => {
    assert.equal(e.name, "UnsupportedManagerError");
    assert.match(e.message, /no bridge service adapter for platform "win32"/);
    assert.match(e.message, /systemd user service/);
    assert.match(e.message, /LaunchAgent/);
    return true;
  });
  assert.throws(() => helper.resolveServicePaths({ platform: "win32" }), /UnsupportedManagerError|no bridge service adapter/);
});

test("the systemd unit keeps the existing user-service semantics", () => {
  const paths = linuxPaths();
  assert.equal(paths.definitionPath, "/home/dev/.config/systemd/user/scoutr-bridge.service");
  const unit = helper.renderSystemdUnit(paths);
  assert.match(unit, /^ExecStart=\/opt\/node\/bin\/node dist\/cli\.js serve$/m);
  assert.match(unit, /^WorkingDirectory=\/home\/dev\/scoutr\/bridge$/m);
  assert.match(unit, /^Restart=on-failure$/m);
  assert.match(unit, /^WantedBy=default\.target$/m);
  // Compiled dist only: the tsx scratch bridge must never be supervised.
  assert.doesNotMatch(unit, /tsx|src\/cli\.ts/);
});

test("the systemd unit pins herdr so a stale shim on the session PATH cannot win", () => {
  // The capability probe spawns whatever `herdr` resolves to; a version-manager
  // shim one release behind the running herdr server fails its handshake and
  // takes the terminal route down, so the resolved path is baked in here.
  assert.match(helper.renderSystemdUnit(linuxPaths()), /^Environment=HERDR_BIN=\/home\/dev\/\.local\/bin\/herdr$/m);
});

test("an unresolvable herdr leaves the systemd unit's HERDR_BIN out", () => {
  const unit = helper.renderSystemdUnit(linuxPaths({ herdrBin: null }));
  assert.doesNotMatch(unit, /HERDR_BIN/);
  assert.match(unit, /^ExecStart=/m);
});

test("XDG_CONFIG_HOME moves the systemd unit path", () => {
  assert.equal(
    linuxPaths({ xdgConfigHome: "/tmp/xdg" }).definitionPath,
    "/tmp/xdg/systemd/user/scoutr-bridge.service",
  );
});

test("the LaunchAgent plist runs compiled dist at load and keeps it alive", () => {
  const paths = darwinPaths();
  assert.equal(paths.definitionPath, "/Users/dev/Library/LaunchAgents/dev.scoutr.bridge.plist");
  const plist = helper.renderLaunchAgentPlist(paths);
  assert.match(plist, /<key>Label<\/key>\s*<string>dev\.scoutr\.bridge<\/string>/);
  assert.match(plist, /<string>\/opt\/node\/bin\/node<\/string>\s*<string>dist\/cli\.js<\/string>\s*<string>serve<\/string>/);
  assert.match(plist, /<key>WorkingDirectory<\/key>\s*<string>\/Users\/dev\/scoutr\/bridge<\/string>/);
  assert.match(plist, /<key>RunAtLoad<\/key>\s*<true\/>/);
  // launchd's Restart=on-failure.
  assert.match(plist, /<key>KeepAlive<\/key>\s*<dict>\s*<key>SuccessfulExit<\/key>\s*<false\/>/);
  assert.doesNotMatch(plist, /tsx|src\/cli\.ts/);
});

test("the LaunchAgent logs under the user's Library and never needs a login shell", () => {
  const plist = helper.renderLaunchAgentPlist(darwinPaths());
  assert.match(plist, /<key>StandardOutPath<\/key>\s*<string>\/Users\/dev\/Library\/Logs\/scoutr\/scoutr-bridge\.log<\/string>/);
  assert.match(plist, /<key>StandardErrorPath<\/key>\s*<string>\/Users\/dev\/Library\/Logs\/scoutr\/scoutr-bridge\.err\.log<\/string>/);
  // launchd inherits no interactive PATH, so herdr is resolved at install time.
  assert.match(plist, /<key>HERDR_BIN<\/key>\s*<string>\/Users\/dev\/\.local\/bin\/herdr<\/string>/);
  assert.match(plist, /<key>PATH<\/key>\s*<string>[^<]*\/Users\/dev\/\.local\/bin[^<]*<\/string>/);
  assert.match(plist, /<key>PATH<\/key>\s*<string>[^<]*\/opt\/node\/bin[^<]*<\/string>/);
});

test("an unresolvable herdr leaves HERDR_BIN out rather than writing a bare name", () => {
  const plist = helper.renderLaunchAgentPlist(darwinPaths({ herdrBin: null }));
  assert.doesNotMatch(plist, /HERDR_BIN/);
  assert.match(plist, /<key>PATH<\/key>/);
});

test("renderDefinition follows the selected manager", () => {
  assert.match(helper.renderDefinition(linuxPaths()), /^\[Unit\]/);
  assert.match(helper.renderDefinition(darwinPaths()), /^<\?xml/);
});

test("status --json reports the stable shape check-deployed consumes", async () => {
  // A throwaway XDG_CONFIG_HOME means the query resolves an uninstalled unit
  // path instead of inspecting (or touching) the developer's real service.
  const configHome = await mkdtemp(join(tmpdir(), "scoutr-service-"));
  try {
    const out = execFileSync(process.execPath, [SERVICE_SCRIPT, "status", "--json"], {
      encoding: "utf8",
      env: { ...process.env, XDG_CONFIG_HOME: configHome },
    });
    const status = JSON.parse(out);
    assert.equal(status.manager, process.platform === "darwin" ? "launchd" : "systemd");
    assert.equal(typeof status.active, "boolean");
    assert.ok(status.pid === null || typeof status.pid === "number");
    assert.ok(status.startedAtMs === null || typeof status.startedAtMs === "number");
    assert.equal(status.installed, false);
    assert.equal(status.definitionCurrent, false);
    assert.ok(status.definitionPath.startsWith(configHome) || process.platform === "darwin");
    assert.ok("problem" in status && "detail" in status);
  } finally {
    await rm(configHome, { recursive: true, force: true });
  }
});

test("an unknown subcommand prints usage and exits 2", () => {
  const result = (() => {
    try {
      execFileSync(process.execPath, [SERVICE_SCRIPT, "reload"], { encoding: "utf8", stdio: "pipe" });
      return { status: 0, stderr: "" };
    } catch (e: any) {
      return { status: e.status, stderr: String(e.stderr) };
    }
  })();
  assert.equal(result.status, 2);
  assert.match(result.stderr, /usage: bridge-service\.mjs install \| restart \| status/);
});

// `install` is exercised against a stub `systemctl` on PATH: the real user
// manager is never touched, but the file-writing/idempotence logic — the part
// that decides whether an existing service is churned — is the real code.
async function withStubSystemctl(
  fn: (ctx: { env: NodeJS.ProcessEnv; unitPath: string; calls: () => string[] }) => void | Promise<void>,
): Promise<void> {
  const dir = await mkdtemp(join(tmpdir(), "scoutr-service-install-"));
  const logPath = join(dir, "calls.log");
  const stub = join(dir, "bin", "systemctl");
  await mkdir(join(dir, "bin"), { recursive: true });
  await writeFile(
    stub,
    `#!/usr/bin/env bash\necho "$*" >> ${logPath}\n` +
      `if [ "$2" = "show" ]; then\n` +
      `  printf 'ActiveState=active\\nSubState=running\\nMainPID=4242\\nExecMainStartTimestamp=Tue 2026-08-18 00:18:08 -03\\n'\n` +
      `fi\nexit 0\n`,
    { mode: 0o755 },
  );
  const env = {
    ...process.env,
    PATH: `${join(dir, "bin")}:${process.env.PATH}`,
    XDG_CONFIG_HOME: join(dir, "config"),
  };
  const calls = () => {
    try {
      return readFileSync(logPath, "utf8").trim().split("\n").filter(Boolean);
    } catch {
      return [];
    }
  };
  try {
    await fn({ env, unitPath: join(dir, "config", "systemd", "user", "scoutr-bridge.service"), calls });
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

const runHelper = (args: string[], env: NodeJS.ProcessEnv) =>
  execFileSync(process.execPath, [SERVICE_SCRIPT, ...args], { encoding: "utf8", env });

test("install writes the unit, then re-running it changes nothing", { skip: process.platform !== "linux" }, async () => {
  await withStubSystemctl(async ({ env, unitPath, calls }) => {
    runHelper(["install"], env);
    const written = readFileSync(unitPath, "utf8");
    assert.match(written, /^ExecStart=\S*node dist\/cli\.js serve$/m);
    assert.ok(calls().some((call) => call.includes("daemon-reload")));
    assert.ok(calls().some((call) => call.includes("enable scoutr-bridge.service")));

    const before = calls().length;
    const second = runHelper(["install"], env);
    assert.match(second, /already current/);
    assert.equal(readFileSync(unitPath, "utf8"), written);
    // No reload and no restart: a valid service is not churned just to deploy.
    assert.deepEqual(
      calls()
        .slice(before)
        .filter((call) => /daemon-reload|restart/.test(call)),
      [],
    );
  });
});

test("install replaces a definition pointing at a stale checkout", { skip: process.platform !== "linux" }, async () => {
  await withStubSystemctl(async ({ env, unitPath, calls }) => {
    await mkdir(dirname(unitPath), { recursive: true });
    await writeFile(unitPath, "[Service]\nWorkingDirectory=/old/checkout/bridge\nExecStart=/old/node dist/cli.js serve\n");
    const out = runHelper(["install"], env);
    assert.match(out, /installed\/updated/);
    const written = readFileSync(unitPath, "utf8");
    assert.doesNotMatch(written, /\/old\/checkout|\/old\/node/);
    assert.match(written, /^WorkingDirectory=.*\/bridge$/m);
    assert.ok(calls().some((call) => call.includes("daemon-reload")));
    assert.ok(calls().some((call) => call.includes("restart scoutr-bridge.service")));
  });
});

test("launchctl print yields the pid and state the status contract needs", () => {
  const printed = [
    "dev.scoutr.bridge = {",
    "\tactive count = 1",
    "\tstate = running",
    "\tprogram = /opt/node/bin/node",
    "\tpid = 5150",
    "}",
  ].join("\n");
  assert.deepEqual(helper.parseLaunchdPrint(printed), { pid: 5150, state: "running" });
  assert.deepEqual(helper.parseLaunchdPrint("dev.scoutr.bridge = {\n\tstate = waiting\n}"), {
    pid: null,
    state: "waiting",
  });
  assert.deepEqual(helper.parseLaunchdPrint("Could not find service"), { pid: null, state: "unknown" });
});
