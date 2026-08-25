package dev.scoutr.app.service

import android.util.Log
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.HostWorkCoordinator
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.notify.NotificationPresenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Turns a contentless FCM ping into a tray notification.
 *
 * Updated payloads identify a [HostProfileKey]. The key is checked before any
 * API call and the captured connection revision is checked again before every
 * notification write. The old constructor remains only for tests covering the
 * pre-registry singleton path.
 */
class FcmPingHandler(
    private val presenter: NotificationPresenter,
    private val api: ScoutrApi? = null,
    private val registry: HostRegistryStore? = null,
    private val hostClients: HostClientFactory? = null,
    private val isForegrounded: () -> Boolean,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    private val isRetiring: (String) -> Boolean = { false },
    @Suppress("UNUSED_PARAMETER") private val constructorMarker: Boolean = false,
    private val workCoordinator: HostWorkCoordinator? = null,
) {

    /** Positional host-aware construction for focused tests and adapters. */
    constructor(
        presenter: NotificationPresenter,
        registry: HostRegistryStore,
        hostClients: HostClientFactory,
        isForegrounded: () -> Boolean,
        delayMs: suspend (Long) -> Unit = { delay(it) },
        isRetiring: (String) -> Boolean = { false },
    ) : this(presenter, null, registry, hostClients, isForegrounded, delayMs, isRetiring, true)


    private data class Target(
        val profile: HostProfileKey,
        val connectionRevision: Long,
        val binding: HostConnectionBinding,
    )

    suspend fun handle(data: Map<String, String>) {
        val target = if (registry == null) null else resolveTarget(data)
        if (registry != null && target == null) return

        val paneId = data[KEY_PANE_ID]?.trim()?.takeIf { it.isNotEmpty() } ?: return
        when (data[KEY_KIND]) {
            KIND_RESOLVE -> {
                if (target == null) {
                    presenter.cancel(paneId)
                } else {
                    postIfCurrent(target) { presenter.cancel(target.profile, paneId) }
                }
            }

            KIND_BLOCKED -> if (target == null) {
                handleBlockedLegacy(paneId)
            } else {
                handleBlocked(target, paneId)
            }

            KIND_DONE -> if (target == null) {
                handleDoneLegacy(paneId)
            } else {
                handleDone(target, paneId)
            }

            KIND_ERRORED -> if (target == null) {
                handleErroredLegacy(paneId)
            } else {
                handleErrored(target, paneId)
            }
        }
    }

    private fun resolveTarget(data: Map<String, String>): Target? {
        val store = registry ?: return null
        val hostId = data[KEY_HOST_ID]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val generation = parseProfileGeneration(data[KEY_PROFILE_GENERATION]) ?: return null
        val profile = store.snapshot().profiles.firstOrNull {
            it.hostId == hostId && it.profileGeneration == generation
        } ?: return null

        if (isRetiring(profile.hostId)) return null
        val credentials = store.credentials(profile.hostId) ?: return null
        val key = HostProfileKey(profile.hostId, profile.profileGeneration)
        val binding = HostConnectionBinding(
            hostId = profile.hostId,
            connectionRevision = profile.connectionRevision,
            baseUrl = credentials.baseUrl,
            token = credentials.token,
            exposure = credentials.exposure,
        )
        if (workCoordinator?.isActive(binding) == false) return null
        return Target(
            profile = key,
            connectionRevision = profile.connectionRevision,
            binding = binding,
        )
    }

    private fun isCurrent(target: Target): Boolean {
        val store = registry ?: return true
        val profile = currentHostProfile(store, target.profile, isRetiring) ?: return false
        return profile.connectionRevision == target.connectionRevision &&
            workCoordinator?.isActive(target.binding) != false
    }

    private fun apiFor(target: Target): ScoutrApi? =
        hostClients?.api(target.binding) ?: api

    private suspend fun handleBlocked(target: Target, paneId: String) {
        val work = workCoordinator
        if (work != null) {
            work.trackIfActive(target.binding) { handleBlockedTracked(target, paneId) }
        } else {
            handleBlockedTracked(target, paneId)
        }
    }

    private suspend fun handleBlockedTracked(target: Target, paneId: String) {
        if (isForegrounded()) return
        val targetApi = apiFor(target) ?: return
        for ((attempt, waitMs) in BLOCKED_FETCH_RETRY_DELAYS_MS.withIndex()) {
            if (waitMs > 0) delayMs(waitMs)
            if (!isCurrent(target)) return
            try {
                val session = targetApi.agents().agents.find { it.live?.paneId == paneId }
                if (session != null) {
                    postIfCurrent(target) { presenter.showBlocked(target.profile, session) }
                    return
                }
                // The pane is gone or no longer listed — a resolve we lost,
                // not a fetch failure. Posting a degraded alert would be a lie.
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "FCM blocked fetch failed", e)
                if (attempt == BLOCKED_FETCH_RETRY_DELAYS_MS.lastIndex) {
                    postIfCurrent(target) { presenter.showDegraded(target.profile, paneId) }
                }
            }
        }
    }

    private suspend fun handleDone(target: Target, paneId: String) {
        val work = workCoordinator
        if (work != null) {
            work.trackIfActive(target.binding) { handleDoneTracked(target, paneId) }
        } else {
            handleDoneTracked(target, paneId)
        }
    }

    private suspend fun handleDoneTracked(target: Target, paneId: String) {
        if (isForegrounded()) return
        val targetApi = apiFor(target) ?: return
        for ((attempt, waitMs) in DONE_FETCH_RETRY_DELAYS_MS.withIndex()) {
            if (waitMs > 0) delayMs(waitMs)
            if (!isCurrent(target)) return
            try {
                val session = targetApi.agents().agents.find { it.live?.paneId == paneId }
                if (session != null) {
                    postIfCurrent(target) { presenter.showDone(target.profile, session) }
                    return
                }
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "FCM done fetch failed", e)
                if (attempt == DONE_FETCH_RETRY_DELAYS_MS.lastIndex) {
                    postIfCurrent(target) { presenter.showDegradedDone(target.profile, paneId) }
                }
            }
        }
    }
    private suspend fun handleErroredLegacy(paneId: String) {
        if (isForegrounded()) return
        val targetApi = api ?: return
        for ((attempt, waitMs) in ERRORED_FETCH_RETRY_DELAYS_MS.withIndex()) {
            if (waitMs > 0) delayMs(waitMs)
            try {
                val session = targetApi.agents().agents.find { it.live?.paneId == paneId }
                if (session != null) {
                    presenter.showErrored(session)
                    return
                }
                // The pane is gone or no longer listed — a resolve we lost,
                // not a fetch failure. Posting a degraded alert would be a lie.
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "FCM errored fetch failed", e)
                if (attempt == ERRORED_FETCH_RETRY_DELAYS_MS.lastIndex) presenter.showDegradedErrored(paneId)
            }
        }
    }

    private suspend fun handleErrored(target: Target, paneId: String) {
        val work = workCoordinator
        if (work != null) {
            work.trackIfActive(target.binding) { handleErroredTracked(target, paneId) }
        } else {
            handleErroredTracked(target, paneId)
        }
    }

    private suspend fun handleErroredTracked(target: Target, paneId: String) {
        if (isForegrounded()) return
        val targetApi = apiFor(target) ?: return
        for ((attempt, waitMs) in ERRORED_FETCH_RETRY_DELAYS_MS.withIndex()) {
            if (waitMs > 0) delayMs(waitMs)
            if (!isCurrent(target)) return
            try {
                val session = targetApi.agents().agents.find { it.live?.paneId == paneId }
                if (session != null) {
                    postIfCurrent(target) { presenter.showErrored(target.profile, session) }
                    return
                }
                // The pane is gone or no longer listed — a resolve we lost,
                // not a fetch failure. Posting a degraded alert would be a lie.
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "FCM errored fetch failed", e)
                if (attempt == ERRORED_FETCH_RETRY_DELAYS_MS.lastIndex) {
                    postIfCurrent(target) { presenter.showDegradedErrored(target.profile, paneId) }
                }
            }
        }
    }

    private fun postIfCurrent(target: Target, post: () -> Unit) {
        if (!isCurrent(target)) return
        val work = workCoordinator
        if (work == null) {
            if (isCurrent(target)) post()
        } else {
            work.withActive(target.binding) {
                if (isCurrent(target)) post()
            }
        }
    }

    // Compatibility path for tests and a pre-migration singleton process. It
    // is never reachable from ScoutrMessagingService, which always supplies a
    // registry and HostClientFactory.
    private suspend fun handleBlockedLegacy(paneId: String) {
        if (isForegrounded()) return
        val targetApi = api ?: return
        for ((attempt, waitMs) in BLOCKED_FETCH_RETRY_DELAYS_MS.withIndex()) {
            if (waitMs > 0) delayMs(waitMs)
            try {
                val session = targetApi.agents().agents.find { it.live?.paneId == paneId }
                if (session != null) {
                    presenter.showBlocked(session)
                    return
                }
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "FCM blocked fetch failed", e)
                if (attempt == BLOCKED_FETCH_RETRY_DELAYS_MS.lastIndex) presenter.showDegraded(paneId)
            }
        }
    }

    private suspend fun handleDoneLegacy(paneId: String) {
        if (isForegrounded()) return
        val targetApi = api ?: return
        for ((attempt, waitMs) in DONE_FETCH_RETRY_DELAYS_MS.withIndex()) {
            if (waitMs > 0) delayMs(waitMs)
            try {
                val session = targetApi.agents().agents.find { it.live?.paneId == paneId }
                if (session != null) {
                    presenter.showDone(session)
                    return
                }
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "FCM done fetch failed", e)
                if (attempt == DONE_FETCH_RETRY_DELAYS_MS.lastIndex) presenter.showDegradedDone(paneId)
            }
        }
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_HOST_ID = "hostId"
        const val KEY_PROFILE_GENERATION = "profileGeneration"
        const val KEY_PANE_ID = "paneId"
        const val KIND_BLOCKED = "blocked"
        const val KIND_RESOLVE = "resolve"
        const val KIND_DONE = "done"
        const val KIND_ERRORED = "errored"

        val BLOCKED_FETCH_RETRY_DELAYS_MS = longArrayOf(0L, 1_000L, 4_000L)
        val DONE_FETCH_RETRY_DELAYS_MS = BLOCKED_FETCH_RETRY_DELAYS_MS
        val ERRORED_FETCH_RETRY_DELAYS_MS = BLOCKED_FETCH_RETRY_DELAYS_MS

        private const val TAG = "FcmPingHandler"
    }
}
