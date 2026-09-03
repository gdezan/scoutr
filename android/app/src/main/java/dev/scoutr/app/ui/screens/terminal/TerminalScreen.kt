package dev.scoutr.app.ui.screens.terminal

import dev.scoutr.app.ui.theme.ScoutrSpace
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.core.content.res.ResourcesCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import dev.scoutr.app.data.TerminalSnapshot
import androidx.lifecycle.compose.LifecycleStartEffect
import com.termux.view.TerminalView
import dev.scoutr.app.R
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.ui.components.PressTintSurface
import dev.scoutr.app.ui.theme.ScoutrMono
import dev.scoutr.app.ui.theme.ScoutrTerminalMono
import dev.scoutr.app.ui.imeOrNavigationBarsPadding
import dev.scoutr.app.state.TerminalConnectionState
import dev.scoutr.app.state.TerminalViewModel
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.rememberHaptic
import java.util.concurrent.atomic.AtomicBoolean
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

    // Owned by the current TerminalView instance; replaced whenever the
    // AndroidView factory builds a new one.
    val repainterRef = remember { mutableStateOf<TerminalRepainter?>(null) }

    // Re-read on every write to the shared store, so pinch, the strip, and the
    // Settings rows all land here without leaving the route.
    val prefsRevision by viewModel.viewPreferencesRevision.collectAsState()
    val fontSizeSp = remember(prefsRevision) {
        viewModel.preferences?.fontSizeSp
            ?: TerminalPreferencesStore.ConnectionPreferences.DEFAULT_FONT_SIZE_SP
    }
    val extraKeysVisible = remember(prefsRevision) {
        viewModel.preferences?.extraKeysVisible
            ?: TerminalPreferencesStore.ConnectionPreferences.DEFAULT_EXTRA_KEYS_VISIBLE
    }

    SideEffect {
        writableRef.value = (ui.connection as? TerminalConnectionState.Ready)?.writable == true
        fontSpRef.value = fontSizeSp
    }

    // A writable terminal owns hardware input. Keep focus across connection
    // changes and return it after the hierarchy drawer closes; requestFocus
    // alone never raises the soft keyboard, so tapping still controls whether
    // the keyboard is shown.
    val writableNow = (ui.connection as? TerminalConnectionState.Ready)?.writable == true
    LaunchedEffect(viewRef, writableNow, ui.connection, drawerState.isOpen) {
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
                    // the same listing Scoutr's session directory picker uses.
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
                    Modifier.fillMaxWidth().padding(horizontal = ScoutrSpace.lg, vertical = ScoutrSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TerminalBreadcrumb(
                        snapshot = ui.snapshot,
                        paneId = ui.paneId,
                        paneName = ui.paneName ?: ui.title ?: "Terminal",
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.weight(1f),
                    )
                    TerminalStatusChip(ui)
                    Spacer(Modifier.width(ScoutrSpace.sm))
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
                        // TerminalView.setTypeface assumes its renderer already exists; initialize
                        // the renderer before applying the bundled terminal font.
                        view.setTextSize((fontSpRef.value * density).roundToInt())
                        view.setTypeface(ResourcesCompat.getFont(ctx, R.font.jetbrains_mono))
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
                        val repainter = TerminalRepainter(view)
                        repainterRef.value = repainter
                        viewModel.session.callbacks.onScreenUpdated = repainter::onScreenUpdated
                        viewRef = view
                        view
                    },
                    // Font size lives in per-connection preferences, so pinch
                    // zoom and a fresh entry to the route land here instead
                    // of an effect that could race the factory's layout.
                    update = { view -> view.setTextSize((fontSizeSp * density).roundToInt()) },
                    onRelease = { view ->
                        // Drop the repaint callback before the view dies: the
                        // session outlives the route and would otherwise hold a
                        // detached view alive and repaint into it.
                        viewModel.session.callbacks.onScreenUpdated = {}
                        repainterRef.value?.release()
                        repainterRef.value = null
                        if (viewRef === view) viewRef = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                TerminalScrollControls(
                    view = viewRef,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = ScoutrSpace.sm),
                )

                TerminalOverlay(
                    state = ui,
                    paneCount = ui.snapshot?.panes?.size ?: 0,
                    onRetry = viewModel::retry,
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
@Composable
private fun TerminalScrollControls(
    view: TerminalView?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = { view?.scrollTerminalHistoryByPage(-1) },
                enabled = view != null,
                modifier = Modifier.testTag("terminal_scroll_up"),
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll terminal up")
            }
            IconButton(
                onClick = { view?.scrollTerminalHistoryByPage(1) },
                enabled = view != null,
                modifier = Modifier.testTag("terminal_scroll_down"),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll terminal down")
            }
        }
    }
}

@Composable
private fun TerminalBreadcrumb(
    snapshot: TerminalSnapshot?,
    paneId: String?,
    paneName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pane = snapshot?.let { current -> paneId?.let(current::pane) }
    val workspace = pane?.let { snapshot?.workspaceName(it.workspaceId) } ?: "Workspace"
    val tab = pane?.let { snapshot?.tabName(it.tabId) } ?: "Tab"
    PressTintSurface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainer,
        pressedColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .testTag("terminal_breadcrumb")
            .padding(vertical = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // The breadcrumb sits over the grid, so it takes the grid's face:
                // JetBrains Mono is the full-screen terminal's mono, and Martian
                // beside it would read as a second typeface on one surface (§9a).
                text = "$workspace › $tab › $paneName",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = ScoutrTerminalMono,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Choose terminal pane",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Bridges terminal screen updates to one [TerminalView] instance.
 *
 * Updates arrive on the terminal dispatcher, but `refreshEmulator` touches view state (scroll
 * position, scrollbars, invalidate), so the repaint is posted to the UI thread. Exactly one
 * repaint is kept in flight: a burst of output batches coalesces into the next frame's repaint
 * instead of queueing one Runnable per batch. `refreshEmulator`, rather than a bare invalidate,
 * re-fetches the emulator that generation resets replace (see
 * [dev.scoutr.app.terminal.RemoteTerminalSession.resetForGeneration]).
 *
 * The session outlives the route, so [release] both drops the queued repaint and latches the
 * repainter off — a post that already escaped the release check must not repaint into a view the
 * route has let go.
 */
private class TerminalRepainter(private val view: TerminalView) : Runnable {
    private val pending = AtomicBoolean(false)

    @Volatile private var released = false

    /** Called from the terminal dispatcher. */
    fun onScreenUpdated() {
        if (released) return
        if (pending.compareAndSet(false, true)) view.post(this)
    }

    /** Runs on the UI thread. Clears [pending] first so an update during the repaint re-posts. */
    override fun run() {
        pending.set(false)
        if (!released) view.refreshEmulator()
    }

    fun release() {
        released = true
        view.removeCallbacks(this)
    }
}
