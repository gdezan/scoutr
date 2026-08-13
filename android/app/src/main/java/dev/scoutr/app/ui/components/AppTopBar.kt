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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp

/** The tab screens' top bar. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(
    title: String,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onTerminal: (() -> Unit)? = null,
) {
    TopAppBar(
        title = { Text(title) },
        // The outer Scaffold already consumes the status-bar inset for the whole
        // NavHost, so the bar must not add its own or the two stack into a ~48dp
        // dead band under the clock — the top-edge twin of the bottom-nav band
        // that contentWindowInsets = WindowInsets(0.dp) removed below.
        windowInsets = WindowInsets(0.dp),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        actions = {
            if (onTerminal != null) {
                IconButton(onClick = onTerminal) {
                    Icon(Icons.Default.Terminal, contentDescription = "Terminal")
                }
            }
            if (onSearch != null) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search agents and sessions")
                }
            }
            if (onSettings != null) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        },
    )
}
