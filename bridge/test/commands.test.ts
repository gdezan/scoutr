import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, it } from "node:test";
import { readCommandsCatalog, validateSlashCommand } from "../src/pi/commands.js";

const roots: string[] = [];

afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

async function fixture(): Promise<{ agentDir: string; cwd: string }> {
  const root = await mkdtemp(join(tmpdir(), "cockpit-commands-"));
  roots.push(root);
  const agentDir = join(root, "agent");
  const cwd = join(root, "project");
  await mkdir(join(agentDir, "skills", "global"), { recursive: true });
  await mkdir(join(cwd, ".pi", "skills", "project"), { recursive: true });
  await mkdir(join(agentDir, "npm", "node_modules", "skill-pack", "skills", "package"), { recursive: true });
  await writeFile(join(agentDir, "settings.json"), JSON.stringify({ packages: ["npm:skill-pack"] }));
  await writeFile(
    join(agentDir, "npm", "node_modules", "skill-pack", "package.json"),
    JSON.stringify({ pi: { skills: ["skills"] } }),
  );
  await writeFile(
    join(agentDir, "skills", "global", "SKILL.md"),
    "---\nname: global-skill\ndescription: A global skill\n---\n",
  );
  await writeFile(
    join(cwd, ".pi", "skills", "project", "SKILL.md"),
    "---\nname: project-skill\ndescription: >\n  A project skill\n  over two lines\n---\n",
  );
  await writeFile(
    join(agentDir, "npm", "node_modules", "skill-pack", "skills", "package", "SKILL.md"),
    "---\nname: package-skill\ndescription: A package skill\n---\n",
  );
  return { agentDir, cwd };
}

describe("pi command catalog", () => {
  it("lists built-ins and installed user, project, and package skills", async () => {
    const { agentDir, cwd } = await fixture();

    const catalog = readCommandsCatalog(cwd, agentDir);

    assert.equal(catalog.commands[0]?.name, "settings");
    assert.deepEqual(
      catalog.commands.filter((command) => command.source === "skill").map((command) => command.name),
      ["skill:global-skill", "skill:package-skill", "skill:project-skill"],
    );
    assert.equal(
      catalog.commands.find((command) => command.name === "skill:project-skill")?.description,
      "A project skill over two lines",
    );
  });

  it("honors the skill-command setting", async () => {
    const { agentDir, cwd } = await fixture();
    await writeFile(join(agentDir, "settings.json"), JSON.stringify({ enableSkillCommands: false }));

    const catalog = readCommandsCatalog(cwd, agentDir);

    assert.equal(catalog.commands.some((command) => command.source === "skill"), false);
    assert.equal(catalog.commands.some((command) => command.name === "compact"), true);
  });
});

describe("slash command validation", () => {
  it("keeps safe command text exact", () => {
    assert.equal(validateSlashCommand("/model openai-codex/gpt-5.4"), "/model openai-codex/gpt-5.4");
    assert.equal(validateSlashCommand("/skill:research compare APIs"), "/skill:research compare APIs");
  });

  it("rejects text that could submit extra terminal input", () => {
    assert.throws(() => validateSlashCommand("compact"), /invalid/);
    assert.throws(() => validateSlashCommand("/compact\n/quit"), /invalid/);
    assert.throws(() => validateSlashCommand("/compact\u001b"), /invalid/);
  });
});
