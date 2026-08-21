package dev.scoutr.app.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.scoutr.app.ui.components.AppTopBar
import dev.scoutr.app.ui.imeOrNavigationBarsPadding

/**
 * The tab screens' shared chrome: top bar plus the zero-inset contract —
 * the outer NavHost Scaffold owns both system bars, so the inner one must
 * not consume them (a double inset stacks into dead bands under the clock
 * and above the bottom nav; see fix 25df24f).
 *
 * [ownsBottomInset] hands the bottom inset to the screen itself. Compact
 * windows keep it false — ScoutrBottomBar is the one consumer there. Wide
 * windows have no bottom bar anymore, so their detail-pane tabs set it true
 * and lift header-anchored content above the navigation bar exactly once.
 */
@Composable
internal fun TabScaffold(
    title: String,
    // Each action is opt-in: the reference gives every tab its own header
    // composition rather than one shared row (§8b, §9c).
    subtitle: String? = null,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onTerminal: (() -> Unit)? = null,
    showLockup: Boolean = false,
    extraActions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    ownsBottomInset: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = if (ownsBottomInset) modifier.imeOrNavigationBarsPadding() else modifier,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title,
                subtitle = subtitle,
                onSearch = onSearch,
                onSettings = onSettings,
                onTerminal = onTerminal,
                showLockup = showLockup,
                extraActions = extraActions,
            )
        },
        floatingActionButton = floatingActionButton,
    ) { inner ->
        content(inner)
    }
}
