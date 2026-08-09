import { test } from "node:test";
import assert from "node:assert/strict";
import { parsePiSession, entryText } from "../src/pi/session.js";

const SAMPLE = `{"type":"session","version":3,"id":"abc123","timestamp":"2026-08-09T16:39:48.826Z","cwd":"/home/gdezan/Dev/x"}
{"type":"model_change","id":"m1","parentId":null,"timestamp":"2026-08-09T16:39:50Z","provider":"opencode-go","modelId":"deepseek-v4-flash"}
{"type":"thinking_level_change","id":"t1","parentId":"m1","timestamp":"2026-08-09T16:39:50Z","thinkingLevel":"high"}
{"type":"custom","customType":"plannotator","data":{"phase":"idle"},"id":"c1","parentId":"t1","timestamp":"2026-08-09T16:39:51Z"}
{"type":"message","id":"e1","parentId":"c1","timestamp":"2026-08-09T16:39:52Z","message":{"role":"user","content":[{"type":"text","text":"hello"}]}}
{"type":"message","id":"e2","parentId":"e1","timestamp":"2026-08-09T16:39:53Z","message":{"role":"assistant","content":[{"type":"thinking","thinking":"plan"},{"type":"toolCall","id":"call_1","name":"bash","arguments":{"command":"ls"}},{"type":"text","text":"on it"}],"usage":{"input":100,"output":20,"cost":{"input":0.01}},"stopReason":"toolUse","model":"deepseek-v4-flash"}}
{"type":"message","id":"e3","parentId":"e2","timestamp":"2026-08-09T16:39:54Z","message":{"role":"toolResult","toolCallId":"call_1","toolName":"bash","isError":false,"content":[{"type":"text","text":"a.txt"}]}}
`;

test("parses a version-3 pi session file", () => {
  const session = parsePiSession(SAMPLE);
  assert.equal(session.id, "abc123");
  assert.equal(session.cwd, "/home/gdezan/Dev/x");
  assert.equal(session.lastEntryId, "e3");
  assert.equal(session.entries.length, 3);
});

test("extracts user text and assistant blocks", () => {
  const session = parsePiSession(SAMPLE);
  const [user, assistant, toolResult] = session.entries;
  assert.equal(user.role, "user");
  assert.equal((user.content[0] as { text: string }).text, "hello");
  assert.equal(assistant.role, "assistant");
  const kinds = assistant.content.map((block) => block.type);
  assert.deepEqual(kinds, ["thinking", "toolCall", "text"]);
  const call = assistant.content[1] as { name: string; arguments: unknown };
  assert.equal(call.name, "bash");
  assert.equal(assistant.usage?.input, 100);
  assert.equal(assistant.stopReason, "toolUse");
  assert.equal(toolResult.toolName, "bash");
  assert.equal(toolResult.toolCallId, "call_1");
});

test("entryText skips thinking and truncates", () => {
  const session = parsePiSession(SAMPLE);
  const assistant = session.entries[1];
  const text = entryText(assistant, 40);
  assert.ok(text.startsWith("[bash] on it"));
});

test("string content is normalized to a text block", () => {
  const session = parsePiSession(
    `{"type":"message","id":"s1","parentId":null,"timestamp":"2026-08-09T00:00:00Z","message":{"role":"user","content":"plain"}}`,
  );
  assert.equal((session.entries[0].content[0] as { text: string }).text, "plain");
});

test("tolerates garbage lines in a growing file", () => {
  const session = parsePiSession(`${SAMPLE}\nnot json at all\n{"type":"message","id":"e9",`);
  assert.equal(session.entries.length, 3);
});
