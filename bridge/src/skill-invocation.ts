import type { ContentBlock, SkillBlock, TextBlock } from "./transcript.js";

/**
 * Harnesses rewrite an invoked skill or slash command into markup on the user
 * turn — pi injects `<skill name="…">…</skill>`, Claude Code injects
 * `<command-name>/…</command-name>` and friends. Chat and previews should see a
 * `skill` block, not that dump. Each agent adapter calls its own peel.
 */

const FIRST_SKILL_RE = /<skill\b([^>]*)>([\s\S]*?)<\/skill>/i;
const SKILL_NAME_ATTR_RE = /\bname\s*=\s*"([^"]*)"/i;
const LATER_SKILL_RE = /<skill\b[^>]*>[\s\S]*?<\/skill>/gi;

const COMMAND_NAME_RE = /<command-name>([\s\S]*?)<\/command-name>/i;
const COMMAND_ARGS_RE = /<command-args>([\s\S]*?)<\/command-args>/i;
/** Custom commands ship their markdown body here; skills leave it out. */
const COMMAND_CONTENTS_RE = /<command-contents>([\s\S]*?)<\/command-contents>/i;
const COMMAND_TAG_RE = /<command-[a-z]+>[\s\S]*?<\/command-[a-z]+>/gi;

/** One-line preview of a named skill, used by entryText and board activity. */
export function skillInvocationPreview(name: string): string {
  return `[skill: ${name}]`;
}

/**
 * Peel the first Pi skill injection (or a leading `/skill:name`) out of a
 * user-turn string. Later `<skill>` tags are dropped. Ordinary text is
 * unchanged.
 */
export function peelPiSkillInvocation(text: string): ContentBlock[] {
  const xml = peelFirstSkillXml(text);
  if (xml) return xml;
  const slash = peelLeadingSlashSkill(text);
  if (slash) return slash;
  return [{ type: "text", text } satisfies TextBlock];
}

/**
 * Peel Claude Code's slash-command expansion out of a user-turn string.
 * Claude wraps every command this way, skill or built-in, so the chip stands
 * for whatever the user typed. Returns null for an ordinary prompt.
 */
export function peelClaudeCommandInvocation(text: string): ContentBlock[] | null {
  const rawName = COMMAND_NAME_RE.exec(text)?.[1]?.trim();
  if (!rawName) return null;
  const name = rawName.replace(/^\/+/, "").trim();
  if (!name) return null;
  const body = COMMAND_CONTENTS_RE.exec(text)?.[1]?.trim() ?? "";
  const skill: SkillBlock = { type: "skill", name, text: body, command: `/${name}` };
  const args = COMMAND_ARGS_RE.exec(text)?.[1]?.trim() ?? "";
  // Anything the CLI appended outside the tags (attachment markers, notes)
  // belongs to the prompt, not to the chip.
  const residue = text.replace(COMMAND_TAG_RE, "").trim();
  const leftover = [args, residue].filter(Boolean).join("\n");
  const blocks: ContentBlock[] = [skill];
  if (leftover) blocks.push({ type: "text", text: leftover } satisfies TextBlock);
  return blocks;
}

/** Rewrite Pi user content so the first injected skill is its own block. */
export function expandSkillInvocationContent(content: ContentBlock[]): ContentBlock[] {
  const expanded: ContentBlock[] = [];
  let tookSkill = false;
  for (const block of content) {
    if (block.type === "text" && "text" in block) {
      const pieces = peelPiSkillInvocation(String(block.text));
      const skill = pieces.find((piece) => piece.type === "skill");
      if (skill && !tookSkill) {
        tookSkill = true;
        expanded.push(...pieces);
        continue;
      }
      if (skill && tookSkill) {
        expanded.push(...pieces.filter((piece) => piece.type !== "skill"));
        continue;
      }
    }
    if (block.type === "skill") {
      if (tookSkill) continue;
      tookSkill = true;
    }
    expanded.push(block);
  }
  return expanded;
}

function peelFirstSkillXml(text: string): ContentBlock[] | null {
  const match = FIRST_SKILL_RE.exec(text);
  if (!match) return null;
  const name = match[1]?.match(SKILL_NAME_ATTR_RE)?.[1]?.trim();
  if (!name) return null;
  const skill: SkillBlock = { type: "skill", name, text: (match[2] ?? "").trim(), command: `/skill:${name}` };
  const before = text.slice(0, match.index).trim();
  const after = dropLaterSkillTags(text.slice(match.index + match[0].length).trim());
  const leftover = [before, after].filter(Boolean).join("\n");
  const blocks: ContentBlock[] = [skill];
  if (leftover) blocks.push({ type: "text", text: leftover } satisfies TextBlock);
  return blocks;
}

function peelLeadingSlashSkill(text: string): ContentBlock[] | null {
  const trimmed = text.trim();
  if (!trimmed.startsWith("/skill:")) return null;
  const rest = trimmed.slice("/skill:".length);
  const space = rest.search(/\s/);
  const name = (space === -1 ? rest : rest.slice(0, space)).trim();
  if (!name) return null;
  const args = space === -1 ? "" : rest.slice(space).trim();
  const blocks: ContentBlock[] = [
    { type: "skill", name, text: "", command: `/skill:${name}` } satisfies SkillBlock,
  ];
  if (args) blocks.push({ type: "text", text: args } satisfies TextBlock);
  return blocks;
}

function dropLaterSkillTags(text: string): string {
  return text.replace(LATER_SKILL_RE, "").replace(/\n{3,}/g, "\n\n").trim();
}
