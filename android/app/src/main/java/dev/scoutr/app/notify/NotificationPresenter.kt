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
import dev.scoutr.app.data.NotificationPreferencesStore
import dev.scoutr.app.data.RepoSummary
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
 * - **One slot per pane**, keyed `paneId.hashCode()` for blocked and
 *   `paneId.hashCode() xor DONE_SLOT_XOR` for done. A pane that blocks twice
 *   updates its own notification instead of stacking a second one, and the
 *   resolve ping can cancel it by pane id alone.
 * - **Two channels**, `needs_you` (blocked) and `agent_done` (done).
 *   Blocked is high-priority “needs your input”; done is high-priority
 *   “finished” and is gated by its own in-app toggle.
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
    private val preferencesStore: NotificationPreferencesStore = NotificationPreferencesStore(context),
) {

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** A blocked agent, named, with the repo state you'd be walking into. */
    fun showBlocked(session: SessionDescriptor) {
        if (!preferencesStore.blockedEnabled) return
        val paneId = session.live?.paneId ?: return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        postBlocked(paneId, title, bodyWithBranch("Needs your input", session.liveSummary))
    }

    /** A finished agent, named, with where its work sits. */
    fun showDone(session: SessionDescriptor) {
        if (!preferencesStore.doneEnabled) return
        val paneId = session.live?.paneId ?: return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        postDone(paneId, title, bodyWithBranch("Finished", session.doneSummary))
    }

    /**
     * Appends deterministic git facts to a notification body in the Board
     * card's own grammar: bare branch, then "uncommitted" when dirty. A
     * missing or branchless summary leaves the body alone — no invented
     * facts, and degraded notifications never gain context they don't have.
     */
    private fun bodyWithBranch(base: String, summary: RepoSummary?): String =
        if (summary == null) {
            base
        } else {
            listOfNotNull(
                base,
                summary.branch,
                if (summary.dirty) "uncommitted" else null,
            ).joinToString(" · ")
        }

    /**
     * A blocked agent whose identity could not be fetched. Silence is the
     * failure mode being eliminated, so the user still learns something
     * happened and tapping opens the app, which retries.
     */
    fun showDegraded(paneId: String) {
        if (!preferencesStore.blockedEnabled) return
        postBlocked(paneId, "An agent needs you", "Tap to open Scoutr")
    }

    /** A finished agent whose identity could not be fetched. */
    fun showDegradedDone(paneId: String) {
        if (!preferencesStore.doneEnabled) return
        postDone(paneId, "An agent finished", "Tap to open Scoutr")
    }

    fun cancel(paneId: String) {
        manager.cancel(slotOf(paneId))
        syncBlockedSummary()
    }

    fun cancelDone(paneId: String) {
        manager.cancel(doneSlotOf(paneId))
        syncDoneSummary()
    }

    /**
     * Clear every blocked slot whose pane is no longer blocked. This is the backstop
     * for a resolve ping that never arrived — a dropped push must not leave a
     * notification the user cannot get rid of.
     */
    fun cancelAllExcept(livePaneIds: Set<String>) {
        val keep = livePaneIds.map(::slotOf).toSet()
        for (slot in activeBlockedSlots()) {
            if (slot !in keep) manager.cancel(slot)
        }
        syncBlockedSummary()
    }

    /** Auto-clear for done: drops every finished notification on foreground entry. */
    fun cancelAllDone() {
        for (slot in activeDoneSlots()) manager.cancel(slot)
        syncDoneSummary()
    }

    private fun postBlocked(paneId: String, title: String, body: String) {
        if (muteStore.isMuted(paneId)) return
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_NEEDS_YOU)
            .setSmallIcon(R.drawable.ic_scoutr_notification)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(openPaneIntent(paneId, BLOCKED))
            .addAction(NotificationReplyReceiver.replyAction(context, paneId))
            .addAction(NotificationMuteReceiver.muteAction(context, paneId))
            .build()
        manager.notify(slotOf(paneId), notification)
        syncBlockedSummary()
    }

    private fun postDone(paneId: String, title: String, body: String) {
        if (muteStore.isMuted(paneId)) return
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_scoutr_notification)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_DONE)
            .setContentIntent(openPaneIntent(paneId, DONE))
            .build()
        manager.notify(doneSlotOf(paneId), notification)
        syncDoneSummary()
    }
    private fun openPaneIntent(paneId: String, status: String): PendingIntent {
        val link = resolveNotificationLink(scoutrChatUri(paneId, status), paneId, status)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            link?.let { data = Uri.parse(it.uri) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val requestCode = if (status == DONE) doneSlotOf(paneId) else slotOf(paneId)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * A group with one child renders as a stray "1 notification" header, so
     * the summary exists only from two agents up.
     */
    private fun syncBlockedSummary() {
        syncGroupSummary(
            count = activeBlockedSlots().size,
            summaryId = SUMMARY_ID,
            channel = CHANNEL_NEEDS_YOU,
            group = GROUP_KEY,
            title = "Agents need you",
        )
    }

    private fun syncDoneSummary() {
        syncGroupSummary(
            count = activeDoneSlots().size,
            summaryId = DONE_SUMMARY_ID,
            channel = CHANNEL_DONE,
            group = GROUP_DONE,
            title = "Agents finished",
        )
    }

    private fun syncGroupSummary(count: Int, summaryId: Int, channel: String, group: String, title: String) {
        if (count >= 2) {
            manager.notify(
                summaryId,
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_scoutr_notification)
                    .setColor(ACCENT)
                    .setContentTitle(title)
                    .setGroup(group)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
        } else {
            manager.cancel(summaryId)
        }
    }

    private fun activeBlockedSlots(): List<Int> =
        manager.activeNotifications
            .filter { it.id != SUMMARY_ID && it.id != DONE_SUMMARY_ID && it.notification.group == GROUP_KEY }
            .map { it.id }

    private fun activeDoneSlots(): List<Int> =
        manager.activeNotifications
            .filter { it.id != SUMMARY_ID && it.id != DONE_SUMMARY_ID && it.notification.group == GROUP_DONE }
            .map { it.id }

    /**
     * Created lazily rather than in the container's init, because a
     * push-woken process may never build the UI half of the app. Upgrading
     * installs still carry the two channels this replaced, so they are
     * removed here — otherwise the user keeps two dead toggles in system
     * settings forever.
     */
    private fun ensureChannels() {
        manager.deleteNotificationChannel(LEGACY_CHANNEL_AGENTS)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_MONITOR)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NEEDS_YOU, "Needs you", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "An agent is waiting for your input" },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "An agent finished" },
        )
    }

    @Suppress("DEPRECATION")
    private fun ensureChannel() = ensureChannels()

    private fun slotOf(paneId: String): Int = paneId.hashCode()

    private fun doneSlotOf(paneId: String): Int = paneId.hashCode() xor DONE_SLOT_XOR

    companion object {
        const val CHANNEL_NEEDS_YOU = "needs_you"
        const val CHANNEL_DONE = "agent_done"
        const val GROUP_KEY = "dev.scoutr.app.NEEDS_YOU"
        const val GROUP_DONE = "dev.scoutr.app.AGENT_DONE"

        /** A fixed id outside the range a pane hash realistically lands on. */
        const val SUMMARY_ID = 0x5C0F7A
        const val DONE_SUMMARY_ID = 0x5C0F7B

        private const val BLOCKED = "blocked"
        private const val DONE = "done"

        /** `error` from the design system: needs-you red. */
        private const val ACCENT = 0xFFE5484D.toInt()

        private const val DONE_SLOT_XOR = 0x40000000.toInt()

        private const val LEGACY_CHANNEL_AGENTS = "agents"
        private const val LEGACY_CHANNEL_MONITOR = "scoutr_monitor"
    }
}
