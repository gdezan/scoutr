package dev.cockpit.app.ui.screens.terminal

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import com.termux.view.TerminalView
import dev.cockpit.app.data.TerminalPreferencesStore
import dev.cockpit.app.state.TerminalConnectionState
import dev.cockpit.app.state.TerminalViewModel
import dev.cockpit.app.ui.motion.HapticEvent
import dev.cockpit.app.ui.motion.rememberHaptic
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Full-screen terminal route (plan "Build Terminal UX"). Hosts the vendored
 * [TerminalView] in an [AndroidView] so the emulator survives recomposition
 * and configuration changes; the view is attached to the ViewModel's session
 * once and repainted through the session's onScreenUpdated callback. The
 * hierarchy drawer overlays without resizing the grid; Back closes the
 * drawer first, then leaves the route.
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    val density = LocalDensity.current.density

    LifecycleStartEffect(Unit) {
        viewModel.start()
        onStopOrDispose { }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Drawer close wins over route back while the drawer is open.
    BackHandler(onBack = onBack)
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    // The view client is created once in the AndroidView factory; these
    // remembered holders feed it values that change with [ui] / preferences.
    var viewRef by remember { mutableStateOf<TerminalView?>(null) }
    val modifierState = remember { ExtraKeyModifierState() }
    val writableRef = remember { mutableStateOf(false) }
    val fontSpRef = remember { mutableStateOf(TerminalPreferencesStore.ConnectionPreferences.DEFAULT_FONT_SIZE_SP) }

    val fontSizeSp = viewModel.preferences?.fontSizeSp
        ?: TerminalPreferencesStore.ConnectionPreferences.DEFAULT_FONT_SIZE_SP
    val extraKeysVisible = viewModel.preferences?.extraKeysVisible
        ?: TerminalPreferencesStore.ConnectionPreferences.DEFAULT_EXTRA_KEYS_VISIBLE

    SideEffect {
        writableRef.value = (ui.connection as? TerminalConnectionState.Ready)?.writable == true
        fontSpRef.value = fontSizeSp
    }
    LaunchedEffect(fontSizeSp) {
        viewRef?.setTextSize((fontSizeSp * density).roundToInt())
    }

    val haptic = rememberHaptic()
    LaunchedEffect(ui.bellAt) {
        if (ui.bellAt > 0L) haptic(HapticEvent.Warning)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                HierarchyDrawer(
                    snapshot = ui.snapshot,
                    busy = ui.hierarchyBusy,
                    error = ui.hierarchyError,
                    activePaneId = ui.paneId,
                    // The bridge's directory listing is not exposed by the
                    // ViewModel yet; New-workspace falls back to manual paths.
                    dirs = null,
                    onCreateTab = viewModel::createTab,
                    onCreateWorkspace = viewModel::createWorkspace,
                    onRenamePane = viewModel::renamePane,
                    onRenameTab = viewModel::renameTab,
                    onRenameWorkspace = viewModel::renameWorkspace,
                    onClosePane = viewModel::closePane,
                    onCloseTab = viewModel::closeTab,
                    onCloseWorkspace = viewModel::closeWorkspace,
                    onResult = { paneId ->
                        scope.launch { drawerState.close() }
                        if (paneId != null) viewModel.attach(paneId)
                    },
                )
            }
        },
    ) {
        Column(modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = ui.paneName ?: ui.title ?: "Terminal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    TerminalStatusChip(ui)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Terminal hierarchy")
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        val view = TerminalView(ctx, null)
                        view.setTerminalViewClient(
                            TerminalViewClient(
                                view = view,
                                session = viewModel.session,
                                modifierState = modifierState,
                                writable = { writableRef.value },
                                onGridMeasured = viewModel::reportGrid,
                                onLinkTap = {},
                                onPlainTap = {
                                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
                                },
                                currentFontSizeSp = { fontSpRef.value },
                                onFontScale = viewModel::updateFontSize,
                            ),
                        )
                        view.attachSession(viewModel.session)
                        viewModel.session.callbacks.onScreenUpdated = { view.onScreenUpdated() }
                        viewRef = view
                        view
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                TerminalOverlay(
                    state = ui,
                    paneCount = ui.snapshot?.panes?.size ?: 0,
                    onRetry = viewModel::refreshNow,
                    onTakeover = viewModel::takeover,
                    // The ViewModel has no dismiss path yet; the dialog clears
                    // on the next server message.
                    onDismissTakeover = {},
                )

                if (ui.paneClosedNotice) {
                    Box(Modifier.align(Alignment.TopCenter)) {
                        PaneClosedNotice(
                            paneName = ui.paneName,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                        )
                    }
                }
            }

            ExtraKeysRow(
                view = viewRef,
                state = modifierState,
                visible = extraKeysVisible,
                onToggleVisibility = { viewModel.updateExtraKeysVisible(!extraKeysVisible) },
            )
        }
    }
}
