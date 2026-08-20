package dev.scoutr.app.service

import android.util.Log
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.notify.NotificationPresenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Turns a contentless FCM ping into a tray notification.
 *
 * The wire payload is only `kind` and `paneId`. Identity is fetched from
 * `GET /api/agents` over the tailnet so nothing identifying an agent transits
 * Google. A `resolve` ping cancels the slot; a `blocked` ping is dropped
 * while Scoutr is already on screen.
 */
class FcmPingHandler(
    private val presenter: NotificationPresenter,
    private val api: ScoutrApi,
    private val isForegrounded: () -> Boolean,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
) {

    suspend fun handle(data: Map<String, String>) {
        val paneId = data[KEY_PANE_ID]?.takeIf { it.isNotBlank() } ?: return
        when (data[KEY_KIND]) {
            KIND_RESOLVE -> presenter.cancel(paneId)
            KIND_BLOCKED -> handleBlocked(paneId)
        }
    }

    private suspend fun handleBlocked(paneId: String) {
        if (isForegrounded()) return
        for ((attempt, waitMs) in BLOCKED_FETCH_RETRY_DELAYS_MS.withIndex()) {
            if (waitMs > 0) delayMs(waitMs)
            try {
                val session = api.agents().agents.find { it.live?.paneId == paneId }
                if (session != null) {
                    presenter.showBlocked(session)
                    return
                }
                // The pane is gone or no longer listed — a resolve we lost, not
                // a fetch failure. Posting the degraded alert would be a lie.
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "FCM blocked fetch failed", e)
                if (attempt == BLOCKED_FETCH_RETRY_DELAYS_MS.lastIndex) {
                    presenter.showDegraded(paneId)
                }
            }
        }
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_PANE_ID = "paneId"
        const val KIND_BLOCKED = "blocked"
        const val KIND_RESOLVE = "resolve"

        /** Immediate try, then 1s, then 4s — two retries after the first miss. */
        val BLOCKED_FETCH_RETRY_DELAYS_MS = longArrayOf(0L, 1_000L, 4_000L)

        private const val TAG = "FcmPingHandler"
    }
}
