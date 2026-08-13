/**
 * Terminal WebSocket contract matrix (offline).
 *
 * Real HTTP upgrades + real `ws` clients against createScoutrServer with a
 * FakeTerminalLauncher, plus adapter-level slow-client/input-queue tests on a
 * scriptable fake socket. The broker's grace and slow-client bounds are
 * injected small so expiry tests run in milliseconds.
 */

import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import WebSocket from "ws";
import { createScoutrServer, type ScoutrServer } from "../src/server.js";
import { TerminalSessionBroker } from "../src/terminal/broker.js";
import { TerminalConnection } from "../src/terminal/websocket.js";
import type { TerminalSocketLike } from "../src/terminal/websocket.js";
import type { SessionSnapshot } from "../src/herdr/types.js";
import { fakeHerdr } from "./support/fake-herdr.js";
import { fakeFeed } from "./support/fake-feed.js";
import { FakeTerminalLauncher } from "./support/fake-terminal.js";
const PORT = 8791;
const TOKEN = "test_token_for_terminal_ws_0001";

function snapshotWithPanes(panes: string[]): SessionSnapshot {
  return {
    version: "0.8.0",
    protocol: 19,
    focused_workspace_id: "ws1",
    focused_tab_id: "t1",
    focused_pane_id: panes[0] ?? "w1:p1",
    workspaces: [
      { workspace_id: "ws1", number: 1, label: "", focused: true, pane_count: panes.length, agent_status: "idle" },
    ],
    tabs: [{ tab_id: "t1", workspace_id: "ws1", label: "", focused: true, agent_status: "idle" }],
    panes: panes.map((paneId, index) => ({
      pane_id: paneId,
      workspace_id: "ws1",
      tab_id: "t1",
      title: paneId,
      focused: index === 0,
      agent_status: "idle",
      terminal_id: `term-${index}`,
      cwd: "/work",
    })),
    agents: [],
    layouts: [],
  };
}

class UpgradeRejected extends Error {
  constructor(readonly status: number) {
    super(`upgrade rejected with ${status}`);
  }
}

/** One real ws client plus an ordered message log. */
type TerminalMessage = { kind: "text" | "binary"; data: Buffer };

class TestClient {
  readonly messages: TerminalMessage[] = [];
  closed: { code: number; reason: string } | null = null;
  private readonly closedPromise: Promise<void>;
  private resolveClosed!: () => void;
  private readonly waiters = new Set<{
    predicate: (m: TerminalMessage) => boolean;
    resolve: (m: TerminalMessage) => void;
    timer: NodeJS.Timeout;
  }>();

  constructor(readonly ws: WebSocket) {
    ws.on("message", (data: Buffer | ArrayBuffer, isBinary: boolean) => {
      const buffer = Buffer.isBuffer(data) ? data : Buffer.from(data);
      const message: TerminalMessage = { kind: isBinary ? "binary" : "text", data: buffer };
      this.messages.push(message);
      for (const waiter of [...this.waiters]) {
        if (waiter.predicate(message)) {
          this.waiters.delete(waiter);
          clearTimeout(waiter.timer);
          waiter.resolve(message);
        }
      }
    });
    ws.on("close", (code: number, reason: Buffer) => {
      this.closed = { code, reason: reason.toString() };
      this.resolveClosed();
    });
    ws.on("error", () => {
      /* observed via close */
    });
    this.closedPromise = new Promise<void>((resolve) => {
      this.resolveClosed = resolve;
    });
  }

  send(obj: unknown): void {
    this.ws.send(JSON.stringify(obj));
  }

  sendBinary(bytes: Buffer): void {
    this.ws.send(bytes);
  }

  sendRaw(text: string): void {
    this.ws.send(text);
  }

  textTypes(): string[] {
    return this.messages.filter((m) => m.kind === "text").map((m) => (JSON.parse(m.data.toString()) as { type: string }).type);
  }

  /** First message (from the front) matching the predicate. */
  waitFor(predicate: (m: TerminalMessage) => boolean, timeoutMs = 5000): Promise<TerminalMessage> {
    const existing = this.messages.find(predicate);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const waiter = {
        predicate,
        resolve,
        timer: setTimeout(() => {
          this.waiters.delete(waiter);
          reject(new Error("timed out waiting for terminal message"));
        }, timeoutMs),
      };
      this.waiters.add(waiter);
    });
  }

  waitForType(type: string, timeoutMs = 5000): Promise<Record<string, unknown>> {
    return this.waitFor((m) => {
      if (m.kind !== "text") return false;
      try {
        return (JSON.parse(m.data.toString()) as { type: string }).type === type;
      } catch {
        return false;
      }
    }, timeoutMs).then((m) => JSON.parse(m.data.toString()) as Record<string, unknown>);
  }

  waitClosed(timeoutMs = 5000): Promise<{ code: number; reason: string }> {
    if (this.closed) return Promise.resolve(this.closed);
    return Promise.race([
      this.closedPromise.then(() => this.closed!),
      new Promise((_, reject) => setTimeout(() => reject(new Error("timed out waiting for close")), timeoutMs)),
    ]);
  }

  close(): void {
    this.ws.close();
  }
}

function connectWs(headers: Record<string, string>): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws/terminal`, { headers });
    ws.on("open", () => {
      settled = true;
      resolve(ws);
    });
    ws.on("unexpected-response", (_req, res) => {
      if (!settled) {
        settled = true;
        reject(new UpgradeRejected(res.statusCode ?? 0));
      }
      ws.terminate();
    });
    ws.on("error", (error) => {
      if (!settled) {
        settled = true;
        reject(error);
      }
    });
  });
}

async function openClient(headers?: Record<string, string>): Promise<TestClient> {
  const ws = await connectWs(headers ?? { authorization: `Bearer ${TOKEN}` });
  return new TestClient(ws);
}

async function hello(client: TestClient, helloMsg: Record<string, unknown>): Promise<Record<string, unknown>> {
  client.send(helloMsg);
  return client.waitForType("ready");
}

async function waitUntil(condition: () => boolean, timeoutMs = 3000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error("condition not met in time");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

describe("terminal websocket contract (offline)", () => {
  let server: ScoutrServer;
  let launcher: FakeTerminalLauncher;
  let feed: ReturnType<typeof fakeFeed>;

  before(async () => {
    launcher = new FakeTerminalLauncher();
    feed = fakeFeed(snapshotWithPanes(["w1:p1", "w1:p2"]));
    server = createScoutrServer({
      herdr: fakeHerdr(),
      feed,
      usage: { all: async () => ({}) } as never,
      config: { configDir: "/tmp/scoutr-test-config", token: TOKEN, port: PORT },
      terminal: launcher,
      terminalOptions: { graceMs: 120 },
    });
    // createScoutrServer listens synchronously (default listen: true).
  });

  after(async () => {
    await server.close();
  });

  test("upgrade auth: header-only bearer token", async () => {
    await assert.rejects(openClient({}), UpgradeRejected);
    await assert.rejects(openClient({ authorization: "Bearer wrong_token_00000000000000" }), UpgradeRejected);
    // Query tokens are rejected even when correct on the terminal path.
    await assert.rejects(
      new Promise<WebSocket>((resolve, reject) => {
        const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws/terminal?token=${TOKEN}`);
        ws.on("open", () => resolve(ws));
        ws.on("unexpected-response", (_req, res) => {
          ws.terminate();
          reject(new UpgradeRejected(res.statusCode ?? 0));
        });
        ws.on("error", reject);
      }),
      UpgradeRejected,
    );
  });

  test("capability probe runs at upgrade time against the first snapshot pane", async () => {
    const client = await openClient();
    const ready = await hello(client, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    assert.equal(ready.type, "ready");
    assert.equal(ready.reset, true);
    assert.equal(ready.paneId, "w1:p1");
    assert.equal(ready.mode, "control");
    assert.equal(ready.cols, 120);
    assert.equal(ready.rows, 30);
    assert.deepEqual(launcher.probeCalls, ["w1:p1"]);
    client.close();
    await client.waitClosed();
  });

  test("health reports the settled terminal capability", async () => {
    const response = await fetch(`http://127.0.0.1:${PORT}/api/health`, {
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    const health = (await response.json()) as {
      terminal: { capability: { status: string; herdrVersion: string; protocol: number } };
    };
    assert.equal(response.status, 200);
    assert.deepEqual(health.terminal.capability, { status: "supported", herdrVersion: "0.8.0", protocol: 19 });
  });

  test("hello validation: unknown pane and missing snapshot reject with retryable errors", async () => {
    const client = await openClient();
    client.send({ type: "hello", version: 1, paneId: "nope", cols: 80, rows: 24, intent: "auto" });
    const error = await client.waitForType("error");
    assert.equal(error.code, "pane_not_found");
    assert.equal(error.retryable, true);
    const { code } = await client.waitClosed();
    assert.equal(code, 1002);

    feed.setSnapshot(null);
    const client2 = await openClient();
    client2.send({ type: "hello", version: 1, paneId: "w1:p1", cols: 80, rows: 24, intent: "auto" });
    const error2 = await client2.waitForType("error");
    assert.equal(error2.code, "no_snapshot");
    feed.setSnapshot(snapshotWithPanes(["w1:p1", "w1:p2"]));
    await client2.waitClosed();
  });

  test("binary input and resize reach the child; frames arrive in order after ready", async () => {
    const client = await openClient();
    await hello(client, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    // ready must precede the replay bytes on the wire.
    await client.waitFor((m) => m.kind === "binary");
    const types = client.textTypes();
    assert.equal(types[0], "ready");
    assert.equal(types.length, 1);

    const proc = launcher.last()!;
    client.sendBinary(Buffer.from("hello\r"));
    client.send({ type: "resize", cols: 100, rows: 40 });
    await waitUntil(() => proc.inputs.length === 1 && proc.resizes.length === 1);
    assert.deepEqual(proc.inputs[0]!.toString(), "hello\r");
    assert.deepEqual(proc.resizes[0], { cols: 100, rows: 40 });
    client.close();
    await client.waitClosed();
  });

  test("observer fallback on ownership conflict emits ready(observe) then ownership", async () => {
    launcher.controlFailure = "conflict";
    const client = await openClient();
    client.send({ type: "hello", version: 1, paneId: "w1:p2", cols: 120, rows: 30, intent: "auto" });
    const ready = await client.waitForType("ready");
    assert.equal(ready.mode, "observe");
    const ownership = await client.waitForType("ownership");
    assert.equal(ownership.mode, "observe");
    assert.equal(ownership.canTakeover, true);
    assert.equal(launcher.opens.at(-1)!.options.mode, "observe");
    launcher.controlFailure = null;

    // observe mode refuses input.
    client.sendBinary(Buffer.from("x"));
    const error = await client.waitForType("error");
    assert.equal(error.code, "protocol_error");
    await client.waitClosed();
  });

  test("takeover intent opens control with takeover and never falls back", async () => {
    launcher.controlFailure = "conflict"; // takeover must ignore the conflict path
    const client = await openClient();
    await hello(client, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "takeover" });
    const options = launcher.opens.at(-1)!.options;
    assert.equal(options.mode, "control");
    assert.equal(options.takeover, true);
    launcher.controlFailure = null;
    client.close();
    await client.waitClosed();
  });

  test("mid-session taken-over emits closed(taken_over) and ends the socket", async () => {
    const client = await openClient();
    await hello(client, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    launcher.last()!.emitClosed("taken-over", "terminal attach taken over");
    const closed = await client.waitForType("closed");
    assert.equal(closed.reason, "taken_over");
    const { code } = await client.waitClosed();
    assert.equal(code, 1000);
  });

  test("pane gone emits closed(pane_closed); a fresh hello starts a new generation", async () => {
    const client = await openClient();
    const ready = await hello(client, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    const firstGen = ready.generation as number;
    launcher.last()!.emitClosed("terminal-gone", "terminal attach ended: pane not found");
    const closed = await client.waitForType("closed");
    assert.equal(closed.reason, "pane_closed");
    await client.waitClosed();

    const client2 = await openClient();
    const ready2 = await hello(client2, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    assert.ok((ready2.generation as number) > firstGen);
    client2.close();
    await client2.waitClosed();
  });

  test("child failure emits error(child_failed, retryable)", async () => {
    const client = await openClient();
    await hello(client, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    launcher.last()!.emitError("record stream broke");
    const error = await client.waitForType("error");
    assert.equal(error.code, "child_failed");
    assert.equal(error.retryable, true);
    await client.waitClosed();
  });

  test("explicit release emits closed(released) and ends the socket", async () => {
    const client = await openClient();
    await hello(client, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    const proc = launcher.last()!;
    client.send({ type: "release" });
    const closed = await client.waitForType("closed");
    assert.equal(closed.reason, "released");
    const { code } = await client.waitClosed();
    assert.equal(code, 1000);
    await waitUntil(() => proc.releasedFlag);
  });

  test("protocol violations: non-hello first message, second hello, garbage JSON", async () => {
    const client = await openClient();
    client.sendRaw('{"type":"resize","cols":10,"rows":10}');
    const error = await client.waitForType("error");
    assert.equal(error.code, "protocol_error");
    await client.waitClosed();

    const client2 = await openClient();
    client2.sendRaw("this is not json");
    const error2 = await client2.waitForType("error");
    assert.equal(error2.code, "protocol_error");
    await client2.waitClosed();

    const client3 = await openClient();
    await hello(client3, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    client3.send({ type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    const error3 = await client3.waitForType("error");
    assert.equal(error3.code, "protocol_error");
    // The double-hello session enters grace (socket was ended by the adapter).
    const proc = launcher.last()!;
    await client3.waitClosed();
    await waitUntil(() => proc.releasedFlag, 2000);
  });

  test("binary frames before ready are a protocol error", async () => {
    launcher.openGate = new Promise((resolve) => setTimeout(resolve, 150));
    const client = await openClient();
    client.send({ type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    client.sendBinary(Buffer.from("early"));
    const error = await client.waitForType("error");
    assert.equal(error.code, "protocol_error");
    await client.waitClosed();
    launcher.openGate = null;
  });

  test("same-token replacement: new socket supersedes the old one with closed(replaced)", async () => {
    const clientA = await openClient();
    const readyA = await hello(clientA, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    const genA = readyA.generation as number;
    const procA = launcher.last()!;

    const clientB = await openClient();
    const readyB = await hello(clientB, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    assert.ok((readyB.generation as number) > genA);

    // Old socket: closed(replaced), then the wire ends; no further frames.
    const closedA = await clientA.waitForType("closed");
    assert.equal(closedA.reason, "replaced");
    await clientA.waitClosed();
    const countAtClose = clientA.messages.length;

    // A child event on the old process must not leak onto the closed socket.
    procA.emitBytes(Buffer.from("stale"));
    await new Promise((resolve) => setTimeout(resolve, 60));
    assert.equal(clientA.messages.length, countAtClose);
    await waitUntil(() => procA.releasedFlag);

    clientB.close();
    await clientB.waitClosed();
  });

  test("socket loss starts a grace; expiry releases the child", async () => {
    const client = await openClient();
    await hello(client, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    const proc = launcher.last()!;
    assert.equal(proc.releasedFlag, false);
    client.close();
    await client.waitClosed();
    // Grace holds the child briefly...
    await new Promise((resolve) => setTimeout(resolve, 40));
    assert.equal(proc.releasedFlag, false);
    // ...then releases it.
    await waitUntil(() => proc.releasedFlag, 2000);
  });

  test("grace resume: a hello during grace releases the old child and opens a fresh one", async () => {
    const opensBefore = launcher.opens.length;
    const clientA = await openClient();
    const readyA = await hello(clientA, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    const procA = launcher.last()!;
    clientA.close();
    await clientA.waitClosed();

    // Reconnect while procA is still in grace: the session is replaced, the
    // old child is released, and a fresh generation replays to the new socket.
    const clientB = await openClient();
    const ready = await hello(clientB, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    assert.equal(launcher.opens.length, opensBefore + 2);
    assert.notEqual(launcher.last(), procA);
    assert.equal(procA.releasedFlag, true);
    assert.ok((ready.generation as number) > (readyA.generation as number));
    clientB.close();
    await clientB.waitClosed();
  });

  test("same-token hello for a different pane ends the old session", async () => {
    const clientA = await openClient();
    await hello(clientA, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    const procA = launcher.last()!;

    const clientB = await openClient();
    const readyB = await hello(clientB, { type: "hello", version: 1, paneId: "w1:p2", cols: 120, rows: 30, intent: "auto" });
    assert.equal(readyB.paneId, "w1:p2");
    const closedA = await clientA.waitForType("closed");
    assert.equal(closedA.reason, "replaced");
    await clientA.waitClosed();
    await waitUntil(() => procA.releasedFlag);
    clientB.close();
    await clientB.waitClosed();
  });

  test("concurrent hellos: the later one wins, the earlier one is discarded", async () => {
    const opensBefore = launcher.opens.length;
    launcher.openGate = new Promise((resolve) => setTimeout(resolve, 120));
    const clientA = await openClient();
    const clientB = await openClient();
    clientA.send({ type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    clientB.send({ type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });

    const readyB = await clientB.waitForType("ready");
    assert.equal(readyB.mode, "control");
    const errorA = await clientA.waitForType("error");
    assert.equal(errorA.code, "replaced");
    assert.equal(errorA.retryable, true);
    await clientA.waitClosed();
    clientB.close();
    await clientB.waitClosed();

    launcher.openGate = null;
    await waitUntil(() => launcher.opens.length === opensBefore + 2);
    const first = launcher.opens[opensBefore]!;
    const second = launcher.opens[opensBefore + 1]!;
    assert.equal(first.process.releasedFlag, true);
    assert.equal(second.process.releasedFlag, false);
  });
  test("startup failure maps to retryable startup_error", async () => {
    launcher.controlFailure = "spawn";
    const client = await openClient();
    client.send({ type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    const error = await client.waitForType("error");
    assert.equal(error.code, "startup_error");
    assert.equal(error.retryable, true);
    await client.waitClosed();
    launcher.controlFailure = null;
  });

  test("shutdown releases children and terminates terminal sockets", async () => {
    const client = await openClient();
    await hello(client, { type: "hello", version: 1, paneId: "w1:p1", cols: 120, rows: 30, intent: "auto" });
    const proc = launcher.last()!;
    await server.close();
    await waitUntil(() => proc.releasedFlag);
    await client.waitClosed();
  });
});

describe("terminal adapter backpressure (fake socket)", () => {
  class FakeSocket implements TerminalSocketLike {
    buffered = 0;
    open = true;
    closeCode: number | null = null;
    readonly sent: { kind: "text" | "binary"; data: Buffer }[] = [];
    private messageHandler: ((data: Buffer, isBinary: boolean) => void) | null = null;
    private closeHandler: (() => void) | null = null;

    isOpen(): boolean {
      return this.open;
    }
    bufferedAmount(): number {
      return this.buffered;
    }
    sendText(text: string): void {
      this.sent.push({ kind: "text", data: Buffer.from(text) });
    }
    sendBinary(bytes: Buffer): void {
      this.sent.push({ kind: "binary", data: bytes });
    }
    close(code: number): void {
      this.open = false;
      this.closeCode = code;
      this.closeHandler?.();
    }
    terminate(): void {
      this.open = false;
    }
    onMessage(handler: (data: Buffer, isBinary: boolean) => void): void {
      this.messageHandler = handler;
    }
    onClose(handler: () => void): void {
      this.closeHandler = handler;
    }
    onError(): void {
      /* noop */
    }
    emitMessage(data: Buffer, isBinary: boolean): void {
      this.messageHandler?.(data, isBinary);
    }
  }

  function makeConnection(options: { broker: TerminalSessionBroker; now?: () => number }): {
    socket: FakeSocket;
    conn: TerminalConnection;
  } {
    const socket = new FakeSocket();
    const conn = new TerminalConnection(socket, {
      broker: options.broker,
      identity: TOKEN,
      highWaterBytes: 512,
      lowWaterBytes: 128,
      slowClientTimeoutMs: 60,
      inputQueueMaxBytes: 1024,
      now: options.now,
    });
    // The connection must outlive the test; holding it in a variable keeps
    // its timers referenced.
    void conn;
    return { socket, conn };
  }

  function makeFreshEnv(): { launcher: FakeTerminalLauncher; feed: ReturnType<typeof fakeFeed> } {
    return { launcher: new FakeTerminalLauncher(), feed: fakeFeed(snapshotWithPanes(["w1:p1"])) };
  }

  test("slow client: sustained high bufferedAmount pauses output then errors and enters grace", async () => {
    const { launcher, feed } = makeFreshEnv();
    const broker = new TerminalSessionBroker({ launcher, feed, graceMs: 100 });
    let now = 1_000;
    const { socket } = makeConnection({ broker, now: () => now });

    socket.emitMessage(Buffer.from(JSON.stringify({ type: "hello", version: 1, paneId: "w1:p1", cols: 80, rows: 24, intent: "auto" })), false);
    await waitUntil(() => launcher.opens.length === 1);
    const proc = launcher.last()!;

    socket.buffered = 600; // above high water
    proc.emitBytes(Buffer.from("big output"));
    assert.equal(proc.pauseCount, 1);

    now = 1_000 + 120; // well past slowClientTimeoutMs=60
    await waitUntil(() => socket.sent.some((m) => m.kind === "text" && m.data.toString().includes('"slow_client"')));
    assert.equal(socket.closeCode, 1001);
    assert.equal(proc.resumeCount, 1); // grace resumes draining
    await waitUntil(() => proc.releasedFlag, 2000); // grace expiry releases
    broker.close();
  });

  test("slow client recovers when bufferedAmount drops below low water", async () => {
    const { launcher, feed } = makeFreshEnv();
    const broker = new TerminalSessionBroker({ launcher, feed, graceMs: 5000 });
    const { socket } = makeConnection({ broker });

    socket.emitMessage(Buffer.from(JSON.stringify({ type: "hello", version: 1, paneId: "w1:p1", cols: 80, rows: 24, intent: "auto" })), false);
    await waitUntil(() => launcher.opens.length === 1);
    const proc = launcher.last()!;

    socket.buffered = 600;
    proc.emitBytes(Buffer.from("surge"));
    assert.equal(proc.pauseCount, 1);

    socket.buffered = 64; // below low water
    proc.emitBytes(Buffer.from("drain"));
    assert.equal(proc.resumeCount, 1);
    assert.equal(proc.pauseCount, 1);
    const binary = socket.sent.filter((m) => m.kind === "binary");
    assert.equal(binary.length, 3); // replay + surge + drain
    broker.close();
  });

  test("input queue: backpressure buffers, overflow errors with input_backpressure", async () => {
    const { launcher, feed } = makeFreshEnv();
    const broker = new TerminalSessionBroker({ launcher, feed, graceMs: 5000 });
    const { socket } = makeConnection({ broker });

    socket.emitMessage(Buffer.from(JSON.stringify({ type: "hello", version: 1, paneId: "w1:p1", cols: 80, rows: 24, intent: "auto" })), false);
    await waitUntil(() => launcher.opens.length === 1);
    const proc = launcher.last()!;
    proc.inputBlocked = true;

    socket.emitMessage(Buffer.alloc(512), true);
    socket.emitMessage(Buffer.alloc(512), true);
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.equal(proc.inputs.length, 0); // held while stdin is blocked

    socket.emitMessage(Buffer.alloc(512), true); // 1536 > 1024
    await waitUntil(() => socket.sent.some((m) => m.kind === "text" && m.data.toString().includes('"input_backpressure"')));
    assert.equal(socket.closeCode, 1008);
    broker.close();
  });

  test("input queue drains when the child unblocks", async () => {
    const { launcher, feed } = makeFreshEnv();
    const broker = new TerminalSessionBroker({ launcher, feed, graceMs: 5000 });
    const { socket } = makeConnection({ broker });

    socket.emitMessage(Buffer.from(JSON.stringify({ type: "hello", version: 1, paneId: "w1:p1", cols: 80, rows: 24, intent: "auto" })), false);
    await waitUntil(() => launcher.opens.length === 1);
    const proc = launcher.last()!;
    proc.inputBlocked = true;

    socket.emitMessage(Buffer.from("ab", "utf8"), true);
    socket.emitMessage(Buffer.from("cd", "utf8"), true);
    proc.inputBlocked = false;
    await waitUntil(() => proc.inputs.length === 2);
    assert.equal(proc.inputs[0]!.toString(), "ab");
    assert.equal(proc.inputs[1]!.toString(), "cd");
    broker.close();
  });
});
