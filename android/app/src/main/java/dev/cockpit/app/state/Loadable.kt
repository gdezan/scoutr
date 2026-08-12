package dev.cockpit.app.state

import dev.cockpit.app.net.BridgeException
import java.io.IOException

/**
 * Why a load failed, derived once from [BridgeException] so each
 * call site can decide explicitly instead of copy-pasting an
 * IOException-vs-Exception split:
 *
 * - [Offline]    — bridge unreachable (or no connection configured).
 * - [Unauthorized] — bridge returned 401: the saved token is stale/wrong.
 * - [Rejected]   — bridge returned 403: the request is outside what the
 *   bridge allows (e.g. a repo root outside the allow-list).
 * - [Server]     — any other failure (5xx, malformed payload, …).
 */
enum class FailureKind { Offline, Unauthorized, Rejected, Server }

/** Maps a thrown exception into the failure taxonomy. */
fun Throwable.failureKind(): FailureKind = when (this) {
    is BridgeException -> when (status) {
        401 -> FailureKind.Unauthorized
        403 -> FailureKind.Rejected
        else -> FailureKind.Server
    }
    is IOException -> FailureKind.Offline
    else -> FailureKind.Server
}

/**
 * The load lifecycle of one field, replacing the `loading`/`error`/data trio
 * so the impossible state (`loading = true, error != null`) is
 * unrepresentable. Use per field, not per screen: composite states keep
 * several of these (plus fields that are streams, not loads).
 */
sealed interface Loadable<out T> {
    /** Not started yet (before the first fetch is dispatched). */
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val reason: String, val kind: FailureKind) : Loadable<Nothing>
}