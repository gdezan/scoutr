import test from "node:test";
import assert from "node:assert/strict";
import { normalizeModel, pickDevice } from "../src/routes/update.js";
import { BridgeError } from "../src/errors.js";

const s24 = { serial: "adb-S24", model: "SM-S926B" };
const pixel = { serial: "adb-pixel", model: "Pixel 8" };

test("normalizeModel strips case, spaces, and punctuation", () => {
  assert.equal(normalizeModel("SM-S926B"), "sms926b");
  assert.equal(normalizeModel("Pixel 8"), "pixel8");
  assert.equal(normalizeModel("Galaxy S24+"), "galaxys24");
});

test("pickDevice returns the single physical device outright", () => {
  assert.equal(pickDevice([s24], "a-different-model"), s24.serial);
  assert.equal(pickDevice([s24], undefined), s24.serial);
});

test("pickDevice requires a model when multiple devices are attached", () => {
  assert.throws(
    () => pickDevice([s24, pixel], undefined),
    (err: unknown) => err instanceof BridgeError && err.status === 409,
  );
});

test("pickDevice matches the app model against multiple devices", () => {
  assert.equal(pickDevice([s24, pixel], "SM-S926B"), s24.serial);
  // Matching is case- and punctuation-insensitive.
  assert.equal(pickDevice([s24, pixel], "sms926b"), s24.serial);
});

test("pickDevice fails when no device matches or the match is ambiguous", () => {
  assert.throws(
    () => pickDevice([s24, pixel], "iPhone 15"),
    (err: unknown) => err instanceof BridgeError && err.status === 409,
  );
  const secondS24 = { serial: "adb-S24-2", model: "SM-S926B" };
  assert.throws(
    () => pickDevice([s24, secondS24], "SM-S926B"),
    (err: unknown) => err instanceof BridgeError && err.status === 409,
  );
});

test("pickDevice fails when no physical device is attached", () => {
  assert.throws(
    () => pickDevice([], "SM-S926B"),
    (err: unknown) => err instanceof BridgeError && err.status === 409,
  );
});
