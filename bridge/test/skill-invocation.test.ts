import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  expandSkillInvocationContent,
  peelPiSkillInvocation,
  skillInvocationPreview,
} from "../src/skill-invocation.js";
import { entryText, type TranscriptEntry } from "../src/transcript.js";
import { parsePiTranscript } from "../src/agents/pi/transcript.js";

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
      { type: "skill", name: "grill-me", text: "" },
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
