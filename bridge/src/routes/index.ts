import type { Route } from "./types.js";
import { agentsRoutes } from "./agents.js";
import { catalogRoutes } from "./catalog.js";
import { commandsRoutes } from "./commands.js";
import { dirsRoutes } from "./dirs.js";
import { filesRoutes } from "./files.js";
import { healthRoutes } from "./health.js";
import { modelsRoutes } from "./models.js";
import { reviewRoutes } from "./review.js";
import { attachmentRoutes } from "./attachments.js";
import { sessionsRoutes } from "./sessions.js";
import { sessionCommandsRoutes } from "./session-commands.js";
import { terminalRoutes } from "./terminal.js";
import { updateRoutes } from "./update.js";
import { usageRoutes } from "./usage.js";

/** Every HTTP route, grouped by feature (mirrors the src/ module layout). */
export function buildRoutes(): Route[] {
  return [
    ...healthRoutes,
    ...terminalRoutes,
    ...agentsRoutes,
    ...sessionsRoutes,
    ...sessionCommandsRoutes,
    ...catalogRoutes,
    ...usageRoutes,
    ...modelsRoutes,
    ...commandsRoutes,
    ...dirsRoutes,
    ...filesRoutes,
    ...reviewRoutes,
    ...attachmentRoutes,
    ...updateRoutes,
  ];
}
