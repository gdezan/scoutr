import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  LIVE_OUTPUT_MAX_BYTES,
  LiveOutputError,
  readLiveOutput,
  sanitizeTerminalText,
} from "../src/live-output.js";

function fakeHerdr(text = "line one\nline two", truncated = false) {
  const calls: Array<Record<string, unknown>> = [];
  return {
    calls,
    client: {
      async agentGet(target: string, timeoutMs: number) {
        calls.push({ method: "agent.get", target, timeoutMs });
        if (target !== "p1") throw new Error("agent not found");
        return { type: "agent" };
      },
      async agentRead(target: string, source: string, options: unknown) {
        calls.push({ method: "agent.read", target, source, options });
        return {
          type: "pane_read",
          read: {
            pane_id: target,
            workspace_id: "w1",
            tab_id: "t1",
            source,
            format: "text",
            text,
            revision: 7,
            truncated,
          },
        };
      },
    } as never,
  };
}

describe("bounded live output", () => {
  it("verifies the current agent then reads fixed plain-text visible output", async () => {
    const fake = fakeHerdr("hello\r\nworld\u0000");

    const result = await readLiveOutput(fake.client, "p1", "40");

    assert.equal(fake.calls.length, 2);
    assert.equal(fake.calls[0]?.method, "agent.get");
    assert.equal(fake.calls[0]?.target, "p1");
    assert.ok(Number(fake.calls[0]?.timeoutMs) > 0 && Number(fake.calls[0]?.timeoutMs) <= 3_000);
    assert.equal(fake.calls[1]?.method, "agent.read");
    assert.equal(fake.calls[1]?.source, "visible");
    const options = fake.calls[1]?.options as Record<string, unknown>;
    assert.deepEqual({ lines: options.lines, format: options.format, stripAnsi: options.stripAnsi }, {
      lines: 40,
      format: "text",
      stripAnsi: true,
    });
    assert.ok(Number(options.requestTimeoutMs) > 0 && Number(options.requestTimeoutMs) <= 3_000);
    assert.equal(result.text, "hello\nworld");
    assert.equal(result.revision, 7);
    assert.equal(result.lineLimit, 40);
  });

  it("keeps only the newest capped bytes and reports truncation", async () => {
    const fake = fakeHerdr("x".repeat(LIVE_OUTPUT_MAX_BYTES + 500));

    const result = await readLiveOutput(fake.client, "p1", null);

    assert.equal(Buffer.byteLength(result.text), LIVE_OUTPUT_MAX_BYTES);
    assert.equal(result.truncated, true);
  });

  it("rejects stale agent targets before reading and invalid limits before any Herdr call", async () => {
    const fake = fakeHerdr();
    await assert.rejects(readLiveOutput(fake.client, "missing", null), (error: unknown) => {
      assert.ok(error instanceof LiveOutputError);
      assert.equal(error.status, 404);
      return true;
    });
    assert.equal(fake.calls.filter((call) => call.method === "agent.read").length, 0);

    const callCount = fake.calls.length;
    await assert.rejects(readLiveOutput(fake.client, "p1", "0"), /between 1 and 120/);
    await assert.rejects(readLiveOutput(fake.client, "p1", "121"), /between 1 and 120/);
    await assert.rejects(readLiveOutput(fake.client, "p1", "many"), /integer/);
    assert.equal(fake.calls.length, callCount);
  });

  it("passes the remaining deadline to the Herdr read and maps timeouts", async () => {
    let readTimeoutMs = 0;
    const client = {
      async agentGet() {
        return { type: "agent" };
      },
      async agentRead(_target: string, _source: string, options: { requestTimeoutMs: number }) {
        readTimeoutMs = options.requestTimeoutMs;
        throw new Error("herdr request agent.read timed out");
      },
    } as never;

    await assert.rejects(readLiveOutput(client, "p1", null, 50), (error: unknown) => {
      assert.ok(error instanceof LiveOutputError);
      assert.equal(error.status, 504);
      return true;
    });
    assert.ok(readTimeoutMs > 0 && readTimeoutMs <= 50);
  });

  it("normalizes carriage returns and removes unsafe controls", () => {
    assert.equal(sanitizeTerminalText("a\rb\r\nc\t\u0007d"), "a\nb\nc\td");
  });
});
