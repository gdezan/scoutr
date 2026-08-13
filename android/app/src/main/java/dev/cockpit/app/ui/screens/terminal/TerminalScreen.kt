package dev.cockpit.app.ui.screens.terminal

import android.content.ClipboardManager
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import com.termux.view.TerminalView
import dev.cockpit.app.data.TerminalPreferencesStore
import dev.cockpit.app.ui.imeOrNavigationBarsPadding
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

    // A writable terminal owns hardware input (plan "Terminal behavior"):
    // without this the top bar's icon buttons keep focus, so Enter from a
    // hardware keyboard re-triggers the drawer button instead of reaching the
    // shell. Focus returns to the grid when the drawer closes. requestFocus
    // alone never raises the soft keyboard — that still waits for a tap.
    val writableNow = (ui.connection as? TerminalConnectionState.Ready)?.writable == true
    LaunchedEffect(viewRef, writableNow, drawerState.isOpen) {
        if (writableNow && !drawerState.isOpen) viewRef?.requestFocus()
    }

    val haptic = rememberHaptic()
    LaunchedEffect(ui.bellAt) {
        if (ui.bellAt > 0L) haptic(HapticEvent.Warning)
    }

    // OSC 52 / selection-toolbar paste: the emulator asks, the screen supplies
    // the device clipboard. Routed back through the ViewModel so bracketed
    // paste and the Ready(writable) input gate apply exactly as for keystrokes.
    val context = LocalContext.current
    LaunchedEffect(ui.pasteRequestAt) {
        if (ui.pasteRequestAt <= 0L) return@LaunchedEffect
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        if (!text.isNullOrEmpty()) viewModel.paste(text)
    }

    val uriHandler = LocalUriHandler.current

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
                    // New-workspace browses the bridge's allow-listed folders,
                    // the same listing Cockpit's session directory picker uses.
                    dirs = viewModel::browseDirs,
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
        // Union, not ime then nav: the IME already sits above the nav bar,
        // so stacking both leaves a nav-bar-tall gap above the keyboard.
        // The grid must still shrink so the cursor row stays visible.
        Column(modifier.fillMaxSize().imeOrNavigationBarsPadding()) {
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
                    IconButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.testTag("terminal_hierarchy"),
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Terminal hierarchy")
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.testTag("terminal_actions"),
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Terminal actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (ui.canTakeover) {
                                DropdownMenuItem(
                                    text = { Text("Take control") },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.takeover()
                                    },
                                )
                            }
                            // Explicit detach. Leaving via Back keeps the bridge
                            // grace window instead, so a user who comes straight
                            // back lands on the same live pane.
                            DropdownMenuItem(
                                text = { Text("Detach from pane") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.release()
                                },
                            )
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        val view = TerminalView(ctx, null)
                        view.isFocusable = true
                        view.isFocusableInTouchMode = true
                        view.setTerminalViewClient(
                            TerminalViewClient(
                                view = view,
                                session = viewModel.session,
                                modifierState = modifierState,
                                writable = { writableRef.value },
                                onGridMeasured = viewModel::reportGrid,
                                onLinkTap = { uriHandler.openUri(it) },
                                onPlainTap = {
                                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
                                },
                                currentFontSizeSp = { fontSpRef.value },
                                onFontScale = viewModel::updateFontSize,
                            ),
                        )
                        view.attachSession(viewModel.session)
                        // Generation resets replace the session's emulator (see
                        // RemoteTerminalSession.resetForGeneration); refreshEmulator
                        // re-fetches it so the new generation's content renders
                        // instead of the view repainting the stale emulator forever.
                        viewModel.session.callbacks.onScreenUpdated = { view.refreshEmulator() }
                        viewRef = view
                        view
                    },
                    // Font size lives in per-connection preferences, so pinch
                    // zoom and a fresh entry to the route both land here rather
                    // than in an effect that could race the factory's layout.
                    update = { view -> view.setTextSize((fontSizeSp * density).roundToInt()) },
                    onRelease = { view ->
                        // Drop the repaint callback before the view dies: the
                        // session outlives the route and would otherwise hold a
                        // detached view alive and repaint into it.
                        viewModel.session.callbacks.onScreenUpdated = {}
                        if (viewRef === view) viewRef = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                TerminalOverlay(
                    state = ui,
                    paneCount = ui.snapshot?.panes?.size ?: 0,
                    onRetry = viewModel::refreshNow,
                    onTakeover = viewModel::takeover,
                    onDismissTakeover = viewModel::dismissTakeover,
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
