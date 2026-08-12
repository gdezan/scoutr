package dev.cockpit.app.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import dev.cockpit.app.ui.components.AppTopBar

/**
 * The tab screens' shared chrome: top bar plus the zero-inset contract —
 * the outer NavHost Scaffold owns both system bars, so the inner one must
 * not consume them (a double inset stacks into dead bands under the clock
 * and above the bottom nav; see fix 25df24f).
 */
@Composable
internal fun TabScaffold(
    title: String,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { AppTopBar(title, onSearch = onSearch, onSettings = onSettings) },
        floatingActionButton = floatingActionButton,
    ) { inner ->
        content(inner)
    }
}
