package dev.scoutr.app.ui.nav

/**
 * The Chat route pattern. MainActivity's `Routes.CHAT` delegates here so the
 * shell predicate and the NavHost declaration cannot drift.
 */
const val CHAT_ROUTE = "chat?sessionKey={sessionKey}&bootstrapPaneId={bootstrapPaneId}&hostProfile={hostProfile}&status={status}"

/**
 * Routes that keep the wide shell (session panel with its destination row):
 * the four tabs plus Chat. Everything else owns the whole window.
 * NavHost reports the route *pattern*, not the filled URL, so Chat is matched
 * against [CHAT_ROUTE] — a `"chat?"` prefix test would never fire.
 */
fun isShellRoute(route: String?): Boolean =
    Destination.isDestinationRoute(route) || route == CHAT_ROUTE
