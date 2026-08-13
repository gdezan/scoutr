package dev.scoutr.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Single source of truth for the phone bottom-bar destinations. The bar's
 * visibility rule is `currentRoute in Destination.routes` — adding a tab is
 * one entry here, and the bar check cannot drift from it.
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
