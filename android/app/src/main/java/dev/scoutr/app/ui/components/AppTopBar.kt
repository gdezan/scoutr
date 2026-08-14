package dev.scoutr.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The tab screens' top bar. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(
    title: String,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onTerminal: (() -> Unit)? = null,
    showLockup: Boolean = false,
) {
    TopAppBar(
        // The board is the app's front door, so it carries the lockup: the mark
        // sits inline with the screen title rather than on a row of its own
        // (reference §8b).
        navigationIcon = {
            if (showLockup) {
                Box(Modifier.padding(start = 16.dp, end = 4.dp)) { ScoutrMark() }
            }
        },
        title = { Text(title) },
        // The outer Scaffold already consumes the status-bar inset for the whole
        // NavHost, so the bar must not add its own or the two stack into a ~48dp
        // dead band under the clock — the top-edge twin of the bottom-nav band
        // that contentWindowInsets = WindowInsets(0.dp) removed below.
        windowInsets = WindowInsets(0.dp),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        actions = {
            if (onTerminal != null) {
                BarAction(onTerminal, Icons.Default.Terminal, "Terminal")
            }
            if (onSearch != null) {
                BarAction(onSearch, Icons.Default.Search, "Search agents and sessions")
            }
            if (onSettings != null) {
                BarAction(onSettings, Icons.Default.Settings, "Settings")
            }
        },
    )
}

/**
 * Header actions draw at 22dp inside the full 48dp touch target — the reference
 * glyph size, which Material's own 24dp default overshoots.
 */
@Composable
private fun BarAction(onClick: () -> Unit, icon: ImageVector, label: String) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
    }
}
