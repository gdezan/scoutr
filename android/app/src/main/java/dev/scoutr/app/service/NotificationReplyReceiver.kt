package dev.scoutr.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.RemoteInput
import androidx.core.app.NotificationCompat
import dev.scoutr.app.MainActivity
import dev.scoutr.app.ScoutrApp
import dev.scoutr.app.data.HostPaneKey
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.encode
import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the inline Reply action. A host-qualified action must still resolve
 * to the same current profile generation when the user taps it; a stale tap
 * only cancels its old notification and never steers another host.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val paneId = intent.getStringExtra(EXTRA_PANE_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)
            ?.toString()
            ?.trim()
        if (text.isNullOrBlank()) return

        var key = hostPaneKeyFromIntent(intent)
        if (key == null) {
            // Already-posted pre-migration actions may be recovered only while
            // the one-profile legacy marker still names the current profile.
            val app = context.applicationContext as? ScoutrApp
            if (app != null) {
                val state = app.container.hostRegistry.snapshot()
                val legacyProfile = state.profiles.singleOrNull()?.takeIf {
                    state.legacyLinkGeneration == it.profileGeneration
                }?.let { HostProfileKey(it.hostId, it.profileGeneration) }
                if (legacyProfile != null && !app.container.pushRegistrations.isRetiring(legacyProfile.hostId)) {
                    key = HostPaneKey(legacyProfile, paneId)
                }
            }
        }
        if (key == null) {
            // The injectable legacy provider exists for old JVM tests only and
            // is never the default production path.
            if (bridgeProvider === productionBridgeProvider) return
            dispatchLegacy(context, paneId, text)
            return
        }

        val container = ScoutrApp.container(context)
        val profile = currentHostProfile(
            container.hostRegistry,
            key.profile,
            container.pushRegistrations::isRetiring,
        )
        if (profile == null) {
            container.notifications.cancel(key)
            return
        }

        val capturedRevision = profile.connectionRevision
        val binding = container.currentHostBinding(key.profile.hostId)
        if (binding == null) {
            container.notifications.cancel(key)
            return
        }
        val result = goAsync()
        pendingResultSignal?.complete(result)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                container.hostWorkCoordinator.trackIfActive(binding) {
                    val current = currentHostProfile(
                        container.hostRegistry,
                        key.profile,
                        container.pushRegistrations::isRetiring,
                    )
                    if (current == null || current.connectionRevision != capturedRevision) {
                        container.notifications.cancel(key)
                        return@trackIfActive
                    }
                    hostBindingApiProvider(context, binding).steer(paneId, text)
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                android.util.Log.w(TAG, "notification reply to $paneId failed", e)
            } finally {
                result.finish()
            }
        }
    }

    private fun dispatchLegacy(context: Context, paneId: String, text: String) {
        val result = goAsync()
        pendingResultSignal?.complete(result)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                bridgeProvider(context).steer(paneId, text)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                android.util.Log.w(TAG, "legacy notification reply to $paneId failed", e)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PANE_ID = "scoutr.paneId"
        const val EXTRA_HOST_ID = "scoutr.hostId"
        const val EXTRA_PROFILE_GENERATION = "scoutr.profileGeneration"
        const val EXTRA_HOST_PROFILE_KEY = "scoutr.hostProfileKey"
        const val KEY_REPLY = "reply"
        private const val ACTION_KIND = "reply"
        private const val TAG = "ScoutrReply"

        /**
         * Legacy test seam. It deliberately does not resolve a default host;
         * the production value is a no-op and host-aware actions use
         * [hostApiProvider] instead.
         */
        private val productionBridgeProvider: (Context) -> ScoutrApi = {
            throw IllegalStateException("Unqualified notification reply")
        }

        internal var bridgeProvider: (Context) -> ScoutrApi = productionBridgeProvider

        private val defaultHostApiProvider: (Context, String) -> ScoutrApi =
            { context, hostId -> ScoutrApp.container(context).hostClients.api(hostId) }

        /** Legacy seam remains usable by tests; production binding calls stay revision-fixed. */
        internal var hostApiProvider: (Context, String) -> ScoutrApi = defaultHostApiProvider

        internal var hostBindingApiProvider: (Context, HostConnectionBinding) -> ScoutrApi =
            { context, binding ->
                if (hostApiProvider === defaultHostApiProvider) {
                    ScoutrApp.container(context).hostClients.api(binding)
                } else {
                    hostApiProvider(context, binding.hostId)
                }
            }

        internal var pendingResultSignal:
            kotlinx.coroutines.CompletableDeferred<android.content.BroadcastReceiver.PendingResult>? = null

        fun replyAction(context: Context, paneId: String): NotificationCompat.Action {
            val intent = Intent(context, NotificationReplyReceiver::class.java)
                .putExtra(EXTRA_PANE_ID, paneId)
            val pending = PendingIntent.getBroadcast(
                context,
                paneId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            return action(pending)
        }

        fun replyAction(
            context: Context,
            profile: HostProfileKey,
            paneId: String,
        ): NotificationCompat.Action = replyAction(context, HostPaneKey(profile, paneId))

        fun replyAction(
            context: Context,
            hostId: String,
            profileGeneration: Long,
            paneId: String,
        ): NotificationCompat.Action = replyAction(
            context,
            HostPaneKey(HostProfileKey(hostId, profileGeneration), paneId),
        )

        fun replyAction(context: Context, key: HostPaneKey): NotificationCompat.Action {
            val identity = Intent(context, NotificationReplyReceiver::class.java)
                .putHostPaneIdentity(key, ACTION_KIND)
                .apply { data = actionUri(key, ACTION_KIND) }
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode(key, ACTION_KIND),
                identity,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            return action(pending)
        }

        private fun action(pending: PendingIntent): NotificationCompat.Action =
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                "Reply",
                pending,
            ).addRemoteInput(
                RemoteInput.Builder(KEY_REPLY).setLabel("Steer the agent…").build(),
            ).build()

        private fun requestCode(key: HostPaneKey, kind: String): Int =
            key.encode().hashCode() xor kind.hashCode()

        private fun actionUri(key: HostPaneKey, kind: String): Uri =
            Uri.parse("scoutr://notification/$kind/${Uri.encode(key.encode())}")
    }
}
