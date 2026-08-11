import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import {
  DefaultResourceLoader,
  ProjectTrustStore,
  SettingsManager,
  type Extension,
  type RegisteredCommand,
} from "@earendil-works/pi-coding-agent";

export interface PiCommandInfo {
  name: string;
  description: string;
  source: "builtin" | "extension" | "prompt" | "skill";
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
  defaultProjectTrust?: "ask" | "always" | "never";
}

const COMMAND_CATALOG_TTL_MS = 30_000;
const commandCatalogCache = new Map<string, { expiresAt: number; catalog: Promise<CommandsCatalog> }>();

/** Read Pi's command catalog for a working directory. */
export async function readCommandsCatalog(
  cwd?: string,
  piAgentDir = process.env.PI_CODING_AGENT_DIR ?? "~/.pi/agent",
): Promise<CommandsCatalog> {
  const agentDir = resolve(expandHome(piAgentDir));
  const resolvedCwd = resolve(cwd ?? process.cwd());
  const globalSettings = readJson(join(agentDir, "settings.json")) as PiSettings | null;
  const projectTrusted = cwd
    ? (new ProjectTrustStore(agentDir).get(resolvedCwd) ?? globalSettings?.defaultProjectTrust === "always")
    : false;
  const cacheKey = `${agentDir}\u0000${resolvedCwd}\u0000${projectTrusted}`;
  const cached = commandCatalogCache.get(cacheKey);
  if (cached && cached.expiresAt > Date.now()) return cached.catalog;

  const catalog = loadCommandsCatalog(resolvedCwd, agentDir, projectTrusted);
  commandCatalogCache.set(cacheKey, { expiresAt: Date.now() + COMMAND_CATALOG_TTL_MS, catalog });
  try {
    return await catalog;
  } catch (error) {
    commandCatalogCache.delete(cacheKey);
    throw error;
  }
}

async function loadCommandsCatalog(
  resolvedCwd: string,
  agentDir: string,
  projectTrusted: boolean,
): Promise<CommandsCatalog> {
  const settingsManager = SettingsManager.create(resolvedCwd, agentDir, { projectTrusted });
  const resourceLoader = new DefaultResourceLoader({
    cwd: resolvedCwd,
    agentDir,
    settingsManager,
    noThemes: true,
    noContextFiles: true,
  });
  await resourceLoader.reload();

  const promptCommands: PiCommandInfo[] = resourceLoader.getPrompts().prompts.map((prompt) => ({
    name: prompt.name,
    description: prompt.description,
    source: "prompt",
    ...(prompt.argumentHint ? { argumentHint: prompt.argumentHint } : {}),
  }));
  const builtinNames = new Set(BUILTIN_COMMANDS.map((command) => command.name));
  const extensionCommands = resolveExtensionCommands(resourceLoader.getExtensions().extensions)
    .filter((command) => !builtinNames.has(command.command.name))
    .map(({ command, invocationName }): PiCommandInfo => ({
      name: invocationName,
      description: command.description ?? "Extension command",
      source: "extension",
    }));
  const skillCommands: PiCommandInfo[] = settingsManager.getEnableSkillCommands()
    ? resourceLoader.getSkills().skills.map((skill) => ({
      name: `skill:${skill.name}`,
      description: skill.description,
      source: "skill",
      argumentHint: "<request>",
    }))
    : [];

  return { commands: [...BUILTIN_COMMANDS, ...promptCommands, ...extensionCommands, ...skillCommands] };
}

export function validateSlashCommand(text: unknown): string {
  if (typeof text !== "string") throw new Error("slash command text must be a string");
  if (text.length === 0 || text.length > 10_000) throw new Error("slash command text must be 1 to 10000 characters");
  if (!text.startsWith("/") || !/^\/[^\s\u0000-\u001f\u007f]+(?:[ \t][^\r\n\u0000-\u001f\u007f]*)?$/.test(text)) {
    throw new Error("invalid slash command text");
  }
  return text;
}

function resolveExtensionCommands(extensions: Extension[]): Array<{ command: RegisteredCommand; invocationName: string }> {
  const commands = extensions.flatMap((extension) => [...extension.commands.values()]);
  const counts = new Map<string, number>();
  for (const command of commands) counts.set(command.name, (counts.get(command.name) ?? 0) + 1);

  const seen = new Map<string, number>();
  const takenNames = new Set<string>();
  return commands.map((command) => {
    const occurrence = (seen.get(command.name) ?? 0) + 1;
    seen.set(command.name, occurrence);
    let invocationName = counts.get(command.name)! > 1 ? `${command.name}:${occurrence}` : command.name;
    let suffix = occurrence;
    while (takenNames.has(invocationName)) invocationName = `${command.name}:${++suffix}`;
    takenNames.add(invocationName);
    return { command, invocationName };
  });
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
