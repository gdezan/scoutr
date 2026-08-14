import { homedir, tmpdir } from "node:os";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { createSession, controlSession, launchStoredSession, SessionsError, THINKING_LEVELS } from "../src/sessions.js";
import { piLaunchCommand, thinkingLevelKeys } from "../src/agents/pi/index.js";
import { shellQuote } from "../src/shell.js";
import { pane, snapshot, tab, workspace } from "./support/snapshot.js";

function fakeHerdr(overrides: Record<string, unknown> = {}) {
  const calls: { method: string; params: any }[] = [];
  let snapshotReads = 0;
  const herdr = {
    calls,
    async workspaceCreate(params: unknown) {
      calls.push({ method: "workspace.create", params });
      return overrides.workspaceCreate ?? { workspace: { workspace_id: "ws1" }, root_pane: { pane_id: "p1" } };
    },
    async tabCreate(params: unknown) {
      calls.push({ method: "tab.create", params });
      return overrides.tabCreate ?? { tab: { tab_id: "t1" }, root_pane: { pane_id: "p1" } };
    },
    async tabRename(tab_id: string, label: string) {
      calls.push({ method: "tab.rename", params: { tab_id, label } });
      return {};
    },
    async tabClose(tab_id: string) {
      calls.push({ method: "tab.close", params: { tab_id } });
      return {};
    },
    async workspaceClose(workspace_id: string) {
      calls.push({ method: "workspace.close", params: { workspace_id } });
      if (overrides.workspaceCloseError) throw overrides.workspaceCloseError;
      return {};
    },
    async paneSendText(pane_id: string, text: string) {
      calls.push({ method: "pane.send_text", params: { pane_id, text } });
      return {};
    },
    async paneSendKeys(pane_id: string, keys: string[]) {
      calls.push({ method: "pane.send_keys", params: { pane_id, keys } });
      return {};
    },
    async paneSendInput(pane_id: string, text: string, keys: string[]) {
      calls.push({ method: "pane.send_input", params: { pane_id, text, keys } });
      if (overrides.paneSendInputError) throw overrides.paneSendInputError;
      return {};
    },
    async agentPrompt(target: string, text: string) {
      calls.push({ method: "agent.prompt", params: { target, text } });
      if (overrides.agentPromptError) throw overrides.agentPromptError;
      return {};
    },
    async workspaceRename(workspace_id: string, label: string) {
      calls.push({ method: "workspace.rename", params: { workspace_id, label } });
      return {};
    },
    async snapshot() {
      calls.push({ method: "session.snapshot", params: {} });
      // Only the first read fails: the launch must survive a snapshot outage
      // at workspace-lookup time and still detect the agent afterwards.
      if (overrides.snapshotError && snapshotReads++ === 0) throw overrides.snapshotError;
      return overrides.snapshot ?? {
        panes: [{ pane_id: "p1", workspace_id: "ws1", tab_id: "t1", agent: "pi" }],
        workspaces: [],
        tabs: [],
        agents: [],
      };
    },
  };
  return herdr as never;
}

const cwd = homedir();

describe("createSession", () => {
  it("creates a workspace for an unclaimed folder, starts pi in one input call, and waits for agent detection", async () => {
    const herdr = fakeHerdr();

    const created = await createSession(herdr, { cwd, model: "openai-codex/gpt-5.4", name: "demo" });

    assert.deepEqual(created, { workspaceId: "ws1", paneId: "p1" });
    assert.deepEqual(herdr.calls.map((call) => call.method), [
      "session.snapshot",
      "workspace.create",
      "pane.send_input",
      "session.snapshot",
      "tab.rename",
    ]);
    // The workspace is the folder's, not the session's: no session label on it.
    assert.deepEqual(herdr.calls[1].params, { cwd, focus: false });
    assert.deepEqual(herdr.calls[2].params, {
      pane_id: "p1",
      text: "pi --model 'openai-codex/gpt-5.4' --name 'demo'",
      keys: ["Enter"],
    });
  });

  it("delivers multiline and option-like prompts through agent.prompt, not the shell", async () => {
    const herdr = fakeHerdr();
    const initialPrompt = "--help\n\nFix the failing test; don't alter behavior.";

    await createSession(herdr, {
      cwd,
      model: "openai-codex/gpt-5.4",
      thinkingLevel: "high",
      name: "demo",
      initialPrompt,
    });

    assert.deepEqual(herdr.calls.map((call) => call.method), [
      "session.snapshot",
      "workspace.create",
      "pane.send_input",
      "session.snapshot",
      "tab.rename",
      "agent.prompt",
    ]);
    assert.equal(herdr.calls[2].params.text, "pi --model 'openai-codex/gpt-5.4' --thinking 'high' --name 'demo'");
    assert.deepEqual(herdr.calls[5].params, { target: "p1", text: initialPrompt });
  });

  it("preserves prompt-less creation", async () => {
    for (const initialPrompt of [undefined, ""]) {
      const herdr = fakeHerdr();
      await createSession(herdr, { cwd, model: "m1", initialPrompt });
      assert.equal(herdr.calls[2].params.text, "pi --model 'm1'");
      assert.equal(herdr.calls.some((call) => call.method === "agent.prompt"), false);
    }
  });

  it("quotes every shell argument while keeping the prompt out of the command", async () => {
    const herdr = fakeHerdr();
    const initialPrompt = "say 'hi' && echo pwned; $(whoami) `id`";

    await createSession(herdr, {
      cwd,
      model: "o'brien; rm -rf /",
      thinkingLevel: "off",
      name: "$(id) `ls` \"quoted\"",
      initialPrompt,
    });

    assert.equal(
      herdr.calls[2].params.text,
      "pi --model 'o'\\''brien; rm -rf /' --thinking 'off' --name '$(id) `ls` \"quoted\"'",
    );
    assert.equal(herdr.calls[5].params.text, initialPrompt);
  });

  it("piLaunchCommand quotes supported options", () => {
    assert.equal(piLaunchCommand({ model: "a b" }), "pi --model 'a b'");
    assert.equal(
      piLaunchCommand({ model: "m", thinkingLevel: "max", name: "it's" }),
      "pi --model 'm' --thinking 'max' --name 'it'\\''s'",
    );
    assert.equal(shellQuote("a; $(id)"), "'a; $(id)'");
  });

  it("rejects invalid input before creating a workspace", async () => {
    const herdr = fakeHerdr();
    await assert.rejects(createSession(herdr, { cwd: "", model: "m" }), SessionsError);
    await assert.rejects(createSession(herdr, { cwd, model: "" }), SessionsError);
    await assert.rejects(createSession(herdr, { cwd: "/", model: "m" }), /outside allowed root/);
    await assert.rejects(createSession(herdr, { cwd, model: "m\nrm -rf /" }), SessionsError);
    await assert.rejects(createSession(herdr, { cwd, model: "m", name: "a\u0000b" }), SessionsError);
    await assert.rejects(createSession(herdr, { cwd, model: "m", initialPrompt: "a\u0000b" }), SessionsError);
    assert.equal(herdr.calls.length, 0);
  });

  it("accepts every documented thinking level", async () => {
    for (const level of THINKING_LEVELS) {
      const herdr = fakeHerdr();
      await createSession(herdr, { cwd, model: "m", thinkingLevel: level });
      assert.equal(herdr.calls[2].params.text, `pi --model 'm' --thinking '${level}'`);
    }
  });

  it("rejects unknown thinking levels and over-long fields", async () => {
    const herdr = fakeHerdr();
    await assert.rejects(createSession(herdr, { cwd, model: "m", thinkingLevel: "insane" }), /unknown thinking level/);
    await assert.rejects(createSession(herdr, { cwd, model: "m".repeat(201) }), /model is too long/);
    await assert.rejects(createSession(herdr, { cwd, model: "m", name: "n".repeat(101) }), /name is too long/);
    await assert.rejects(createSession(herdr, { cwd, model: "m", initialPrompt: "p".repeat(100_001) }), /initialPrompt is too long/);
    assert.equal(herdr.calls.length, 0);
  });

  it("closes the workspace when launch input fails", async () => {
    const herdr = fakeHerdr({ paneSendInputError: new Error("send failed") });

    await assert.rejects(createSession(herdr, { cwd, model: "m" }), /session launch failed: send failed/);

    assert.deepEqual(herdr.calls.map((call) => call.method), [
      "session.snapshot",
      "workspace.create",
      "pane.send_input",
      "workspace.close",
    ]);
  });

  it("closes the workspace when first-prompt delivery fails", async () => {
    const herdr = fakeHerdr({ agentPromptError: new Error("prompt failed") });

    await assert.rejects(createSession(herdr, { cwd, model: "m", initialPrompt: "Task" }), /session launch failed: prompt failed/);

    assert.equal(herdr.calls.at(-1)?.method, "workspace.close");
  });

  it("closes a workspace that has no root pane id", async () => {
    const herdr = fakeHerdr({ workspaceCreate: { workspace: { workspace_id: "ws1" } } });

    await assert.rejects(createSession(herdr, { cwd, model: "m" }), /did not return a pane id/);

    assert.equal(herdr.calls.at(-1)?.method, "workspace.close");
  });
});

/**
 * Workspaces are per folder. A session started on a folder that already has a
 * workspace becomes a tab in it; only an unclaimed folder gets a workspace.
 */
describe("createSession workspace reuse", () => {
  /** A workspace rooted at `cwd`, plus the live pane tab.create will return. */
  function folderWorkspace(options: { rootCwd: string; workspaceId?: string; number?: number }) {
    const workspaceId = options.workspaceId ?? "ws1";
    return {
      workspace: workspace({ workspace_id: workspaceId, number: options.number ?? 1 }),
      root: pane({ pane_id: `${workspaceId}-root`, workspace_id: workspaceId, tab_id: `${workspaceId}-t0`, cwd: options.rootCwd }),
    };
  }

  /** The pane tab.create/workspace.create hands back, already running an agent. */
  const launched = (workspaceId: string) =>
    pane({ pane_id: "p1", workspace_id: workspaceId, tab_id: "t1", agent: "pi", cwd });

  it("adds a tab labeled with the session name to the workspace already on the folder", async () => {
    const existing = folderWorkspace({ rootCwd: cwd });
    const herdr = fakeHerdr({ snapshot: snapshot([existing.root, launched("ws1")], [], [existing.workspace]) });

    const created = await createSession(herdr, { cwd, model: "m", name: "demo" });

    assert.deepEqual(created, { workspaceId: "ws1", paneId: "p1" });
    assert.deepEqual(herdr.calls.map((call) => call.method), ["session.snapshot", "tab.create", "pane.send_input", "session.snapshot"]);
    assert.deepEqual(herdr.calls[1].params, { workspace_id: "ws1", cwd, label: "demo", focus: false });
    // The workspace is the folder's: its label is never rewritten by a session.
    assert.equal(herdr.calls.some((call) => call.method === "workspace.rename"), false);
  });

  it("names the root tab of a workspace it had to create", async () => {
    const herdr = fakeHerdr({ snapshot: snapshot([launched("ws1")], [], []) });

    await createSession(herdr, { cwd, model: "m", name: "demo" });

    assert.deepEqual(herdr.calls.at(-1), { method: "tab.rename", params: { tab_id: "t1", label: "demo" } });
  });

  it("matches on the workspace root pane only, never a pane that cd-ed into the folder", async () => {
    const elsewhere = folderWorkspace({ rootCwd: join(homedir(), "somewhere-else") });
    const wanderer = pane({ pane_id: "p9", workspace_id: "ws1", tab_id: "ws1-t1", cwd });
    const herdr = fakeHerdr({ snapshot: snapshot([elsewhere.root, wanderer, launched("ws2")], [], [elsewhere.workspace]) });

    await createSession(herdr, { cwd, model: "m" });

    assert.equal(herdr.calls[1].method, "workspace.create");
  });

  it("reuses the lowest-numbered workspace when several sit on the folder", async () => {
    const second = folderWorkspace({ rootCwd: cwd, workspaceId: "ws2", number: 2 });
    const first = folderWorkspace({ rootCwd: cwd, workspaceId: "ws1", number: 7 });
    const older = folderWorkspace({ rootCwd: cwd, workspaceId: "ws0", number: 1 });
    const herdr = fakeHerdr({
      snapshot: snapshot(
        [second.root, first.root, older.root, launched("ws0")],
        [],
        [second.workspace, first.workspace, older.workspace],
      ),
    });

    const created = await createSession(herdr, { cwd, model: "m" });

    assert.equal(created.workspaceId, "ws0");
    assert.deepEqual((herdr.calls[1].params as { workspace_id: string }).workspace_id, "ws0");
  });

  it("closes only its own tab when a launch into a reused workspace fails", async () => {
    const existing = folderWorkspace({ rootCwd: cwd });
    const herdr = fakeHerdr({
      snapshot: snapshot([existing.root, launched("ws1")], [], [existing.workspace]),
      paneSendInputError: new Error("send failed"),
    });

    await assert.rejects(createSession(herdr, { cwd, model: "m" }), /session launch failed: send failed/);

    assert.deepEqual(herdr.calls.map((call) => call.method), ["session.snapshot", "tab.create", "pane.send_input", "tab.close"]);
    assert.deepEqual(herdr.calls.at(-1)?.params, { tab_id: "t1" });
  });

  it("falls back to a new workspace when the snapshot is unavailable", async () => {
    const herdr = fakeHerdr({ snapshotError: new Error("herdr is down") });

    await createSession(herdr, { cwd, model: "m" });

    assert.equal(herdr.calls[1].method, "workspace.create");
  });
});

describe("launchStoredSession", () => {
  it("opens or forks an allowed session with one quoted pi command", async () => {
    const root = await mkdtemp(join(homedir(), ".scoutr-stored-session-"));
    const path = join(root, "saved.jsonl");
    await writeFile(path, `${JSON.stringify({
      type: "session",
      version: 3,
      id: "saved-session",
      timestamp: "2026-01-01T00:00:00.000Z",
      cwd,
    })}\n`);
    process.env.PI_CODING_AGENT_SESSION_DIR = root;
    try {
      for (const mode of ["resume", "fork"] as const) {
        const herdr = fakeHerdr();
        const created = await launchStoredSession(herdr, { path, mode });
        assert.deepEqual(created, { workspaceId: "ws1", paneId: "p1" });
        assert.equal(herdr.calls[1].method, "workspace.create");
        assert.deepEqual(herdr.calls[2].params, {
          pane_id: "p1",
          text: `pi --${mode === "resume" ? "session" : "fork"} ${shellQuote(path)}`,
          keys: ["Enter"],
        });
      }
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  it("rejects a session outside the configured store before any Herdr call", async () => {
    const root = await mkdtemp(join(homedir(), ".scoutr-stored-session-"));
    const herdr = fakeHerdr();
    process.env.PI_CODING_AGENT_SESSION_DIR = root;
    try {
      await assert.rejects(
        launchStoredSession(herdr, { path: "/etc/passwd", mode: "resume" }),
        /outside a registered session store/,
      );
      assert.equal(herdr.calls.length, 0);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  it("resumes a session whose recorded cwd is outside the home directory", async () => {
    // Fix 7 (user decision): the cwd recorded in the session file is trusted,
    // so a session run in e.g. /tmp can be resumed from the app.
    const root = await mkdtemp(join(homedir(), ".scoutr-stored-session-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = root;
    const outsideCwd = await mkdtemp(join(tmpdir(), "scoutr-resume-cwd-"));
    const path = join(root, "outside-cwd.jsonl");
    await writeFile(path, `${JSON.stringify({
      type: "session",
      version: 3,
      id: "outside-cwd-session",
      timestamp: "2026-01-01T00:00:00.000Z",
      cwd: outsideCwd,
    })}\n`);
    try {
      const herdr = fakeHerdr();
      const created = await launchStoredSession(herdr, { path, mode: "resume" });
      assert.deepEqual(created, { workspaceId: "ws1", paneId: "p1" });
      assert.equal((herdr.calls[1].params as { cwd: string }).cwd, outsideCwd);
    } finally {
      await rm(root, { recursive: true, force: true });
      await rm(outsideCwd, { recursive: true, force: true });
    }
  });

  it("falls back to the session store root when the recorded cwd is gone", async () => {
    const root = await mkdtemp(join(homedir(), ".scoutr-stored-session-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = root;
    // Real pi session paths nest one level (encoded dir), so dirname alone
    // must not satisfy the fallback: the store root is the contract.
    const nested = join(root, "--encoded-dir--");
    await mkdir(nested, { recursive: true });
    const path = join(nested, "gone-cwd.jsonl");
    await writeFile(path, `${JSON.stringify({
      type: "session",
      version: 3,
      id: "gone-cwd-session",
      timestamp: "2026-01-01T00:00:00.000Z",
      cwd: join(tmpdir(), "scoutr-never-existed-", "nested"),
    })}\n`);
    try {
      const herdr = fakeHerdr();
      await launchStoredSession(herdr, { path, mode: "resume" });
      assert.equal((herdr.calls[1].params as { cwd: string }).cwd, root);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });
});

describe("controlSession", () => {
  it("abort sends escape without a snapshot (emergency control never blocks)", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "abort" });
    assert.deepEqual(herdr.calls.map((call) => call.method), ["pane.send_keys"]);
    assert.deepEqual(herdr.calls[0].params, { pane_id: "p1", keys: ["escape"] });
  });

  it("retry re-prompts with the last user message", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "retry", text: "fix the tests" });
    assert.deepEqual(herdr.calls.map((call) => call.method), ["session.snapshot", "agent.prompt"]);
    assert.deepEqual(herdr.calls[1].params, { target: "p1", text: "fix the tests" });
  });

  it("retry without text is rejected", async () => {
    const herdr = fakeHerdr();
    await assert.rejects(controlSession(herdr, { paneId: "p1", action: "retry" }), SessionsError);
  });

  it("compact submits /compact atomically", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "compact" });
    assert.deepEqual(herdr.calls.map((call) => call.method), ["session.snapshot", "pane.send_input"]);
    assert.deepEqual(herdr.calls[1].params, { pane_id: "p1", text: "/compact", keys: ["Enter"] });
  });

  it("fork types /fork + Enter", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "fork" });
    assert.equal(herdr.calls[1].params.text, "/fork");
  });

  it("rejects verbs the pane's backend does not advertise", async () => {
    // A live claude pane (id-kind reference): retry and fork are outside
    // claude's capabilities, so the direct control must fail, not submit
    // input into the TUI.
    const herdr = fakeHerdr({
      snapshot: {
        panes: [{ pane_id: "p2", workspace_id: "ws1", tab_id: "t1", agent: "claude", cwd: "/w", agent_status: "working" }],
        agents: [
          { pane_id: "p2", workspace_id: "ws1", tab_id: "t1", agent: "claude", agent_status: "working", agent_session: { source: "herdr:claude", agent: "claude", kind: "id", value: "abc" } },
        ],
      },
    });
    // The capability check rejects before the backend switch, with a 400
    // carrying the backend id.
    await assert.rejects(controlSession(herdr, { paneId: "p2", action: "retry", text: "x" }), /claude does not support retry/);
    await assert.rejects(controlSession(herdr, { paneId: "p2", action: "fork" }), /claude does not support fork/);
    await assert.rejects(
      controlSession(herdr, { paneId: "p2", action: "fork" }),
      (error: unknown) => error instanceof SessionsError && error.status === 400,
    );
  });

  it("rename persists the pi name and labels the session tab, not the folder's workspace", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "rename", text: "new name" });
    assert.deepEqual(herdr.calls.map((call) => call.method), ["session.snapshot", "pane.send_input", "session.snapshot", "tab.rename"]);
    assert.deepEqual(herdr.calls[1].params, { pane_id: "p1", text: "/name new name", keys: ["Enter"] });
    assert.deepEqual(herdr.calls[3].params, { tab_id: "t1", label: "new name" });
  });

  it("close takes the workspace with it when the session is the folder's last tab", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "close" });
    assert.deepEqual(herdr.calls.map((call) => call.method), ["session.snapshot", "session.snapshot", "workspace.close"]);
    assert.deepEqual(herdr.calls[2].params, { workspace_id: "ws1" });
  });

  it("close spares the folder's other sessions, closing only its own tab", async () => {
    const sibling = pane({ pane_id: "p2", workspace_id: "ws1", tab_id: "t2", agent: "pi" });
    const herdr = fakeHerdr({
      snapshot: snapshot(
        [pane({ pane_id: "p1", workspace_id: "ws1", tab_id: "t1", agent: "pi" }), sibling],
        [tab({ tab_id: "t1" }), tab({ tab_id: "t2", number: 2 })],
        [workspace({ workspace_id: "ws1" })],
      ),
    });

    await controlSession(herdr, { paneId: "p1", action: "close" });

    assert.deepEqual(herdr.calls.at(-1), { method: "tab.close", params: { tab_id: "t1" } });
    assert.equal(herdr.calls.some((call) => call.method === "workspace.close"), false);
  });

  it("rename of an unknown pane fails with 404", async () => {
    const herdr = fakeHerdr({ snapshot: { panes: [], workspaces: [], tabs: [], agents: [] } });
    await assert.rejects(controlSession(herdr, { paneId: "nope", action: "rename", text: "n" }), (error: unknown) => {
      assert.ok(error instanceof SessionsError);
      assert.equal(error.status, 404);
      return true;
    });
  });

  it("sets a catalog model with pi's exact /model command", async () => {
    const herdr = fakeHerdr();
    const agentDir = await mkdtemp(join(homedir(), ".scoutr-agent-"));
    await writeFile(join(agentDir, "models-store.json"), JSON.stringify({
      "openai-codex": { models: [{ id: "gpt-5.4", name: "GPT-5.4", reasoning: true }] },
    }));
    process.env.PI_CODING_AGENT_DIR = agentDir;
    try {
      await controlSession(herdr, { paneId: "p1", action: "set_model", text: "openai-codex/gpt-5.4" });
    } finally {
      await rm(agentDir, { recursive: true, force: true });
    }
    assert.deepEqual(herdr.calls.map((call) => call.method), ["session.snapshot", "pane.send_input"]);
    assert.deepEqual(herdr.calls[1].params, {
      pane_id: "p1",
      text: "/model openai-codex/gpt-5.4",
      keys: ["Enter"],
    });
  });

  it("sets thinking by cycling from the session's active level", async () => {
    const sessionDir = await mkdtemp(join(homedir(), ".scoutr-session-"));
    const sessionPath = join(sessionDir, "session.jsonl");
    await writeFile(
      sessionPath,
      [
        JSON.stringify({ type: "session", version: 3, id: "s1", cwd: "/w", timestamp: "2026-01-01T00:00:00.000Z" }),
        JSON.stringify({ type: "model_change", provider: "openai-codex", modelId: "gpt-5.4" }),
        JSON.stringify({ type: "thinking_level_change", thinkingLevel: "medium" }),
      ].join("\n") + "\n",
    );
    const agentDir = await mkdtemp(join(homedir(), ".scoutr-agent-"));
    await writeFile(join(agentDir, "models-store.json"), JSON.stringify({
      "openai-codex": { models: [{ id: "gpt-5.4", name: "GPT-5.4", reasoning: true, thinkingLevelMap: {
        off: "off", minimal: "minimal", low: "low", medium: "medium", high: "high", xhigh: "xhigh", max: "max",
      } }] },
    }));
    const snapshot = {
      panes: [{
        pane_id: "p1",
        workspace_id: "ws1",
        agent: "pi",
        agent_session: { agent: "pi", kind: "path", value: sessionPath },
      }],
      workspaces: [], tabs: [], agents: [],
    };
    const herdr = fakeHerdr({ snapshot });
    process.env.PI_CODING_AGENT_SESSION_DIR = sessionDir;
    process.env.PI_CODING_AGENT_DIR = agentDir;
    try {
      await controlSession(herdr, { paneId: "p1", action: "set_thinking", text: "xhigh" });
    } finally {
      await rm(sessionDir, { recursive: true, force: true });
      await rm(agentDir, { recursive: true, force: true });
    }
    assert.deepEqual(herdr.calls.map((call) => call.method), ["session.snapshot", "session.snapshot", "pane.send_keys"]);
    assert.deepEqual(herdr.calls[2].params.keys, ["shift+tab", "shift+tab"]);
  });

  it("calculates deterministic thinking cycles and rejects unsupported targets", () => {
    const levels = ["off", "minimal", "low", "medium", "high"];
    assert.deepEqual(thinkingLevelKeys("high", "minimal", levels), ["shift+tab", "shift+tab"]);
    assert.deepEqual(thinkingLevelKeys("low", "low", levels), []);
    assert.throws(() => thinkingLevelKeys("low", "max", levels), /not supported/);
  });

  it("rejects an unknown action", async () => {
    const herdr = fakeHerdr();
    await assert.rejects(controlSession(herdr, { paneId: "p1", action: "explode" as never }), SessionsError);
  });
});
