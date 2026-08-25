import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { ApkBuilder, APK_RELATIVE_PATH, type BuildRunner } from "../src/apk.js";
import { createUpdateRoutes } from "../src/routes/update.js";
import { BridgeError } from "../src/errors.js";
import type { Route, RouteContext } from "../src/routes/types.js";

const identity = { commit: "abc1234", version: "0.4.0", versionCode: 4000 };

/** A checkout root whose APK output path holds `bytes`. */
async function fakeCheckout(bytes: Buffer): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "scoutr-apk-"));
  const apk = join(root, APK_RELATIVE_PATH);
  await mkdir(dirname(apk), { recursive: true });
  await writeFile(apk, bytes);
  return root;
}

/** Polls until the builder leaves "building" — finish() stats and hashes async. */
async function settle(builder: ApkBuilder): Promise<void> {
  for (let i = 0; i < 200; i++) {
    if (builder.status.state !== "building") return;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error("builder never left the building state");
}

function download(routes: Route[]): Route {
  const route = routes.find((r) => r.method === "GET" && r.path === "/api/update/apk");
  assert.ok(route, "download route must exist");
  return route;
}

test("a fresh builder has nothing to serve", () => {
  const builder = new ApkBuilder(async () => {});
  assert.deepEqual(builder.status, { state: "idle", buildId: 0, error: null, apk: null });
  assert.equal(builder.readyArtifact(), null);
});

test("a successful build records the APK size, hash, and identity", async () => {
  const bytes = Buffer.from("not really an apk, but bytes are bytes");
  const root = await fakeCheckout(bytes);
  const builder = new ApkBuilder(async () => {});

  assert.equal(builder.start(root, identity), 1);
  assert.equal(builder.status.state, "building");
  await settle(builder);

  const status = builder.status;
  assert.equal(status.state, "ready");
  assert.equal(status.error, null);
  assert.deepEqual(status.apk, {
    size: bytes.length,
    sha256: createHash("sha256").update(bytes).digest("hex"),
    ...identity,
  });
  assert.equal(builder.readyArtifact()?.path, join(root, APK_RELATIVE_PATH));
  await rm(root, { recursive: true, force: true });
});

test("a failed build reports the runner's reason and serves nothing", async () => {
  const root = await fakeCheckout(Buffer.from("stale"));
  const failing: BuildRunner = async () => {
    throw new Error("> Task :app:compileDebugKotlin FAILED");
  };
  const builder = new ApkBuilder(failing);
  builder.start(root, identity);
  await settle(builder);

  assert.equal(builder.status.state, "failed");
  assert.match(builder.status.error ?? "", /compileDebugKotlin FAILED/);
  // The stale APK on disk must not be handed out as if it were the new build.
  assert.equal(builder.readyArtifact(), null);
  await rm(root, { recursive: true, force: true });
});

test("a build that leaves no APK behind fails instead of reporting ready", async () => {
  const root = await mkdtemp(join(tmpdir(), "scoutr-apk-"));
  const builder = new ApkBuilder(async () => {});
  builder.start(root, identity);
  await settle(builder);

  assert.equal(builder.status.state, "failed");
  assert.match(builder.status.error ?? "", /unreadable/);
  await rm(root, { recursive: true, force: true });
});

test("starting while a build runs returns the running id, not a second gradle", async () => {
  const root = await fakeCheckout(Buffer.from("apk"));
  let runs = 0;
  let release = (): void => {};
  const blocked: BuildRunner = async () => {
    runs += 1;
    await new Promise<void>((resolve) => {
      release = resolve;
    });
  };
  const builder = new ApkBuilder(blocked);

  const first = builder.start(root, identity);
  assert.equal(builder.start(root, identity), first);
  assert.equal(runs, 1);

  release();
  await settle(builder);
  // A build started after the first finished gets a fresh id.
  assert.equal(builder.start(root, identity), first + 1);
  release();
  await settle(builder);
  await rm(root, { recursive: true, force: true });
});

test("the download route refuses until a build is ready, then streams the file", async () => {
  const bytes = Buffer.from("apk bytes");
  const root = await fakeCheckout(bytes);
  const builder = new ApkBuilder(async () => {});
  const route = download(createUpdateRoutes(builder));
  // SAFETY: the download route tests do not inspect request context fields.
  const ctx = {} as RouteContext;

  await assert.rejects(
    async () => route.handle(ctx),
    (error) => error instanceof BridgeError && error.status === 409,
  );

  builder.start(root, identity);
  await settle(builder);

  const result = await route.handle(ctx);
  assert.equal(result.status, 200);
  assert.deepEqual(result.file, {
    path: join(root, APK_RELATIVE_PATH),
    size: bytes.length,
    contentType: "application/vnd.android.package-archive",
    filename: "scoutr-0.4.0.apk",
  });
  await rm(root, { recursive: true, force: true });
});

test("the download route surfaces the build failure reason in its 409", async () => {
  const failing: BuildRunner = async () => {
    throw new Error("gradle exploded");
  };
  const builder = new ApkBuilder(failing);
  const route = download(createUpdateRoutes(builder));
  builder.start("/nonexistent", identity);
  await settle(builder);

  await assert.rejects(
    // SAFETY: the download route tests do not inspect request context fields.
    async () => route.handle({} as RouteContext),
    (error) => error instanceof BridgeError && error.status === 409 && /gradle exploded/.test(error.message),
  );
});
