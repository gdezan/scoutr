import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  expandSkillInvocationContent,
  peelClaudeCommandInvocation,
  peelPiSkillInvocation,
  skillInvocationPreview,
} from "../src/skill-invocation.js";
import { entryText, type TranscriptEntry } from "../src/transcript.js";
import { parsePiTranscript } from "../src/agents/pi/transcript.js";
import { parseClaudeTranscript } from "../src/agents/claude/transcript.js";

const SKILL_XML = `<skill name="grill-me" location="/home/u/.agents/skills/grilling/SKILL.md">
References are relative to /home/u/.agents/skills/grilling.

# Grill me
Ask hard questions.
</skill>`;

function userEntry(content: TranscriptEntry["content"]): TranscriptEntry {
  return {
    entryId: "e1",
    parentId: null,
    timestamp: "",
    role: "user",
    content,
  };
}

describe("skill invocation", () => {
  it("peels the first skill XML and leftover prompt", () => {
    const blocks = peelPiSkillInvocation(`${SKILL_XML}\n\non the preferred approach`);
    assert.deepEqual(
      blocks.map((block) => block.type),
      ["skill", "text"],
    );
    const skill = blocks[0] as { name: string; text: string };
    assert.equal(skill.name, "grill-me");
    assert.match(skill.text, /Ask hard questions/);
    assert.equal((blocks[1] as { text: string }).text, "on the preferred approach");
  });

  it("drops later skill tags", () => {
    const second = `<skill name="other">nope</skill>`;
    const blocks = peelPiSkillInvocation(`${SKILL_XML}\n\n${second}\n\nstill here`);
    assert.deepEqual(
      blocks.map((block) => block.type),
      ["skill", "text"],
    );
    assert.equal((blocks[0] as { name: string }).name, "grill-me");
    assert.equal((blocks[1] as { text: string }).text, "still here");
  });

  it("lifts a leading /skill:name with empty body", () => {
    const blocks = peelPiSkillInvocation("/skill:grill-me on the preferred approach");
    assert.deepEqual(blocks, [
      { type: "skill", name: "grill-me", text: "", command: "/skill:grill-me" },
      { type: "text", text: "on the preferred approach" },
    ]);
  });

  it("leaves ordinary user text alone", () => {
    assert.deepEqual(peelPiSkillInvocation("just a prompt"), [
      { type: "text", text: "just a prompt" },
    ]);
  });

  it("entryText uses the skill preview, not the injected body", () => {
    const text = entryText(
      userEntry([
        { type: "skill", name: "grill-me", text: "# Grill me\nAsk hard questions." },
        { type: "text", text: "on the preferred approach" },
      ]),
    );
    assert.equal(text, "[skill: grill-me] on the preferred approach");
    assert.doesNotMatch(text, /Ask hard questions/);
    assert.equal(skillInvocationPreview("grill-me"), "[skill: grill-me]");
  });

  it("does not collapse unpeeled XML in text blocks", () => {
    const text = entryText(userEntry([{ type: "text", text: `${SKILL_XML}\n\nargs` }]));
    assert.match(text, /<skill /);
  });

  it("expandSkillInvocationContent peels a user text block", () => {
    const expanded = expandSkillInvocationContent([
      { type: "text", text: `${SKILL_XML}\n\non the preferred approach` },
    ]);
    assert.equal(expanded[0]?.type, "skill");
    assert.equal(expanded[1]?.type, "text");
  });

  it("parsePiTranscript turns injected skill XML into a skill block", () => {
    const line = JSON.stringify({
      type: "message",
      id: "u1",
      parentId: null,
      timestamp: "2026-08-19T00:00:00Z",
      message: {
        role: "user",
        content: [{ type: "text", text: `${SKILL_XML}\n\non the preferred approach` }],
      },
    });
    const transcript = parsePiTranscript(line);
    const kinds = transcript.entries[0]?.content.map((block) => block.type);
    assert.deepEqual(kinds, ["skill", "text"]);
    assert.equal(entryText(transcript.entries[0]!), "[skill: grill-me] on the preferred approach");
  });

  it("peels a Claude slash command into a skill block", () => {
    const blocks = peelClaudeCommandInvocation(
      "<command-message>writing-for-agents</command-message>\n" +
        "<command-name>/writing-for-agents</command-name>\n" +
        "<command-args>can we write a UI design skill?</command-args>",
    );
    assert.deepEqual(blocks, [
      { type: "skill", name: "writing-for-agents", text: "", command: "/writing-for-agents" },
      { type: "text", text: "can we write a UI design skill?" },
    ]);
  });

  it("peels a Claude command with no args and indented tags", () => {
    const blocks = peelClaudeCommandInvocation(
      "<command-name>/usage</command-name>\n            <command-message>usage</command-message>\n            <command-args></command-args>",
    );
    assert.deepEqual(blocks, [
      { type: "skill", name: "usage", text: "", command: "/usage" },
    ]);
  });

  it("keeps a custom command's contents as the chip body", () => {
    const blocks = peelClaudeCommandInvocation(
      "<command-name>/review</command-name>\n<command-contents>Review the diff.</command-contents>\n<command-args>the bridge</command-args>",
    );
    assert.equal((blocks?.[0] as { text: string }).text, "Review the diff.");
  });

  it("keeps text the CLI appended outside the command tags", () => {
    const blocks = peelClaudeCommandInvocation(
      "<command-name>/review</command-name>\n<command-args>the bridge</command-args>\n[Image #1]",
    );
    assert.equal((blocks?.[1] as { text: string }).text, "the bridge\n[Image #1]");
  });

  it("leaves an ordinary Claude prompt alone", () => {
    assert.equal(peelClaudeCommandInvocation("just a prompt"), null);
  });

  it("parseClaudeTranscript turns a slash command into a skill block", () => {
    const line = JSON.stringify({
      type: "user",
      uuid: "u1",
      parentUuid: null,
      timestamp: "2026-08-19T00:00:00Z",
      sessionId: "s1",
      cwd: "/repo",
      message: {
        role: "user",
        content:
          "<command-message>grill-me</command-message>\n<command-name>/grill-me</command-name>\n<command-args>on the preferred approach</command-args>",
      },
    });
    const transcript = parseClaudeTranscript(line);
    const kinds = transcript.entries[0]?.content.map((block) => block.type);
    assert.deepEqual(kinds, ["skill", "text"]);
    assert.equal(entryText(transcript.entries[0]!), "[skill: grill-me] on the preferred approach");
    assert.equal(transcript.preview, "[skill: grill-me] on the preferred approach");
  });

  it("parsePiTranscript lifts an unexpanded /skill:name", () => {
    const line = JSON.stringify({
      type: "message",
      id: "u2",
      parentId: null,
      timestamp: "2026-08-19T00:00:00Z",
      message: {
        role: "user",
        content: [{ type: "text", text: "/skill:missing please" }],
      },
    });
    const transcript = parsePiTranscript(line);
    const [skill, leftover] = transcript.entries[0]!.content;
    assert.equal(skill?.type, "skill");
    assert.equal((skill as { name: string }).name, "missing");
    assert.equal((skill as { text: string }).text, "");
    assert.equal((leftover as { text: string }).text, "please");
  });
});
