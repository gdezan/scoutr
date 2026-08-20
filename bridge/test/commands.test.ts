import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, it } from "node:test";
import { readCommandsCatalog } from "../src/agents/pi/commands.js";
import { validateSlashCommand } from "../src/commands.js";

const roots: string[] = [];

afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

async function fixture(): Promise<{ agentDir: string; cwd: string }> {
  const root = await mkdtemp(join(tmpdir(), "scoutr-commands-"));
  roots.push(root);
  const agentDir = join(root, "agent");
  const cwd = join(root, "project");
  await mkdir(join(agentDir, "skills", "global"), { recursive: true });
  await mkdir(join(agentDir, "skills", "duplicate"), { recursive: true });
  await mkdir(join(cwd, ".pi", "skills", "project"), { recursive: true });
  await mkdir(join(cwd, ".pi", "skills", "duplicate"), { recursive: true });
  await mkdir(join(cwd, ".pi", "skills", "ignored"), { recursive: true });
  await mkdir(join(cwd, ".pi", "explicit-skills", "duplicate"), { recursive: true });
  await mkdir(join(cwd, ".pi", "prompts"), { recursive: true });
  await mkdir(join(cwd, ".pi", "extensions"), { recursive: true });
  await mkdir(join(cwd, ".agents", "skills", "agents-project"), { recursive: true });
  await mkdir(join(cwd, ".git"), { recursive: true });
  await mkdir(join(agentDir, "npm", "node_modules", "skill-pack", "skills", "package"), { recursive: true });
  await mkdir(join(agentDir, "npm", "node_modules", "object-pack", "skills", "object"), { recursive: true });
  await mkdir(join(agentDir, "npm", "node_modules", "glob-pack", "skills", "kept"), { recursive: true });
  await mkdir(join(agentDir, "npm", "node_modules", "glob-pack", "skills", "excluded"), { recursive: true });
  await mkdir(join(agentDir, "npm", "node_modules", "filtered-pack", "skills", "kept"), { recursive: true });
  await mkdir(join(agentDir, "npm", "node_modules", "filtered-pack", "skills", "dropped"), { recursive: true });
  await mkdir(join(agentDir, "npm", "node_modules", "authoritative-pack", "skills", "hidden"), { recursive: true });
  await writeFile(
    join(agentDir, "settings.json"),
    JSON.stringify({
      packages: [
        "npm:skill-pack",
        { source: "npm:object-pack", skills: ["skills/object"] },
        "npm:glob-pack",
        { source: "npm:filtered-pack", autoload: false, skills: ["+skills/kept"] },
        "npm:authoritative-pack",
      ],
    }),
  );
  await writeFile(join(agentDir, "trust.json"), JSON.stringify({ [cwd]: true }));
  await writeFile(
    join(agentDir, "npm", "node_modules", "skill-pack", "package.json"),
    JSON.stringify({ name: "skill-pack", version: "1.0.0" }),
  );
  await writeFile(
    join(agentDir, "npm", "node_modules", "object-pack", "package.json"),
    JSON.stringify({ name: "object-pack", version: "1.0.0" }),
  );
  await writeFile(join(cwd, ".pi", "settings.json"), JSON.stringify({ skills: ["explicit-skills"] }));
  await writeFile(join(cwd, ".pi", "skills", ".gitignore"), "ignored/\n");
  await writeFile(
    join(agentDir, "npm", "node_modules", "glob-pack", "package.json"),
    JSON.stringify({ name: "glob-pack", version: "1.0.0", pi: { skills: ["skills/**", "!skills/excluded/**"] } }),
  );
  await writeFile(
    join(agentDir, "npm", "node_modules", "filtered-pack", "package.json"),
    JSON.stringify({ name: "filtered-pack", version: "1.0.0", pi: { skills: ["skills/**"] } }),
  );
  await writeFile(
    join(agentDir, "npm", "node_modules", "authoritative-pack", "package.json"),
    JSON.stringify({ name: "authoritative-pack", version: "1.0.0", pi: { extensions: [] } }),
  );
  await writeFile(
    join(agentDir, "skills", "global", "SKILL.md"),
    "---\nname: global-skill\ndescription: A global skill\n---\n",
  );
  await writeFile(
    join(agentDir, "skills", "duplicate", "SKILL.md"),
    "---\nname: duplicate\ndescription: Global winner\n---\n",
  );
  await writeFile(
    join(cwd, ".pi", "skills", "project", "SKILL.md"),
    "---\nname: project-skill\ndescription: >\n  A project skill\n  over two lines\n---\n",
  );
  await writeFile(
    join(cwd, ".pi", "prompts", "review.md"),
    "---\ndescription: Review the current changes\nargument-hint: <base>\n---\nReview $ARGUMENTS\n",
  );
  await writeFile(
    join(cwd, ".pi", "extensions", "hello.ts"),
    "export default (pi) => pi.registerCommand('hello', { description: 'Say hello', handler: async () => {} });\n",
  );
  await writeFile(
    join(cwd, ".pi", "skills", "duplicate", "SKILL.md"),
    "---\nname: duplicate\ndescription: Project winner\n---\n",
  );
  await writeFile(
    join(cwd, ".pi", "explicit-skills", "duplicate", "SKILL.md"),
    "---\nname: duplicate\ndescription: Explicit project winner\n---\n",
  );
  await writeFile(
    join(cwd, ".pi", "skills", "ignored", "SKILL.md"),
    "---\nname: ignored-project\ndescription: This should stay hidden\n---\n",
  );
  await writeFile(
    join(cwd, ".agents", "skills", "agents-project", "SKILL.md"),
    "---\nname: agents-project\ndescription: An Agent Skills project skill\n---\n",
  );
  await writeFile(
    join(cwd, ".agents", "skills", "ignored-root.md"),
    "---\nname: ignored-root\ndescription: Pi ignores this root file\n---\n",
  );
  await writeFile(
    join(agentDir, "npm", "node_modules", "skill-pack", "skills", "package", "SKILL.md"),
    "---\nname: package-skill\ndescription: A package skill\n---\n",
  );
  await writeFile(
    join(agentDir, "npm", "node_modules", "object-pack", "skills", "object", "SKILL.md"),
    "---\nname: object-package-skill\ndescription: An object package skill\n---\n",
  );
  const packageSkills = [
    ["glob-pack", "kept", "glob-kept"],
    ["glob-pack", "excluded", "glob-excluded"],
    ["filtered-pack", "kept", "filtered-kept"],
    ["filtered-pack", "dropped", "filtered-dropped"],
    ["authoritative-pack", "hidden", "authoritative-hidden"],
  ];
  for (const [pack, directory, name] of packageSkills) {
    await writeFile(
      join(agentDir, "npm", "node_modules", pack!, "skills", directory!, "SKILL.md"),
      `---\nname: ${name}\ndescription: Package fixture ${name}\n---\n`,
    );
  }
  return { agentDir, cwd };
}

describe("pi command catalog", () => {
  it("lists built-ins, prompts, extensions, and installed skills", async () => {
    const { agentDir, cwd } = await fixture();

    const catalog = await readCommandsCatalog(cwd, agentDir);

    assert.equal(catalog.commands[0]?.name, "settings");
    const commandNames = new Set(catalog.commands.map((command) => command.name));
    for (const name of [
      "skill:agents-project",
      "skill:duplicate",
      "skill:filtered-kept",
      "skill:glob-kept",
      "skill:global-skill",
      "skill:object-package-skill",
      "skill:package-skill",
      "skill:project-skill",
    ]) {
      assert.equal(commandNames.has(name), true, name);
    }
    assert.equal(catalog.commands.find((command) => command.name === "review")?.source, "prompt");
    assert.equal(catalog.commands.find((command) => command.name === "review")?.argumentHint, "<base>");
    assert.equal(catalog.commands.find((command) => command.name === "hello")?.source, "extension");
    assert.equal(catalog.commands.find((command) => command.name === "skill:duplicate")?.description, "Explicit project winner");
    assert.equal(
      catalog.commands.find((command) => command.name === "skill:project-skill")?.description,
      "A project skill over two lines\n",
    );
    assert.equal(catalog.commands.some((command) => command.name.includes("excluded")), false);
    assert.equal(catalog.commands.some((command) => command.name.includes("dropped")), false);
    assert.equal(catalog.commands.some((command) => command.name.includes("authoritative")), false);
    assert.equal(catalog.commands.some((command) => command.name === "skill:ignored-project"), false);
    assert.equal(catalog.commands.some((command) => command.name === "debug"), false);
  });

  it("honors the skill-command setting", async () => {
    const { agentDir, cwd } = await fixture();
    await writeFile(join(agentDir, "settings.json"), JSON.stringify({ enableSkillCommands: false }));

    const catalog = await readCommandsCatalog(cwd, agentDir);

    assert.equal(catalog.commands.some((command) => command.source === "skill"), false);
    assert.equal(catalog.commands.some((command) => command.name === "compact"), true);
  });

  it("omits project skills when the project is not trusted", async () => {
    const { agentDir, cwd } = await fixture();
    await writeFile(join(agentDir, "trust.json"), JSON.stringify({ [cwd]: false }));

    const catalog = await readCommandsCatalog(cwd, agentDir);

    assert.equal(catalog.commands.some((command) => command.name === "skill:project-skill"), false);
    assert.equal(catalog.commands.some((command) => command.name === "skill:global-skill"), true);
  });

  it("fails closed when the trust store is malformed", async () => {
    const { agentDir, cwd } = await fixture();
    await writeFile(join(agentDir, "settings.json"), JSON.stringify({ defaultProjectTrust: "always" }));
    await writeFile(join(agentDir, "trust.json"), "not json");

    await assert.rejects(readCommandsCatalog(cwd, agentDir), /Failed to read trust store/);
  });

  it("fails closed when the trust store has invalid decisions", async () => {
    const { agentDir, cwd } = await fixture();
    await writeFile(join(agentDir, "settings.json"), JSON.stringify({ defaultProjectTrust: "always" }));
    await writeFile(join(agentDir, "trust.json"), JSON.stringify({ [cwd]: "yes" }));

    await assert.rejects(readCommandsCatalog(cwd, agentDir), /Invalid trust store/);
  });

  it("fails closed when the trust store is unreadable", async () => {
    const { agentDir, cwd } = await fixture();
    await writeFile(join(agentDir, "settings.json"), JSON.stringify({ defaultProjectTrust: "always" }));
    await rm(join(agentDir, "trust.json"));
    await mkdir(join(agentDir, "trust.json"));

    await assert.rejects(readCommandsCatalog(cwd, agentDir), /Failed to read trust store/);
  });
});

describe("slash command validation", () => {
  it("keeps safe command text exact", () => {
    assert.equal(validateSlashCommand("/model openai-codex/gpt-5.4"), "/model openai-codex/gpt-5.4");
    assert.equal(validateSlashCommand("/skill:research compare APIs"), "/skill:research compare APIs");
    assert.equal(validateSlashCommand("/reload_runtime"), "/reload_runtime");
    assert.equal(validateSlashCommand("/foo.bar"), "/foo.bar");
    assert.equal(validateSlashCommand("/foo/bar"), "/foo/bar");
  });

  it("flattens newlines in arguments into a single PTY line", () => {
    assert.equal(validateSlashCommand("/skill:research\ncompare APIs"), "/skill:research compare APIs");
    assert.equal(validateSlashCommand("/compact\n/quit"), "/compact /quit");
    assert.equal(validateSlashCommand("/skill:research\r\nplease\ndo this"), "/skill:research please do this");
  });

  it("rejects text that could inject terminal control input", () => {
    assert.throws(() => validateSlashCommand("compact"), /invalid/);
    assert.throws(() => validateSlashCommand("/compact\u001b"), /invalid/);
  });
});
