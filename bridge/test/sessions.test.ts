import { homedir, tmpdir } from "node:os";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { createSession, controlSession, launchStoredSession, SessionsError, THINKING_LEVELS } from "../src/sessions.js";
import { piLaunchCommand, thinkingLevelKeys } from "../src/agents/pi/index.js";
import { shellQuote } from "../src/shell.js";

function fakeHerdr(overrides: Record<string, unknown> = {}) {
  const calls: { method: string; params: any }[] = [];
  const herdr = {
    calls,
    async workspaceCreate(params: unknown) {
      calls.push({ method: "workspace.create", params });
      return overrides.workspaceCreate ?? { workspace: { workspace_id: "ws1" }, root_pane: { pane_id: "p1" } };
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
      return overrides.snapshot ?? {
        panes: [{ pane_id: "p1", workspace_id: "ws1", agent: "pi" }],
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
  it("creates a workspace, starts pi in one input call, and waits for agent detection", async () => {
    const herdr = fakeHerdr();

    const created = await createSession(herdr, { cwd, model: "openai-codex/gpt-5.4", name: "demo" });

    assert.deepEqual(created, { workspaceId: "ws1", paneId: "p1" });
    assert.deepEqual(herdr.calls.map((call) => call.method), ["workspace.create", "pane.send_input", "session.snapshot"]);
    assert.deepEqual(herdr.calls[0].params, { cwd, label: "demo", focus: false });
    assert.deepEqual(herdr.calls[1].params, {
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
      "workspace.create",
      "pane.send_input",
      "session.snapshot",
      "agent.prompt",
    ]);
    assert.equal(herdr.calls[1].params.text, "pi --model 'openai-codex/gpt-5.4' --thinking 'high' --name 'demo'");
    assert.deepEqual(herdr.calls[3].params, { target: "p1", text: initialPrompt });
  });

  it("preserves prompt-less creation", async () => {
    for (const initialPrompt of [undefined, ""]) {
      const herdr = fakeHerdr();
      await createSession(herdr, { cwd, model: "m1", initialPrompt });
      assert.equal(herdr.calls[1].params.text, "pi --model 'm1'");
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
      herdr.calls[1].params.text,
      "pi --model 'o'\\''brien; rm -rf /' --thinking 'off' --name '$(id) `ls` \"quoted\"'",
    );
    assert.equal(herdr.calls[3].params.text, initialPrompt);
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
      assert.equal(herdr.calls[1].params.text, `pi --model 'm' --thinking '${level}'`);
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

    assert.deepEqual(herdr.calls.map((call) => call.method), ["workspace.create", "pane.send_input", "workspace.close"]);
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

describe("launchStoredSession", () => {
  it("opens or forks an allowed session with one quoted pi command", async () => {
    const root = await mkdtemp(join(homedir(), ".cockpit-stored-session-"));
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
        assert.equal(herdr.calls[0].method, "workspace.create");
        assert.deepEqual(herdr.calls[1].params, {
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
    const root = await mkdtemp(join(homedir(), ".cockpit-stored-session-"));
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
    const root = await mkdtemp(join(homedir(), ".cockpit-stored-session-"));
    process.env.PI_CODING_AGENT_SESSION_DIR = root;
    const outsideCwd = await mkdtemp(join(tmpdir(), "cockpit-resume-cwd-"));
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
      assert.equal((herdr.calls[0].params as { cwd: string }).cwd, outsideCwd);
    } finally {
      await rm(root, { recursive: true, force: true });
      await rm(outsideCwd, { recursive: true, force: true });
    }
  });

  it("falls back to the session store root when the recorded cwd is gone", async () => {
    const root = await mkdtemp(join(homedir(), ".cockpit-stored-session-"));
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
      cwd: join(tmpdir(), "cockpit-never-existed-", "nested"),
    })}\n`);
    try {
      const herdr = fakeHerdr();
      await launchStoredSession(herdr, { path, mode: "resume" });
      assert.equal((herdr.calls[0].params as { cwd: string }).cwd, root);
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

  it("rename persists the pi name and updates the workspace label", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "rename", text: "new name" });
    assert.deepEqual(herdr.calls.map((call) => call.method), ["session.snapshot", "session.snapshot", "pane.send_input", "workspace.rename"]);
    assert.deepEqual(herdr.calls[2].params, { pane_id: "p1", text: "/name new name", keys: ["Enter"] });
    assert.deepEqual(herdr.calls[3].params, { workspace_id: "ws1", label: "new name" });
  });

  it("close resolves the pane workspace and closes it", async () => {
    const herdr = fakeHerdr();
    await controlSession(herdr, { paneId: "p1", action: "close" });
    assert.deepEqual(herdr.calls.map((call) => call.method), ["session.snapshot", "session.snapshot", "workspace.close"]);
    assert.deepEqual(herdr.calls[2].params, { workspace_id: "ws1" });
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
    const agentDir = await mkdtemp(join(homedir(), ".cockpit-agent-"));
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
    const sessionDir = await mkdtemp(join(homedir(), ".cockpit-session-"));
    const sessionPath = join(sessionDir, "session.jsonl");
    await writeFile(
      sessionPath,
      [
        JSON.stringify({ type: "session", version: 3, id: "s1", cwd: "/w", timestamp: "2026-01-01T00:00:00.000Z" }),
        JSON.stringify({ type: "model_change", provider: "openai-codex", modelId: "gpt-5.4" }),
        JSON.stringify({ type: "thinking_level_change", thinkingLevel: "medium" }),
      ].join("\n") + "\n",
    );
    const agentDir = await mkdtemp(join(homedir(), ".cockpit-agent-"));
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
