import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, it } from "node:test";
import { extractAskPreamble } from "../src/agents/claude/ask-preamble.js";
import { claudeCaptureAskContext } from "../src/agents/claude/index.js";
import { readPendingAsk, writePendingAsk } from "../src/agents/claude/pending-asks.js";
import { claudeQuestions } from "../src/agents/claude/questions.js";
import { fakeHerdr } from "./support/fake-herdr.js";
import type { AgentReadResponse } from "../src/herdr/types.js";
import type { Transcript } from "../src/transcript.js";

const SESSION = "9f1c2d3e-0000-0000-0000-0000000000a1";
const PATH = `/home/u/.claude/projects/p/${SESSION}.jsonl`;

/**
 * A blocked pane, as `agent read --source visible` renders it: the prose that
 * introduced the ask, then the questionnaire box. Captured live from Claude
 * Code 2.1.241.
 */
const PANE_WITH_ASK = [
  "",
  "  Searched for 3 patterns, read 1 file, ran 15 shell commands",
  "",
  "● I dug into this before asking, so here's what I have.",
  "",
  "  The card itself isn't hiding anything — ChatList renders AskCard as an",
  "  ordinary row in the same LazyColumn as message bubbles, so any assistant",
  "  text above it would just show.",
  "────────────────────────────────────────────────────────────────────────",
  " ☐ Source",
  "",
  "│ Should the bridge scrape it?",
  "",
  "❯ 1. Scrape the pane once per ask",
  "  2. Leave it",
  "────────────────────────────────────────────────────────────────────────",
  "",
  "  esc to cancel",
].join("\n");

/** A pane snapshot, in the envelope `agentRead` returns it in. */
function paneRead(text: string): AgentReadResponse {
  return {
    type: "pane_read",
    read: {
      pane_id: "w1:p1",
      workspace_id: "ws1",
      tab_id: "t1",
      source: "visible",
      format: "text",
      text,
      revision: 0,
      truncated: false,
    },
  };
}

/** The port a blocked pane presents: one readable screen, nothing else. */
function paneShowing(text: string) {
  const base = fakeHerdr();
  return { ...base, sent: base.sent, agentRead: async () => paneRead(text) };
}

function emptyTranscript(): Transcript {
  return {
    version: 3,
    id: SESSION,
    cwd: "/work",
    timestamp: "2026-08-24T00:00:00Z",
    entries: [],
    model: null,
    thinkingLevel: null,
    lastEntryId: null,
    title: null,
    preview: "",
  };
}

function recordPendingAsk(): void {
  writePendingAsk({
    sessionId: SESSION,
    toolUseId: "toolu_ask",
    timestamp: new Date().toISOString(),
    transcriptPath: PATH,
    questions: [{ question: "Should the bridge scrape it?", header: "Source", options: [{ label: "Yes" }] }],
  });
}

describe("extractAskPreamble", () => {
  it("takes the prose between the last bullet and the questionnaire box", () => {
    assert.equal(
      extractAskPreamble(PANE_WITH_ASK),
      [
        "I dug into this before asking, so here's what I have.",
        "",
        "The card itself isn't hiding anything — ChatList renders AskCard as an"
          + " ordinary row in the same LazyColumn as message bubbles, so any assistant"
          + " text above it would just show.",
      ].join("\n"),
    );
  });

  it("keeps list items on their own lines while rejoining wrapped prose", () => {
    const pane = [
      "● Two things decide this:",
      "",
      "  - the pane is the only live source, because the whole assistant turn is",
      "    buffered",
      "  - the hook payload carries no prose",
      "──────────────────────────────",
      "│ Which one?",
    ].join("\n");
    assert.equal(
      extractAskPreamble(pane),
      [
        "Two things decide this:",
        "",
        "- the pane is the only live source, because the whole assistant turn is buffered",
        "- the hook payload carries no prose",
      ].join("\n"),
    );
  });

  it("refuses a tool call bullet rather than passing one off as prose", () => {
    const pane = [
      "● Bash(npm test)",
      "──────────────────────────────",
      "│ Which one?",
    ].join("\n");
    assert.equal(extractAskPreamble(pane), "");
  });

  it("refuses a bullet whose block carries tool output", () => {
    const pane = [
      "● User answered Claude's questions:",
      "  ⎿  · Which color? → Green",
      "──────────────────────────────",
      "│ Which one?",
    ].join("\n");
    assert.equal(extractAskPreamble(pane), "");
  });

  it("returns nothing when the prose scrolled off above the pane", () => {
    const pane = [
      "  text above it would just show, but its bullet is long gone.",
      "──────────────────────────────",
      "│ Which one?",
    ].join("\n");
    assert.equal(extractAskPreamble(pane), "");
  });

  it("returns nothing for a pane with no questionnaire on it", () => {
    assert.equal(extractAskPreamble("● Done — the tests pass.\n"), "");
  });
});

describe("claudeCaptureAskContext", () => {
  let home = "";
  const realHome = process.env.XDG_CONFIG_HOME;

  beforeEach(async () => {
    home = await mkdtemp(join(tmpdir(), "scoutr-preamble-"));
    process.env.XDG_CONFIG_HOME = home;
  });
  afterEach(async () => {
    if (realHome === undefined) delete process.env.XDG_CONFIG_HOME;
    else process.env.XDG_CONFIG_HOME = realHome;
    await rm(home, { recursive: true, force: true });
  });

  it("puts the pane's prose on every card of the open ask", async () => {
    recordPendingAsk();
    const herdr = paneShowing(PANE_WITH_ASK);
    await claudeCaptureAskContext(herdr, "w1:p1", PATH);
    const questions = claudeQuestions(emptyTranscript());
    assert.equal(questions.length, 1);
    assert.match(questions[0]?.preamble ?? "", /^I dug into this before asking/);
  });

  it("reads the pane once per ask, however many polls see it", async () => {
    recordPendingAsk();
    let reads = 0;
    const base = fakeHerdr();
    const herdr = { ...base, agentRead: async () => { reads += 1; return paneRead(PANE_WITH_ASK); } };
    await claudeCaptureAskContext(herdr, "w1:p1", PATH);
    await claudeCaptureAskContext(herdr, "w1:p1", PATH);
    assert.equal(reads, 1);
  });

  it("marks a pane it could not read, so the poll stops paying for it", async () => {
    recordPendingAsk();
    const herdr = fakeHerdr();
    herdr.failNext("agentRead", new Error("agent_not_idle"));
    await claudeCaptureAskContext(herdr, "w1:p1", PATH);
    assert.equal(readPendingAsk(SESSION)?.preambleCaptured, true);
    assert.equal(claudeQuestions(emptyTranscript())[0]?.preamble, undefined);
  });

  it("does nothing when the ask is already over", async () => {
    const herdr = fakeHerdr();
    await claudeCaptureAskContext(herdr, "w1:p1", PATH);
    assert.equal(readPendingAsk(SESSION), null);
    assert.equal(herdr.sent.length, 0);
  });
});
