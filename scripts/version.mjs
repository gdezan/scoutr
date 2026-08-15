#!/usr/bin/env node
/**
 * Single source of truth for the Scoutr build identity.
 *
 * Computes a semantic version anchored on the most recent release tag, bumped
 * by the conventional commits since it, plus the git commit, working-tree
 * dirty flag, and build time. Gradle runs this at build time to stamp the APK
 * (`--props`); the bridge runs it at runtime to compute the host's "latest"
 * identity for the update status row (`--json`).
 *
 * Version rules (see docs/decisions.md):
 *   feat(scope):  -> minor bump
 *   fix(scope):   -> patch bump
 *   feat!/fix! or BREAKING CHANGE: -> major bump
 *   everything else, including non-conventional subjects -> patch bump
 *
 * versionCode = major*1_000_000 + minor*1_000 + patch (monotonic).
 *
 * Output modes:
 *   (default) JSON object on stdout
 *   --props    key=value lines (Gradle `java.util.Properties` parse)
 *   --key NAME single bare value (tests / Makefile one-liners)
 */
import { execFileSync } from "node:child_process";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

// The script lives at <repo>/scripts/version.mjs, so its own directory is
// always inside the checkout regardless of the caller's cwd. All git commands
// run with `-C` here, making the script location-independent.
const scriptDir = dirname(fileURLToPath(import.meta.url));

function git(args) {
  return execFileSync("git", ["-C", scriptDir, ...args], {
    encoding: "utf8",
    env: { ...process.env, GIT_TERMINAL_PROMPT: "0" },
    stdio: ["ignore", "pipe", "ignore"],
  }).toString("utf8");
}

/** `git` may fail (no repo, no tags); every failure degrades to a sane default. */
function gitOrNull(args) {
  try {
    return git(args).trim();
  } catch {
    return null;
  }
}

// Conventional-commit subject: `type(scope)!: subject` or `type: subject`.
// `!` after type/scope marks a breaking change; `BREAKING CHANGE:` is handled
// separately on the full subject+body below.
const CONVENTIONAL = /^([a-z]+)(?:\([^)]*\))?(!)?:/;

/** Bump category for one commit subject (subject-level breaking `!` only). */
function classify(subject) {
  const match = CONVENTIONAL.exec(subject.trim());
  if (!match) return "patch"; // non-conventional -> patch (see header rules)
  if (match[2] === "!") return "major";
  if (match[1] === "feat") return "minor";
  return "patch"; // fix and every other conventional type -> patch
}

const BUMP_ORDER = { none: 0, patch: 1, minor: 2, major: 3 };

function parseSemver(text) {
  const match = /^v?(\d+)\.(\d+)\.(\d+)/.exec(text);
  if (!match) return null;
  return { major: Number(match[1]), minor: Number(match[2]), patch: Number(match[3]) };
}

function computeIdentity() {
  const repoRoot = gitOrNull(["rev-parse", "--show-toplevel"]);

  const lastTag = gitOrNull(["describe", "--tags", "--abbrev=0"]);
  const base = (lastTag && parseSemver(lastTag)) || { major: 0, minor: 0, patch: 0 };

  // Full subjects + bodies since the anchor tag, so `BREAKING CHANGE:` footers
  // count even when the subject itself is a plain `feat:`.
  let subjects = [];
  if (lastTag) {
    const log = gitOrNull(["log", `${lastTag}..HEAD`, "--pretty=format:%s%n%b"]);
    if (log) subjects = log.split("\n").filter((line) => line.length > 0);
  }

  let bump = "none";
  for (const line of subjects) {
    if (/^BREAKING CHANGE:/i.test(line.trim())) {
      bump = "major";
      break;
    }
    const category = classify(line);
    if (BUMP_ORDER[category] > BUMP_ORDER[bump]) bump = category;
  }

  let { major, minor, patch } = base;
  if (bump === "major") {
    major += 1;
    minor = 0;
    patch = 0;
  } else if (bump === "minor") {
    minor += 1;
    patch = 0;
  } else if (bump === "patch") {
    patch += 1;
  }

  const version = `${major}.${minor}.${patch}`;
  // AGP requires versionCode >= 1; the untagged baseline (0.0.0) would be 0.
  const versionCode = Math.max(1, major * 1_000_000 + minor * 1_000 + patch);

  const commit = gitOrNull(["rev-parse", "--short", "HEAD"]) ?? "";
  const status = gitOrNull(["status", "--porcelain"]);
  const dirty = status !== null && status !== "";

  return {
    version,
    versionCode,
    commit,
    dirty,
    lastTag,
    bump,
    commitsSinceTag: subjects.length,
    buildTime: new Date().toISOString(),
    repoRoot,
  };
}

const identity = computeIdentity();

function print() {
  const arg = process.argv[2];
  if (arg === "--props") {
    const lines = [
      `version=${identity.version}`,
      `versionCode=${identity.versionCode}`,
      `commit=${identity.commit}`,
      `dirty=${identity.dirty}`,
      `lastTag=${identity.lastTag ?? ""}`,
      `buildTime=${identity.buildTime}`,
    ];
    process.stdout.write(`${lines.join("\n")}\n`);
    return;
  }
  if (arg === "--key") {
    const key = process.argv[3];
    if (!(key in identity)) {
      process.stderr.write(`unknown key: ${key}\n`);
      process.exit(2);
    }
    process.stdout.write(`${identity[key]}\n`);
    return;
  }
  process.stdout.write(`${JSON.stringify(identity, null, 2)}\n`);
}

print();
