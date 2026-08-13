import { readdir, readFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import type { CommandInfo, CommandsCatalog } from "../types.js";

const BUILTIN_AGY_COMMANDS: CommandInfo[] = [
  {
    name: "goal",
    description: "Run a long-running task thoroughly until the goal is fully achieved",
    source: "builtin",
    argumentHint: "<instruction>",
  },
  {
    name: "plan",
    description: "Create a step-by-step implementation plan before execution",
    source: "builtin",
    argumentHint: "<task>",
  },
  {
    name: "schedule",
    description: "Schedule a one-shot timer or recurring cron job",
    source: "builtin",
    argumentHint: "<instruction>",
  },
  {
    name: "grill-me",
    description: "Interactive interview to resolve design decisions",
    source: "builtin",
  },
  {
    name: "learn",
    description: "Persist corrections or setup for future tasks",
    source: "builtin",
  },
  {
    name: "agy-customizations",
    description: "Antigravity Customization System guide",
    source: "builtin",
  },
  {
    name: "help",
    description: "Show help and available commands",
    source: "builtin",
  },
  {
    name: "model",
    description: "Select or switch the active model",
    source: "builtin",
    argumentHint: "<model>",
  },
  {
    name: "effort",
    description: "Set reasoning effort (low|medium|high)",
    source: "builtin",
    argumentHint: "<level>",
  },
  {
    name: "compact",
    description: "Manually compact the session context",
    source: "builtin",
  },
];

export async function readAgyCommandsCatalog(cwd?: string): Promise<CommandsCatalog> {
  const commands: CommandInfo[] = [...BUILTIN_AGY_COMMANDS];
  const seen = new Set(commands.map((c) => c.name));

  const skillDirs: string[] = [];
  if (cwd) {
    skillDirs.push(
      resolve(cwd, "skills"),
      resolve(cwd, ".agents", "skills"),
      resolve(cwd, ".agent", "skills"),
    );
  }

  const globalDir = process.env.ANTIGRAVITY_CONFIG_DIR || join(process.env.HOME ?? "", ".gemini", "antigravity-cli");
  skillDirs.push(
    join(globalDir, "skills"),
    join(globalDir, "builtin", "skills"),
  );

  for (const dir of skillDirs) {
    try {
      const entries = await readdir(dir, { withFileTypes: true });
      for (const entry of entries) {
        if (!entry.isDirectory()) continue;
        const skillPath = join(dir, entry.name, "SKILL.md");
        const meta = await parseSkillFile(skillPath);
        if (meta && !seen.has(meta.name)) {
          seen.add(meta.name);
          commands.push({
            name: meta.name,
            description: meta.description,
            source: "skill",
          });
        }
      }
    } catch {
      // Missing or unreadable directory is skipped
    }
  }

  return { commands };
}

interface SkillMetadata {
  name: string;
  description: string;
}

async function parseSkillFile(path: string): Promise<SkillMetadata | null> {
  try {
    const text = await readFile(path, "utf8");
    const fmMatch = text.match(/^---\r?\n([\s\S]*?)\r?\n---/);
    if (!fmMatch || !fmMatch[1]) return null;

    const frontmatter = fmMatch[1];
    const nameMatch = frontmatter.match(/^name:\s*(.+)$/m);
    const descMatch = frontmatter.match(/^description:\s*(.+)$/m);

    if (!nameMatch || !nameMatch[1]) return null;
    const name = nameMatch[1].trim();
    const description = descMatch && descMatch[1] ? descMatch[1].trim() : "Custom Antigravity skill";

    return { name, description };
  } catch {
    return null;
  }
}
