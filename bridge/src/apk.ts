import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { join } from "node:path";

/**
 * Host-side APK build coordinator for the phone-pull update path.
 *
 * The install path used to push bytes host -> phone over adb, so updating the
 * app required a live `adb pair` (and, off USB, re-pairing after every phone
 * reboot). This inverts the direction: the bridge builds an APK on the host,
 * the phone downloads it over the exposed API, and Android's PackageInstaller does
 * the install on-device. Nothing here shells out to adb.
 *
 * Build state is process-wide on purpose — one checkout can only run one
 * gradle build at a time, and the bridge is the only process driving this one.
 */

/** Where `assembleDebug` leaves the APK, relative to the checkout root. */
export const APK_RELATIVE_PATH = "android/app/build/outputs/apk/debug/app-debug.apk";
const BUILD_SCRIPT = "scripts/install-app.sh";
/** Only the tail of gradle's stderr is kept, to bound what a failure reports. */
const STDERR_TAIL_BYTES = 4_000;

export type ApkBuildState = "idle" | "building" | "ready" | "failed";

/** The build identity gradle stamps into the APK (from scripts/version.mjs). */
export interface BuildIdentity {
  commit: string;
  version: string;
  versionCode: number;
}

/** A built APK: where it is, what it hashes to, and what it claims to be. */
export interface ApkArtifact extends BuildIdentity {
  path: string;
  size: number;
  sha256: string;
}

/** The state the phone polls. `apk` describes the most recent finished build. */
export interface ApkBuildStatus {
  state: ApkBuildState;
  /** Monotonic id of the most recently started build (0 before the first). */
  buildId: number;
  error: string | null;
  apk: Omit<ApkArtifact, "path"> | null;
}

/** Runs one build to completion; rejects with a human-readable reason. */
export type BuildRunner = (root: string) => Promise<void>;

/** Production runner: `install-app.sh --build-only`, i.e. gradle assembleDebug. */
export const gradleBuildRunner: BuildRunner = (root) =>
  new Promise((resolve, reject) => {
    const child = spawn("bash", [join(root, BUILD_SCRIPT), "--build-only"], {
      cwd: root,
      stdio: ["ignore", "ignore", "pipe"],
      env: process.env,
    });
    let tail = "";
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk: string) => {
      tail = (tail + chunk).slice(-STDERR_TAIL_BYTES);
    });
    child.on("error", (error) => reject(new Error(`could not start the APK build: ${error.message}`)));
    child.on("close", (code) => {
      if (code === 0) resolve();
      else reject(new Error(tail.trim() || `APK build exited with code ${code}`));
    });
  });

function describe(artifact: ApkArtifact): Omit<ApkArtifact, "path"> {
  const { size, sha256, commit, version, versionCode } = artifact;
  return { size, sha256, commit, version, versionCode };
}

function hashFile(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = createHash("sha256");
    const stream = createReadStream(path);
    stream.on("error", reject);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("end", () => resolve(hash.digest("hex")));
  });
}

export class ApkBuilder {
  private state: ApkBuildState = "idle";
  private buildId = 0;
  private error: string | null = null;
  private artifact: ApkArtifact | null = null;

  constructor(private readonly run: BuildRunner = gradleBuildRunner) {}

  get status(): ApkBuildStatus {
    return {
      state: this.state,
      buildId: this.buildId,
      error: this.error,
      apk: this.artifact === null ? null : describe(this.artifact),
    };
  }

  /**
   * Starts a build unless one is already running (then the running build's id
   * comes back, so a double tap on the phone cannot queue a second gradle).
   *
   * `identity` is read at start rather than at finish: a build that straddles
   * a commit would otherwise be labelled with a HEAD it does not contain. The
   * phone compares this commit against the host's before it installs.
   */
  start(root: string, identity: BuildIdentity): number {
    if (this.state === "building") return this.buildId;
    this.buildId += 1;
    this.state = "building";
    this.error = null;
    void this.run(root)
      .then(() => this.finish(join(root, APK_RELATIVE_PATH), identity))
      .catch((error) => this.fail(error instanceof Error ? error.message : String(error)));
    return this.buildId;
  }

  /**
   * The APK the download route may serve. Only a finished build qualifies: a
   * failed rebuild leaves a stale file on disk that must not be handed out.
   */
  readyArtifact(): ApkArtifact | null {
    return this.state === "ready" ? this.artifact : null;
  }

  private async finish(path: string, identity: BuildIdentity): Promise<void> {
    try {
      const { size } = await stat(path);
      const sha256 = await hashFile(path);
      this.artifact = { path, size, sha256, ...identity };
      this.state = "ready";
      this.error = null;
    } catch (error) {
      this.fail(`build succeeded but ${path} is unreadable: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private fail(message: string): void {
    this.state = "failed";
    this.error = message;
  }
}
