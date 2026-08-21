package dev.scoutr.app.ui.nav

import androidx.navigation.NavController
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.decodeSessionKey
import dev.scoutr.app.data.encode
import java.net.URLEncoder

/**
 * The app's non-tab routes; the tab routes live in [Destination]. One owner
 * for route patterns, nav argument names, and the builders that fill them,
 * so a builder and its NavHost declaration cannot drift.
 */
object AppRoutes {
    const val CONNECT = "connect"

    /** One source with the shell predicate; see ui/nav/ShellRoute.kt. */
    const val CHAT = CHAT_ROUTE

    const val FILE_BROWSER = "files?cwd={cwd}"
    const val FILE_VIEWER = "file-viewer?cwd={cwd}&file={file}"
    const val SETTINGS = "settings"
    const val TERMINAL = "terminal?paneId={paneId}"

    /** Chat's argument names and defaults, shared by both chat entry paths. */
    object ChatArgs {
        const val SESSION_KEY = "sessionKey"
        const val BOOTSTRAP_PANE_ID = "bootstrapPaneId"
        const val STATUS = "status"

        /** Sessions without a known state enter as working until live data corrects it. */
        const val DEFAULT_STATUS = "working"
    }

    object FileArgs {
        const val CWD = "cwd"
        const val FILE = "file"
    }

    object TerminalArgs {
        const val PANE_ID = "paneId"
    }

    fun chat(key: SessionKey, status: String): String =
        "chat?${ChatArgs.SESSION_KEY}=${key.encode()}&${ChatArgs.STATUS}=$status"

    fun bootstrapChat(paneId: String, status: String): String =
        "chat?${ChatArgs.BOOTSTRAP_PANE_ID}=${encode(paneId)}&${ChatArgs.STATUS}=$status"

    fun fileBrowser(cwd: String): String =
        "files?${FileArgs.CWD}=${encode(cwd)}"

    fun fileViewer(cwd: String, file: String): String =
        "file-viewer?${FileArgs.CWD}=${encode(cwd)}&${FileArgs.FILE}=${encode(file)}"

    /**
     * Full-screen terminal. A null [paneId] lets the ViewModel resolve the
     * pane (saved pane, then herdr's focused pane, then the first one), so the
     * global top-bar action and the per-session "Open terminal" share a route.
     */
    fun terminal(paneId: String? = null): String =
        "terminal?${TerminalArgs.PANE_ID}=${paneId?.let(::encode) ?: ""}"
}

/** The single URL-encoding path; no call site hand-rolls encoder fragments. */
private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

/**
 * A session's chat route: by canonical key, or by its pane until a fresh
 * launch converges. Null only when neither identity is known — callers skip
 * the navigation instead of building a broken route.
 */
internal fun chatRoute(key: SessionKey?, bootstrapPaneId: String?, status: String): String? =
    key?.let { AppRoutes.chat(it, status) }
        ?: bootstrapPaneId?.let { AppRoutes.bootstrapChat(it, status) }

/** Open a session by canonical key, or by its pane until a fresh launch converges. */
internal fun NavController.navigateToChat(key: SessionKey?, bootstrapPaneId: String?, status: String) {
    navigate(chatRoute(key, bootstrapPaneId, status) ?: return)
}

/**
 * Open a session from the wide window's session panel. Switching rows replaces
 * the detail pane rather than stacking chats, so repeated selection cannot grow
 * the back stack or leak per-session ChatViewModels.
 */
internal fun NavController.navigateToChatFromPanel(key: SessionKey?, bootstrapPaneId: String?, status: String) {
    navigate(chatRoute(key, bootstrapPaneId, status) ?: return) {
        popUpTo(Destination.Board.route) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * Decode the canonical session key carried in a filled chat URL back stack
 * entry. Blank arguments decode to null, matching the NavHost defaults.
 */
internal fun decodedChatSessionKey(rawValue: String?): SessionKey? =
    rawValue?.takeIf(String::isNotBlank)?.let(::decodeSessionKey)

/**
 * Cold start lands on Board when a pairing exists, else on Connect.
 */
internal fun initialStartDestination(paired: Boolean): String =
    if (paired) Destination.Board.route else AppRoutes.CONNECT
