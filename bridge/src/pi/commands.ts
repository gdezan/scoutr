import { existsSync, readFileSync, readdirSync, realpathSync, statSync } from "node:fs";
import { basename, dirname, isAbsolute, join, resolve } from "node:path";

export interface PiCommandInfo {
  name: string;
  description: string;
  source: "builtin" | "skill";
  argumentHint?: string;
}

export interface CommandsCatalog {
  commands: PiCommandInfo[];
}

const BUILTIN_COMMANDS: PiCommandInfo[] = [
  { name: "settings", description: "Open settings menu", source: "builtin" },
  { name: "model", description: "Select model (opens selector UI)", source: "builtin", argumentHint: "<provider/model>" },
  { name: "scoped-models", description: "Enable/disable models for Ctrl+P cycling", source: "builtin" },
  { name: "export", description: "Export session (HTML default, or specify path: .html/.jsonl)", source: "builtin" },
  { name: "import", description: "Import and resume a session from a JSONL file", source: "builtin" },
  { name: "share", description: "Share session as a secret GitHub gist", source: "builtin" },
  { name: "copy", description: "Copy last agent message to clipboard", source: "builtin" },
  { name: "name", description: "Set session display name", source: "builtin" },
  { name: "session", description: "Show session info and stats", source: "builtin" },
  { name: "changelog", description: "Show changelog entries", source: "builtin" },
  { name: "hotkeys", description: "Show all keyboard shortcuts", source: "builtin" },
  { name: "fork", description: "Create a new fork from a previous user message", source: "builtin" },
  { name: "clone", description: "Duplicate the current session at the current position", source: "builtin" },
  { name: "tree", description: "Navigate session tree (switch branches)", source: "builtin" },
  { name: "trust", description: "Save project trust decision for future sessions", source: "builtin" },
  { name: "login", description: "Configure provider authentication", source: "builtin", argumentHint: "<provider>" },
  { name: "logout", description: "Remove provider authentication", source: "builtin" },
  { name: "new", description: "Start a new session", source: "builtin" },
  { name: "compact", description: "Manually compact the session context", source: "builtin" },
  { name: "resume", description: "Resume a different session", source: "builtin" },
  { name: "reload", description: "Reload keybindings, extensions, skills, prompts, themes, and context files", source: "builtin" },
  { name: "quit", description: "Quit pi", source: "builtin" },
];

interface PiSettings {
  enableSkillCommands?: boolean;
  packages?: unknown;
}

interface SkillInfo {
  name: string;
  description: string;
}

/** Read the commands pi exposes without starting another pi process. */
export function readCommandsCatalog(cwd?: string, piAgentDir = process.env.PI_CODING_AGENT_DIR ?? "~/.pi/agent"): CommandsCatalog {
  const agentDir = expandHome(piAgentDir);
  const settings = readJson(join(agentDir, "settings.json")) as PiSettings | null;
  if (settings?.enableSkillCommands === false) return { commands: [...BUILTIN_COMMANDS] };

  const roots = [join(agentDir, "skills")];
  if (cwd) roots.push(join(cwd, ".pi", "skills"));
  if (Array.isArray(settings?.packages)) {
    for (const spec of settings.packages) {
      if (typeof spec !== "string") continue;
      const packageRoot = resolvePackageRoot(spec, agentDir);
      if (!packageRoot) continue;
      const manifest = readJson(join(packageRoot, "package.json")) as { pi?: { skills?: unknown } } | null;
      const skillPaths = manifest?.pi?.skills;
      if (!Array.isArray(skillPaths)) continue;
      for (const path of skillPaths) {
        if (typeof path === "string") roots.push(resolve(packageRoot, path));
      }
    }
  }

  const skills = new Map<string, SkillInfo>();
  for (const root of roots) {
    for (const file of findSkillFiles(root)) {
      const skill = readSkill(file);
      if (skill && !skills.has(skill.name)) skills.set(skill.name, skill);
    }
  }
  const skillCommands = [...skills.values()]
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((skill): PiCommandInfo => ({
      name: `skill:${skill.name}`,
      description: skill.description,
      source: "skill",
      argumentHint: "<request>",
    }));
  return { commands: [...BUILTIN_COMMANDS, ...skillCommands] };
}

export function validateSlashCommand(text: unknown): string {
  if (typeof text !== "string") throw new Error("slash command text must be a string");
  if (text.length === 0 || text.length > 10_000) throw new Error("slash command text must be 1 to 10000 characters");
  if (!text.startsWith("/") || !/^\/[a-z0-9][a-z0-9:-]*(?:[ \t][^\r\n\u0000-\u001f\u007f]*)?$/i.test(text)) {
    throw new Error("invalid slash command text");
  }
  return text;
}

function findSkillFiles(root: string): string[] {
  const files: string[] = [];
  const seen = new Set<string>();

  function walk(path: string, includeRootFiles: boolean): void {
    if (files.length >= 500 || !existsSync(path)) return;
    let stats;
    try {
      stats = statSync(path);
    } catch {
      return;
    }
    if (stats.isFile()) {
      if (path.endsWith(".md")) files.push(path);
      return;
    }
    if (!stats.isDirectory()) return;

    let real: string;
    try {
      real = realpathSync(path);
    } catch {
      return;
    }
    if (seen.has(real)) return;
    seen.add(real);

    let entries;
    try {
      entries = readdirSync(path, { withFileTypes: true });
    } catch {
      return;
    }
    const rootSkill = entries.find((entry) => entry.name === "SKILL.md");
    if (rootSkill) {
      files.push(join(path, rootSkill.name));
      return;
    }
    for (const entry of entries) {
      if (entry.name.startsWith(".") || entry.name === "node_modules") continue;
      const child = join(path, entry.name);
      if (entry.isDirectory() || entry.isSymbolicLink()) walk(child, false);
      else if (includeRootFiles && entry.isFile() && entry.name.endsWith(".md")) files.push(child);
      if (files.length >= 500) return;
    }
  }

  walk(root, true);
  return files;
}

function readSkill(path: string): SkillInfo | null {
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch {
    return null;
  }
  const lines = text.split(/\r?\n/);
  if (lines[0]?.trim() !== "---") return null;
  const end = lines.slice(1).findIndex((line) => line.trim() === "---");
  if (end < 0) return null;
  const frontmatter = lines.slice(1, end + 1);
  const name = readFrontmatterValue(frontmatter, "name") || basename(dirname(path));
  const description = readFrontmatterValue(frontmatter, "description").replace(/\s+/g, " ").trim();
  if (!/^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/.test(name) || !description) return null;
  return { name, description: description.slice(0, 1024) };
}

function readFrontmatterValue(lines: string[], key: string): string {
  const index = lines.findIndex((line) => line.startsWith(`${key}:`));
  if (index < 0) return "";
  const raw = lines[index]!.slice(key.length + 1).trim();
  if (raw.startsWith(">") || raw.startsWith("|")) {
    const parts: string[] = [];
    for (let line = index + 1; line < lines.length; line++) {
      const value = lines[line]!;
      if (value && !/^\s/.test(value)) break;
      parts.push(value.trim());
    }
    return parts.join(" ").trim();
  }
  if (raw.startsWith('"') && raw.endsWith('"')) {
    try {
      return JSON.parse(raw) as string;
    } catch {
      return raw.slice(1, -1);
    }
  }
  if (raw.startsWith("'") && raw.endsWith("'")) return raw.slice(1, -1).replace(/''/g, "'");
  return raw;
}

function resolvePackageRoot(spec: string, agentDir: string): string | null {
  if (spec.startsWith("npm:")) {
    const name = npmPackageName(spec.slice(4));
    return name ? join(agentDir, "npm", "node_modules", ...name.split("/")) : null;
  }
  if (spec.startsWith("git:")) {
    const match = spec.match(/github\.com[:/]([^/]+)\/([^/#]+?)(?:\.git)?(?:[#/].*)?$/);
    return match ? join(agentDir, "git", "github.com", match[1]!, match[2]!) : null;
  }
  return isAbsolute(spec) ? spec : resolve(agentDir, spec);
}

function npmPackageName(spec: string): string {
  if (spec.startsWith("@")) {
    const version = spec.indexOf("@", 1);
    return version < 0 ? spec : spec.slice(0, version);
  }
  return spec.split("@", 1)[0] ?? "";
}

function readJson(path: string): unknown | null {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return null;
  }
}

function expandHome(path: string): string {
  if (path === "~") return process.env.HOME ?? path;
  if (path.startsWith("~/")) return join(process.env.HOME ?? "", path.slice(2));
  return path;
}
