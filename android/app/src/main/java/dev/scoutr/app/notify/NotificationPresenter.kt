package dev.scoutr.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.scoutr.app.MainActivity
import dev.scoutr.app.R
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.service.NotificationMuteReceiver
import dev.scoutr.app.service.NotificationReplyReceiver
import dev.scoutr.app.service.resolveNotificationLink
import dev.scoutr.app.service.scoutrChatUri
import dev.scoutr.app.state.MuteStore

/**
 * The one place Scoutr posts, updates, and clears notifications.
 *
 * Presentation used to be split between the monitor service and the app
 * container, which drifted into two builders that disagreed on channel, icon,
 * and id. Everything now goes through here, which is what makes the
 * invariants below true rather than merely intended:
 *
 * - **One slot per pane**, keyed `paneId.hashCode()`. A pane that blocks twice
 *   updates its own notification instead of stacking a second one, and the
 *   resolve ping can cancel it by pane id alone.
 * - **One channel**, `needs_you`. A blocked agent is the only thing Scoutr
 *   interrupts for.
 * - **Deep links are built, never received.** The content intent comes from
 *   [scoutrChatUri] through [resolveNotificationLink], so no payload string
 *   ever reaches the launcher unvalidated.
 *
 * The posted notifications themselves are the state: [NotificationManager]
 * survives process death, an in-memory slot map would not, and the FCM path
 * runs in a process the system starts and stops at will.
 */
class NotificationPresenter(
    private val context: Context,
    private val muteStore: MuteStore = MuteStore(context),
) {

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** A blocked agent, named. */
    fun showBlocked(session: SessionDescriptor) {
        val paneId = session.live?.paneId ?: return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        post(paneId, title, "Needs your input")
    }

    /**
     * A blocked agent whose identity could not be fetched. Silence is the
     * failure mode being eliminated, so the user still learns something
     * happened and tapping opens the app, which retries.
     */
    fun showDegraded(paneId: String) {
        post(paneId, "An agent needs you", "Tap to open Scoutr")
    }

    fun cancel(paneId: String) {
        manager.cancel(slotOf(paneId))
        syncSummary()
    }

    /**
     * Clear every slot whose pane is no longer blocked. This is the backstop
     * for a resolve ping that never arrived — a dropped push must not leave a
     * notification the user cannot get rid of.
     */
    fun cancelAllExcept(livePaneIds: Set<String>) {
        val keep = livePaneIds.map(::slotOf).toSet()
        for (slot in activeSlots()) {
            if (slot !in keep) manager.cancel(slot)
        }
        syncSummary()
    }

    private fun post(paneId: String, title: String, body: String) {
        // The mute is checked here rather than at each call site so no future
        // caller can post around it.
        if (muteStore.isMuted(paneId)) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_NEEDS_YOU)
            .setSmallIcon(R.drawable.ic_scoutr_notification)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // A re-post of a live slot is an update, not a new interruption.
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(openPaneIntent(paneId))
            .addAction(NotificationReplyReceiver.replyAction(context, paneId))
            .addAction(NotificationMuteReceiver.muteAction(context, paneId))
            .build()
        manager.notify(slotOf(paneId), notification)
        syncSummary()
    }

    private fun openPaneIntent(paneId: String): PendingIntent {
        val link = resolveNotificationLink(scoutrChatUri(paneId, BLOCKED), paneId, BLOCKED)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            link?.let { data = Uri.parse(it.uri) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            paneId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * A group with one child renders as a stray "1 notification" header, so
     * the summary exists only from two blocked agents up.
     */
    private fun syncSummary() {
        if (activeSlots().size >= 2) {
            manager.notify(
                SUMMARY_ID,
                NotificationCompat.Builder(context, CHANNEL_NEEDS_YOU)
                    .setSmallIcon(R.drawable.ic_scoutr_notification)
                    .setColor(ACCENT)
                    .setContentTitle("Agents need you")
                    .setGroup(GROUP_KEY)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
        } else {
            manager.cancel(SUMMARY_ID)
        }
    }

    /** Ids of the posted pane slots, excluding the group summary. */
    private fun activeSlots(): List<Int> =
        manager.activeNotifications.map { it.id }.filter { it != SUMMARY_ID }

    /**
     * Created lazily rather than in the container's init, because a
     * push-woken process may never build the UI half of the app. Upgrading
     * installs still carry the two channels this replaced, so they are
     * removed here — otherwise the user keeps two dead toggles in system
     * settings forever.
     */
    private fun ensureChannel() {
        manager.deleteNotificationChannel(LEGACY_CHANNEL_AGENTS)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_MONITOR)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NEEDS_YOU, "Needs you", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "An agent is waiting for your input" },
        )
    }

    private fun slotOf(paneId: String): Int = paneId.hashCode()

    companion object {
        const val CHANNEL_NEEDS_YOU = "needs_you"
        const val GROUP_KEY = "dev.scoutr.app.NEEDS_YOU"

        /** A fixed id outside the range a pane hash realistically lands on. */
        const val SUMMARY_ID = 0x5C0F7A

        private const val BLOCKED = "blocked"

        /** `error` from the design system: needs-you red. */
        private const val ACCENT = 0xFFE5484D.toInt()

        private const val LEGACY_CHANNEL_AGENTS = "agents"
        private const val LEGACY_CHANNEL_MONITOR = "scoutr_monitor"
    }
}
