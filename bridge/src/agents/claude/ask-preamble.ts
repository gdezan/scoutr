/**
 * The prose Claude wrote just before an open ask, read off the pane.
 *
 * Claude buffers the whole assistant turn — thinking, the prose that
 * introduces an ask, and the `AskUserQuestion` call itself — and writes all of
 * it to the session JSONL only when the questionnaire resolves. Verified live
 * against 2.1.241: with a card on screen for 14s the transcript file did not
 * grow by a byte, then the entire turn landed in one write the moment the ask
 * was answered. The hook payload carries no assistant text either, and the
 * docs say `transcript_path` "may lag behind the current turn's messages".
 *
 * So while the card is up, that background exists in exactly one place: the
 * rendered pane. This module owns reading it back out of the TUI's own
 * layout — the adapter's grammar, the same way `questionnaire.ts` owns the
 * key sequence (ADR 0006, ADR 0012).
 *
 * The layout being parsed, from `herdr agent read --source visible`:
 *
 *     ● I dug into this before asking, so here's what I found.
 *
 *       The card itself isn't hiding anything — ChatList renders AskCard
 *       as an ordinary row, so any assistant text above it would show.
 *     ────────────────────────────────────────────────────────────────
 *      ☐ Source
 *     │ Should the bridge scrape it?
 *     ❯ 1. Scrape the pane once per ask
 */

/** Bullet Claude prefixes every assistant turn and tool call with. */
const BULLET = "●";

/** Tool output hangs under its call on this glyph; prose never carries one. */
const TOOL_OUTPUT_GLYPH = "⎿";

/** The questionnaire box opens on a full-width rule; prose never draws one. */
const BOX_RULE = /^\s*[─━]{8,}\s*$/;

/** `Bash(npm test)`, `mcp__chrome__navigate(…)` — a call bullet, not prose. */
const TOOL_CALL_HEAD = /^[A-Za-z_][A-Za-z0-9_]*\(/;

/** A wrapped paragraph rejoins into one line; these keep their own. */
const STANDALONE_LINE = /^(?:[-*+•>#]|\d+[.)])\s/;

/** Enough background for any ask, without shipping a screenful of scrollback. */
export const MAX_ASK_PREAMBLE_LENGTH = 4000;

/**
 * The preamble in a visible-pane snapshot, or "" when the pane does not
 * clearly hold one.
 *
 * Refusing beats guessing here: the text goes on screen as if the agent had
 * said it, so a block that cannot be identified as the prose immediately
 * above the questionnaire is dropped rather than approximated. A preamble
 * whose bullet has already scrolled off the top of the pane is one of those —
 * the card simply carries no background, which is what happens today anyway.
 */
export function extractAskPreamble(paneText: string): string {
  const lines = paneText.replace(/\r/g, "").split("\n");
  const start = lastIndex(lines, (line) => line.trimStart().startsWith(BULLET));
  if (start === -1) return "";
  // The block runs from its bullet to the questionnaire box. A bullet with no
  // box under it is not the ask's preamble — it is ordinary scrollback from
  // a pane whose questionnaire is not on screen.
  const boxAt = lines.findIndex((line, at) => at > start && BOX_RULE.test(line));
  if (boxAt === -1) return "";
  const block = lines.slice(start, boxAt);
  const head = stripBullet(block[0] ?? "");
  if (!head || TOOL_CALL_HEAD.test(head)) return ""; // `Bash(…)`, `Read(…)`: a call, not prose
  if (block.some((line) => line.includes(TOOL_OUTPUT_GLYPH))) return ""; // a result, or the ask's own echo
  return unwrap([head, ...block.slice(1).map(stripIndent)]).slice(0, MAX_ASK_PREAMBLE_LENGTH);
}

function lastIndex(lines: string[], match: (line: string) => boolean): number {
  for (let at = lines.length - 1; at >= 0; at -= 1) {
    if (match(lines[at] ?? "")) return at;
  }
  return -1;
}

function stripBullet(line: string): string {
  return line.trimStart().slice(BULLET.length).trim();
}

/** Continuation lines sit under the bullet at a fixed two-space indent. */
function stripIndent(line: string): string {
  return line.startsWith("  ") ? line.slice(2).trimEnd() : line.trimEnd();
}

/**
 * Rejoin what the pane hard-wrapped at its own width.
 *
 * `--source visible` is the only snapshot herdr will take of a pane that is
 * not idle, and it comes back wrapped to the terminal's columns — rendered
 * straight onto a phone that reads as a ragged column. Blank lines keep
 * paragraphs apart, list items and headings keep their own line, and
 * everything else in a paragraph becomes one line for the app to re-wrap.
 */
function unwrap(lines: string[]): string {
  const out: string[] = [];
  let paragraph: string[] = [];
  const flush = () => {
    if (paragraph.length > 0) out.push(paragraph.join(" "));
    paragraph = [];
  };
  for (const line of lines) {
    const text = line.trim();
    if (!text) {
      flush();
      if (out[out.length - 1] !== "") out.push("");
      continue;
    }
    // A list item opens its own paragraph; the wrapped remainder of it rejoins
    // like any other continuation line.
    if (STANDALONE_LINE.test(text)) flush();
    paragraph.push(text);
  }
  flush();
  return out.join("\n").trim();
}
