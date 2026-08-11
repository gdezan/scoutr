import type { Route } from "./types.js";
import { agentsRoutes } from "./agents.js";
import { catalogRoutes } from "./catalog.js";
import { commandsRoutes } from "./commands.js";
import { dirsRoutes } from "./dirs.js";
import { healthRoutes } from "./health.js";
import { modelsRoutes } from "./models.js";
import { reviewRoutes } from "./review.js";
import { attachmentRoutes } from "./attachments.js";
import { sessionsRoutes } from "./sessions.js";
import { usageRoutes } from "./usage.js";

/** Every HTTP route, grouped by feature (mirrors the src/ module layout). */
export function buildRoutes(): Route[] {
  return [
    ...healthRoutes,
    ...agentsRoutes,
    ...sessionsRoutes,
    ...catalogRoutes,
    ...usageRoutes,
    ...modelsRoutes,
    ...commandsRoutes,
    ...dirsRoutes,
    ...reviewRoutes,
    ...attachmentRoutes,
  ];
}
