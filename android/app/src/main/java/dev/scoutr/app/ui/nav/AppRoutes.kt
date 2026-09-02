package dev.scoutr.app.ui.nav

import androidx.navigation.NavController
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.decodeHostProfileKey
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
    const val CONNECT_ADD = "connect/add"
    const val CONNECT_REFRESH = "connect/refresh?hostProfile={hostProfile}"

    /** One source with the shell predicate; see ui/nav/ShellRoute.kt. */
    const val CHAT = CHAT_ROUTE

    const val FILE_BROWSER = "files?cwd={cwd}&hostProfile={hostProfile}"
    const val FILE_VIEWER = "file-viewer?cwd={cwd}&file={file}&hostProfile={hostProfile}"
    const val SETTINGS = "settings"
    const val TERMINAL = "terminal?paneId={paneId}&hostProfile={hostProfile}"
    const val SUBAGENT_PROGRESS = "subagent-progress?runId={runId}&hostProfile={hostProfile}"

    /** Chat's argument names and defaults, shared by both chat entry paths. */
    object ChatArgs {
        const val SESSION_KEY = "sessionKey"
        const val BOOTSTRAP_PANE_ID = "bootstrapPaneId"
        const val STATUS = "status"
        const val HOST_PROFILE = "hostProfile"

        /** Sessions without a known state enter as working until live data corrects it. */
        const val DEFAULT_STATUS = "working"
    }

    object FileArgs {
        const val CWD = "cwd"
        const val FILE = "file"
        const val HOST_PROFILE = "hostProfile"
    }

    object TerminalArgs {
        const val PANE_ID = "paneId"
        const val HOST_PROFILE = "hostProfile"
    }

    object SubagentArgs {
        const val RUN_ID = "runId"
        const val HOST_PROFILE = "hostProfile"
    }

    fun refreshConnection(profile: HostProfileKey): String =
        "connect/refresh?hostProfile=${profile.encode()}"

    /** New navigation builders always carry the immutable profile generation. */
    fun chat(profile: HostProfileKey, key: SessionKey, status: String): String =
        "chat?${ChatArgs.SESSION_KEY}=${key.encode()}&${ChatArgs.HOST_PROFILE}=${profile.encode()}&${ChatArgs.STATUS}=$status"

    fun bootstrapChat(profile: HostProfileKey, paneId: String, status: String): String =
        "chat?${ChatArgs.BOOTSTRAP_PANE_ID}=${encode(paneId)}&${ChatArgs.HOST_PROFILE}=${profile.encode()}&${ChatArgs.STATUS}=$status"

    /** Legacy test/old-link builders; production navigation uses the overloads above. */
    fun chat(key: SessionKey, status: String): String =
        "chat?${ChatArgs.SESSION_KEY}=${key.encode()}&${ChatArgs.STATUS}=$status"

    fun bootstrapChat(paneId: String, status: String): String =
        "chat?${ChatArgs.BOOTSTRAP_PANE_ID}=${encode(paneId)}&${ChatArgs.STATUS}=$status"

    fun fileBrowser(profile: HostProfileKey, cwd: String): String =
        "files?${FileArgs.CWD}=${encode(cwd)}&${FileArgs.HOST_PROFILE}=${profile.encode()}"

    fun fileViewer(profile: HostProfileKey, cwd: String, file: String): String =
        "file-viewer?${FileArgs.CWD}=${encode(cwd)}&${FileArgs.FILE}=${encode(file)}&${FileArgs.HOST_PROFILE}=${profile.encode()}"

    fun fileBrowser(cwd: String): String =
        "files?${FileArgs.CWD}=${encode(cwd)}"

    fun fileViewer(cwd: String, file: String): String =
        "file-viewer?${FileArgs.CWD}=${encode(cwd)}&${FileArgs.FILE}=${encode(file)}"

    /** Usage/review are concrete routes even though their shell entries are hostless. */
    fun usage(profile: HostProfileKey): String =
        "usage?${DestinationArgs.HOST_PROFILE}=${profile.encode()}"

    fun review(profile: HostProfileKey, repoPath: String? = null): String =
        "review?${DestinationArgs.HOST_PROFILE}=${profile.encode()}&${DestinationArgs.REPO_PATH}=${repoPath?.let(::encode) ?: ""}"

    /**
     * Full-screen terminal. A null [paneId] lets the ViewModel resolve the
     * pane (saved pane, then herdr's focused pane, then the first one), so the
     * global top-bar action and the per-session "Open terminal" share a route.
     */
    fun terminal(profile: HostProfileKey, paneId: String? = null): String =
        "terminal?${TerminalArgs.PANE_ID}=${paneId?.let(::encode) ?: ""}&${TerminalArgs.HOST_PROFILE}=${profile.encode()}"

    fun terminal(paneId: String? = null): String =
        "terminal?${TerminalArgs.PANE_ID}=${paneId?.let(::encode) ?: ""}"


    fun subagentProgress(profile: HostProfileKey, runId: String): String =
        "subagent-progress?${SubagentArgs.RUN_ID}=${encode(runId)}&${SubagentArgs.HOST_PROFILE}=${profile.encode()}"
}

/** The single URL-encoding path; no call site hand-rolls encoder fragments. */
private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

/**
 * A session's chat route: by canonical key, or by its pane until a fresh
 * launch converges. Null only when neither identity is known — callers skip
 * the navigation instead of building a broken route.
 */
internal fun chatRoute(
    profile: HostProfileKey,
    key: SessionKey?,
    bootstrapPaneId: String?,
    status: String,
): String? = key?.let { AppRoutes.chat(profile, it, status) }
    ?: bootstrapPaneId?.let { AppRoutes.bootstrapChat(profile, it, status) }

/** Compatibility route helper retained for non-production unit fixtures. */
internal fun chatRoute(key: SessionKey?, bootstrapPaneId: String?, status: String): String? =
    key?.let { AppRoutes.chat(it, status) }
        ?: bootstrapPaneId?.let { AppRoutes.bootstrapChat(it, status) }

/** Open a session by canonical key, or by its pane until a fresh launch converges. */
internal fun NavController.navigateToChat(
    profile: HostProfileKey,
    key: SessionKey?,
    bootstrapPaneId: String?,
    status: String,
) {
    navigate(chatRoute(profile, key, bootstrapPaneId, status) ?: return)
}

/** Compatibility overload for old isolated navigation tests. */
internal fun NavController.navigateToChat(key: SessionKey?, bootstrapPaneId: String?, status: String) {
    navigate(chatRoute(key, bootstrapPaneId, status) ?: return)
}

/**
 * Open a session from the wide window's session panel. Switching rows replaces
 * the detail pane rather than stacking chats, so repeated selection cannot grow
 * the back stack or leak per-session ChatViewModels.
 */
internal fun NavController.navigateToChatFromPanel(
    profile: HostProfileKey,
    key: SessionKey?,
    bootstrapPaneId: String?,
    status: String,
) {
    navigate(chatRoute(profile, key, bootstrapPaneId, status) ?: return) {
        popUpTo(Destination.Board.route) { inclusive = false }
        launchSingleTop = true
    }
}

/** Compatibility overload for old isolated navigation tests. */
internal fun NavController.navigateToChatFromPanel(key: SessionKey?, bootstrapPaneId: String?, status: String) {
    navigate(chatRoute(key, bootstrapPaneId, status) ?: return) {
        popUpTo(Destination.Board.route) { inclusive = false }
        launchSingleTop = true
    }
}


internal fun NavController.navigateToSubagentProgress(profile: HostProfileKey, runId: String) {
    navigate(AppRoutes.subagentProgress(profile, runId))
}

/** Arguments shared by host-qualified shell destinations. */
object DestinationArgs {
    const val HOST_PROFILE = "hostProfile"
    const val REPO_PATH = "repoPath"
}

fun decodeRouteProfile(raw: String?): HostProfileKey? =
    raw?.takeIf(String::isNotBlank)?.let(::decodeHostProfileKey)

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

/** Registry-aware initial routing: pending singleton migration is an installation. */
fun initialStartDestination(hasInstallation: Boolean, pendingMigration: Boolean): String =
    if (hasInstallation || pendingMigration) Destination.Board.route else AppRoutes.CONNECT
