package dev.scoutr.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.scoutr.app.ScoutrApp
import dev.scoutr.app.data.HostPaneKey
import dev.scoutr.app.data.encode

/** Mutes exactly one generation-qualified host pane and clears its slot. */
class NotificationMuteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val paneId = intent.getStringExtra(EXTRA_PANE_ID)?.takeIf { it.isNotBlank() } ?: return
        var key = hostPaneKeyFromIntent(intent)
        if (key == null) {
            // Recover an old shade action only for the one marked migration
            // profile; never reinterpret it after same-id re-pair.
            val app = context.applicationContext as? ScoutrApp
            val legacyProfile = app?.container?.hostRegistry?.let { registry ->
                val state = registry.snapshot()
                state.profiles.singleOrNull()?.takeIf {
                    state.legacyLinkGeneration == it.profileGeneration
                }?.let { dev.scoutr.app.data.HostProfileKey(it.hostId, it.profileGeneration) }
            }
            if (legacyProfile != null) key = HostPaneKey(legacyProfile, paneId)
        }
        if (key == null) return
        val container = ScoutrApp.container(context)
        val profile = currentHostProfile(
            container.hostRegistry,
            key.profile,
            container.pushRegistrations::isRetiring,
        )
        val binding = profile?.let { container.currentHostBinding(key.profile.hostId) }
        if (profile == null || binding == null) {
            // A stale shade action must not affect a re-paired profile, but its
            // old notification can safely be removed by its complete tag.
            container.notifications.cancel(key)
            return
        }
        container.hostWorkCoordinator.withActive(binding) {
            val current = currentHostProfile(
                container.hostRegistry,
                key.profile,
                container.pushRegistrations::isRetiring,
            )
            if (current == null || current.connectionRevision != profile.connectionRevision) {
                container.notifications.cancel(key)
                return@withActive
            }
            container.muteStore.mute(key)
            container.notifications.cancel(key)
        }
    }

    companion object {
        const val EXTRA_PANE_ID = "scoutr.paneId"
        const val EXTRA_HOST_ID = "scoutr.hostId"
        const val EXTRA_PROFILE_GENERATION = "scoutr.profileGeneration"
        const val EXTRA_HOST_PROFILE_KEY = "scoutr.hostProfileKey"
        private const val ACTION_KIND = "mute"

        fun muteAction(context: Context, paneId: String): NotificationCompat.Action {
            val intent = Intent(context, NotificationMuteReceiver::class.java)
                .putExtra(EXTRA_PANE_ID, paneId)
            val pending = PendingIntent.getBroadcast(
                context,
                paneId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return action(pending)
        }

        fun muteAction(
            context: Context,
            profile: dev.scoutr.app.data.HostProfileKey,
            paneId: String,
        ): NotificationCompat.Action = muteAction(context, HostPaneKey(profile, paneId))

        fun muteAction(
            context: Context,
            hostId: String,
            profileGeneration: Long,
            paneId: String,
        ): NotificationCompat.Action = muteAction(
            context,
            HostPaneKey(dev.scoutr.app.data.HostProfileKey(hostId, profileGeneration), paneId),
        )

        fun muteAction(context: Context, key: HostPaneKey): NotificationCompat.Action {
            val intent = Intent(context, NotificationMuteReceiver::class.java)
                .putHostPaneIdentity(key, ACTION_KIND)
                .apply { data = actionUri(key, ACTION_KIND) }
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode(key, ACTION_KIND),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return action(pending)
        }

        private fun action(pending: PendingIntent): NotificationCompat.Action =
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_lock_silent_mode,
                "Mute this agent",
                pending,
            ).build()

        private fun requestCode(key: HostPaneKey, kind: String): Int =
            key.encode().hashCode() xor kind.hashCode()

        private fun actionUri(key: HostPaneKey, kind: String): Uri =
            Uri.parse("scoutr://notification/$kind/${Uri.encode(key.encode())}")
    }
}
