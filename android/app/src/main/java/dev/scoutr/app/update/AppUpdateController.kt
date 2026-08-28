package dev.scoutr.app.update

import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.HostWorkCoordinator
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Everything the Settings row and the shade both render from. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Building : UpdateState
    data class Downloading(val bytes: Long, val total: Long) : UpdateState

    /**
     * Verified bytes on disk, waiting for a foreground moment to commit.
     *
     * [lastError] carries a previous install attempt's failure. The state stays
     * Ready rather than Failed because the APK is still good: a declined or
     * failed install must leave the user one tap from retrying, not facing
     * another multi-minute build.
     */
    data class Ready(val identity: StagedIdentity, val lastError: String? = null) : UpdateState

    /** Commit issued; the system's confirmation sheet owns the flow now. */
    data object Installing : UpdateState
    data class Failed(val message: String, val resumable: Boolean) : UpdateState
}

/**
 * An update action a notification asked for, carried through MainActivity.
 *
 * Both must run from a foreground Activity: committing needs one or the system
 * install sheet is suppressed, and starting the `dataSync` service needs one or
 * the platform refuses the start outright.
 */
enum class PendingUpdateAction { Install, Resume }

/** Commits a staged APK to the device. [ApkInstaller.forContext] is the real one. */
fun interface ApkInstall {
    suspend fun install(apk: File)
}

/** What the controller tells the user about an update it cannot show in Settings. */
interface UpdateNotifier {
    fun showUpdateReady(identity: StagedIdentity)
    fun showUpdateFailed(message: String, resumable: Boolean)
    fun cancelUpdateNotifications()
}

/**
 * Process-wide owner of the self-update.
 *
 * Ownership deliberately does not live in the Settings composition: a host
 * build plus a multi-megabyte download over a slow link takes minutes, and a
 * composition-scoped coroutine dies the moment the user navigates away. Here
 * the job outlives every screen, and Settings becomes a view of [state].
 *
 * Committing is separate from staging because Android suppresses the
 * PackageInstaller confirmation Activity unless the app is foreground. Whether
 * to commit or to notify is therefore a lifecycle decision, and it is made
 * here — see [finish].
 */
class AppUpdateController(
    private val scope: CoroutineScope,
    private val work: HostWorkCoordinator,
    private val notifications: UpdateNotifier,
    private val staging: UpdateStaging,
    private val installer: ApkInstall,
    private val installedVersionCode: Int = 0,
    private val pollDelayMs: Long = 2_000,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Which run owns the right to publish state. A cancelled job finishes
     * asynchronously, so without this its terminal handler could overwrite the
     * state of the run that replaced it — stopping the service out from under a
     * live download.
     */
    private var generation: Long = 0

    /** The generation the *user* cancelled, as opposed to host retirement. */
    private var userCancelledGeneration: Long = -1

    /**
     * True while the Settings update screen is composed and started.
     *
     * [dev.scoutr.app.state.ForegroundTracker] cannot answer this: it is
     * app-wide, so it would also say "yes" when the user is mid-sentence in a
     * chat or watching a live terminal, where throwing up an install sheet
     * would be an ambush.
     */
    @Volatile
    private var updateScreenVisible: Boolean = false

    fun setUpdateScreenVisible(visible: Boolean) {
        updateScreenVisible = visible
    }

    /**
     * Adopts whatever the last process left on disk, so minutes of build and
     * download are not thrown away by a restart.
     *
     * A complete staged APK that is newer than the installed app is offered
     * for install immediately; a partial one is left untouched for an
     * explicit resume, and nothing here ever starts a transfer on its own.
     * A complete but stale one is not offered at all — see [rehydrate].
     * Deliberately answerable offline: the staged bytes were verified when
     * they landed, and whether the host has since built
     * something newer does not make this APK less installable — only an APK
     * older than the *installed* app is refused, because that one could
     * never be committed at all.
     */
    fun rehydrate() {
        if (_state.value != UpdateState.Idle) return
        val staged = staging.complete() ?: return
        // An APK that is not newer than the running app can never be
        // committed — the system rejects the downgrade — and offering it
        // anyway wedges the flow on Ready forever, with no way back to build
        // and download. That is exactly what a self-update stages while a
        // different version is being installed, so an unknown versionCode
        // (a sidecar from before it was recorded) counts as stale too. A
        // "Ready" notification the refusing process's predecessor left in
        // the shade must go too: tapping it would deep-link into an install
        // this app can never accept.
        if (staged.identity.versionCode <= installedVersionCode) {
            notifications.cancelUpdateNotifications()
            return
        }
        _state.value = UpdateState.Ready(staged.identity)
    }

    /**
     * Gives up a staged APK and returns to Idle, freeing a fresh build and
     * download.
     *
     * Ready is otherwise a dead end: only a *successful* install leaves it,
     * so a stage the system will never accept — a signature mismatch, a
     * declined downgrade — would trap the flow on "Install now" forever.
     */
    fun discardStaged() {
        if (_state.value !is UpdateState.Ready) return
        staging.discard()
        _state.value = UpdateState.Idle
        notifications.cancelUpdateNotifications()
    }

    /** Starts the pipeline from Settings or a resume action. A no-op while one is running. */
    fun start(api: ScoutrApi, binding: HostConnectionBinding) {
        if (!canStart()) return
        run(api, binding)
    }

    fun cancel() {
        userCancelledGeneration = generation
        generation += 1
        job?.cancel()
        job = null
        // Partial bytes stay on disk: cancelling is "not now", not "throw away
        // the minutes already spent".
        _state.value = UpdateState.Idle
        notifications.cancelUpdateNotifications()
    }

    /**
     * Commits a [UpdateState.Ready] state. The caller guarantees a foreground
     * Activity, without which the system silently swallows the install sheet.
     */
    fun install() {
        val ready = _state.value as? UpdateState.Ready ?: return
        val staged = staging.complete()
        if (staged == null || staged.identity.size != ready.identity.size) {
            _state.value = UpdateState.Failed("the staged update is no longer on disk", resumable = false)
            return
        }
        _state.value = UpdateState.Installing
        notifications.cancelUpdateNotifications()
        scope.launch {
            try {
                installer.install(staged.apk)
            } catch (cancelled: CancellationException) {
                // Never leave the row stuck on "Installing…" for a sheet that
                // will now never appear.
                _state.value = UpdateState.Ready(staged.identity)
                throw cancelled
            } catch (failure: Exception) {
                // The bytes are still good; only the commit failed, so keep
                // them and let the user try again for free.
                _state.value = UpdateState.Ready(staged.identity, failure.message ?: "install failed")
            }
        }
    }

    /** Called when the install session reports a definitive result. */
    fun onInstallOutcome(outcome: ApkInstallOutcome) {
        when (outcome) {
            is ApkInstallOutcome.Success -> {
                staging.discard()
                _state.value = UpdateState.Idle
                notifications.cancelUpdateNotifications()
            }
            is ApkInstallOutcome.Failed -> {
                // Declined or failed: the staged APK is kept, so this goes back
                // to Ready rather than Failed — the button must stay "Install
                // now" instead of offering another build and download.
                val staged = staging.complete()
                _state.value = if (staged != null) {
                    UpdateState.Ready(staged.identity, outcome.message)
                } else {
                    UpdateState.Failed(outcome.message, resumable = false)
                }
                notifications.showUpdateFailed(outcome.message, resumable = false)
            }
        }
    }

    private fun canStart(): Boolean = when (_state.value) {
        is UpdateState.Idle, is UpdateState.Failed -> true
        // Exactly one update job at a time. A second tap is a no-op rather than
        // a queued build; the host coalesces duplicate builds too.
        else -> false
    }

    private fun run(api: ScoutrApi, binding: HostConnectionBinding) {
        generation += 1
        val mine = generation
        val previous = job
        _state.value = UpdateState.Building
        job = scope.launch {
            // The generation guard keeps a superseded run from publishing
            // state, but staging is a directory on disk that only one run may
            // own. A cancelled run unwinds asynchronously and its last steps —
            // the checksum and its discard-on-mismatch — are not suspension
            // points, so it can still touch those files. Waiting it out is what
            // makes "exactly one update job" true of the bytes, not just the state.
            previous?.join()
            try {
                // Tracked so forgetting, re-pairing, or refreshing the host
                // cancels the transfer: never download with a retired token,
                // never stage an APK from a host that is no longer trusted.
                work.track(binding) {
                    val staged = AppUpdater(api = api, staging = staging, pollDelayMs = pollDelayMs)
                        .stage { progress ->
                            if (generation != mine) return@stage
                            _state.value = when (progress) {
                                is UpdateProgress.Building -> UpdateState.Building
                                is UpdateProgress.Downloading ->
                                    UpdateState.Downloading(progress.bytes, progress.total)
                            }
                        }
                    if (generation == mine) finish(staged)
                }
            } catch (cancelled: CancellationException) {
                // A newer run already owns the staging dir and the state; this
                // one is a ghost and must touch neither.
                if (generation == mine) {
                    // Explicit cancel is "not now" and keeps the expensive
                    // bytes. Anything else cancelling a tracked job is host
                    // retirement — forget, re-pair, replaceIdentity — and those
                    // bytes came from a host no longer trusted to update this app.
                    if (userCancelledGeneration != mine) staging.discard()
                    _state.value = UpdateState.Idle
                    notifications.cancelUpdateNotifications()
                }
                throw cancelled
            } catch (failure: Exception) {
                // Dropped rather than rethrown: applicationScope has no
                // exception handler, so rethrowing a superseded run's failure
                // would take the process down.
                if (generation != mine) return@launch
                val message = failure.message ?: "update failed"
                // Only a dropped transfer leaves bytes worth resuming; a build
                // that never produced an APK has nothing to continue from.
                val resumable = staging.partialBytes() > 0 && staging.identity() != null
                _state.value = UpdateState.Failed(message, resumable)
                notifications.showUpdateFailed(message, resumable)
            }
        }
    }

    /**
     * Decides what a completed download becomes. Committing straight to the
     * system sheet is only right when the user is looking at the update screen;
     * anywhere else — a chat being typed into, a live terminal, or no app at
     * all — it would be an interruption, so the shade carries the news instead.
     */
    private fun finish(staged: StagedUpdate) {
        // These bytes came from a live host poll, so a versionCode of 0 (an
        // old bridge that never sent one) is tolerated — but a build that is
        // provably not newer than the installed app can never be committed
        // and must not enter Ready, or it wedges the flow the same way a
        // stale rehydrate would.
        if (staged.identity.versionCode in 1..installedVersionCode) {
            staging.discard()
            val message = "the host built an APK older than the installed app"
            _state.value = UpdateState.Failed(message, resumable = false)
            notifications.showUpdateFailed(message, resumable = false)
            return
        }
        if (updateScreenVisible) {
            _state.value = UpdateState.Ready(staged.identity)
            install()
        } else {
            _state.value = UpdateState.Ready(staged.identity)
            notifications.showUpdateReady(staged.identity)
        }
    }
}
