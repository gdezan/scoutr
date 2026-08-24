package dev.scoutr.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Single source of truth for the tab destinations. Both chrome shapes render
 * it via [DestinationNavRow]: the compact bottom bar (visibility rule
 * `!isWide && currentRoute in Destination.routes`) and the wide window's
 * session-panel row. Adding a tab is one entry here, and neither chrome can
 * drift from it.
 */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** Filled remote destinations use this qualified NavHost pattern. */
    val pattern: String = route,
) {
    Board("board", "Board", Icons.Default.GridView),
    Sessions("sessions", "Sessions", Icons.Default.History),
    Usage("usage", "Usage", Icons.Default.BarChart, "usage?hostProfile={hostProfile}"),
    Review("review", "Review", Icons.Default.Code, "review?hostProfile={hostProfile}&repoPath={repoPath}");

    companion object {
        /** Stable shell entry names retained for tab callbacks and persisted UI tests. */
        val routes: Set<String> = entries.map { it.route }.toSet()
        val patterns: Set<String> = entries.map { it.pattern }.toSet()
        fun forRoute(route: String?): Destination? = entries.find { it.route == route || it.pattern == route }
        fun isDestinationRoute(route: String?): Boolean = route in routes || route in patterns
    }
}
