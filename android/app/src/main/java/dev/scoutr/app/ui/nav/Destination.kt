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
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Board("board", "Board", Icons.Default.GridView),
    Sessions("sessions", "Sessions", Icons.Default.History),
    Usage("usage", "Usage", Icons.Default.BarChart),
    Review("review", "Review", Icons.Default.Code);

    companion object {
        val routes: Set<String> = entries.map { it.route }.toSet()
        fun forRoute(route: String?): Destination? = entries.find { it.route == route }
    }
}
