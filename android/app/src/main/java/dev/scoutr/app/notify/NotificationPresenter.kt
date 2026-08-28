package dev.scoutr.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.scoutr.app.MainActivity
import dev.scoutr.app.R
import dev.scoutr.app.data.HostPaneKey
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.NotificationPreferencesStore
import dev.scoutr.app.data.RepoSummary
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.encode
import dev.scoutr.app.service.NotificationMuteReceiver
import dev.scoutr.app.service.NotificationReplyReceiver
import dev.scoutr.app.service.putHostPaneIdentity
import dev.scoutr.app.service.resolveNotificationLink
import dev.scoutr.app.service.scoutrChatUri
import dev.scoutr.app.state.MuteStore
import dev.scoutr.app.update.PendingUpdateAction
import dev.scoutr.app.update.StagedIdentity
import dev.scoutr.app.update.UpdateActionReceiver
import dev.scoutr.app.update.UpdateNotifier
import dev.scoutr.app.update.UpdateState

/**
 * The one place Scoutr posts, updates, and clears notifications.
 *
 * Host-aware notifications use a tag containing host id, profile generation,
 * and pane id. Their numeric id is also derived from that full identity, and
 * every PendingIntent carries the same identity. The old pane-only overloads
 * remain for pre-host tests and migration compatibility only.
 */
class NotificationPresenter(
    private val context: Context,
    private val muteStore: MuteStore = MuteStore(context),
    private val preferencesStore: NotificationPreferencesStore = NotificationPreferencesStore(context),
) : UpdateNotifier {

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun showBlocked(session: SessionDescriptor) {
        val paneId = session.live?.paneId ?: return
        showBlockedLegacy(paneId, session)
    }

    fun showBlocked(key: HostPaneKey, session: SessionDescriptor) {
        if (session.live?.paneId != key.paneId) return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        postBlocked(key, title, bodyWithBranch("Needs your input", session.liveSummary))
    }

    fun showBlocked(profile: HostProfileKey, session: SessionDescriptor) {
        val paneId = session.live?.paneId ?: return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        postBlocked(
            HostPaneKey(profile, paneId),
            title,
            bodyWithBranch("Needs your input", session.liveSummary),
        )
    }

    fun showDone(session: SessionDescriptor) {
        val paneId = session.live?.paneId ?: return
        showDoneLegacy(paneId, session)
    }

    fun showDone(key: HostPaneKey, session: SessionDescriptor) {
        if (session.live?.paneId != key.paneId) return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        postDone(key, title, bodyWithBranch("Finished", session.doneSummary))
    }

    fun showDone(profile: HostProfileKey, session: SessionDescriptor) {
        val paneId = session.live?.paneId ?: return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        postDone(
            HostPaneKey(profile, paneId),
            title,
            bodyWithBranch("Finished", session.doneSummary),
        )
    }

    /**
     * The conversation stopped on a failed model call (repeated 5xx and the
     * agent gave up). Same attention class as blocked — the agent cannot
     * proceed without the user — so it shares the needs-you slot, channel,
     * preference, and Reply/Mute actions; only the text differs. Replying is
     * the natural recovery: it steers the pane, e.g. "continue".
     */
    fun showErrored(session: SessionDescriptor) {
        val paneId = session.live?.paneId ?: return
        showErroredLegacy(paneId, session)
    }

    fun showErrored(profile: HostProfileKey, session: SessionDescriptor) {
        val paneId = session.live?.paneId ?: return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        postErrored(
            HostPaneKey(profile, paneId),
            title,
            bodyWithBranch("Stopped on an error", session.liveSummary),
        )
    }

    /** Degraded notifications still retain the host-qualified destination. */
    fun showDegraded(paneId: String) {
        if (!preferencesStore.blockedEnabled) return
        postBlockedLegacy(paneId, "An agent needs you", "Tap to open Scoutr")
    }

    fun showDegraded(key: HostPaneKey) = showDegraded(key.profile, key.paneId)

    fun showDegraded(profile: HostProfileKey, paneId: String) {
        if (!preferencesStore.blockedEnabled) return
        postBlocked(HostPaneKey(profile, paneId), "An agent needs you", "Tap to open Scoutr")
    }

    fun showDegradedDone(paneId: String) {
        if (!preferencesStore.doneEnabled) return
        postDoneLegacy(paneId, "An agent finished", "Tap to open Scoutr")
    }

    fun showDegradedDone(key: HostPaneKey) = showDegradedDone(key.profile, key.paneId)

    fun showDegradedDone(profile: HostProfileKey, paneId: String) {
        if (!preferencesStore.doneEnabled) return
        postDone(HostPaneKey(profile, paneId), "An agent finished", "Tap to open Scoutr")
    }

    /** Fetch failed after retries; still a real error stop, just unnamed. */
    fun showDegradedErrored(paneId: String) {
        if (!preferencesStore.blockedEnabled) return
        postErroredLegacy(paneId, "An agent stopped", "Tap to open Scoutr")
    }

    fun showDegradedErrored(profile: HostProfileKey, paneId: String) {
        if (!preferencesStore.blockedEnabled) return
        postErrored(HostPaneKey(profile, paneId), "An agent stopped", "Tap to open Scoutr")
    }

    /** Cancels both status slots for one generation-qualified pane. */
    fun cancel(key: HostPaneKey) {
        manager.cancel(tagOf(key), slotOf(key))
        manager.cancel(tagOf(key), doneSlotOf(key))
        syncBlockedSummary()
        syncDoneSummary()
    }

    fun cancel(profile: HostProfileKey, paneId: String) = cancel(HostPaneKey(profile, paneId))

    /** Compatibility pane-only cancellation. */
    fun cancel(paneId: String) {
        manager.cancel(slotOf(paneId))
        syncBlockedSummary()
    }

    /** Cancels every generation and pane belonging to one host id. */
    fun cancelHost(hostId: String) {
        for (entry in manager.activeNotifications) {
            if (entry.tag?.startsWith(hostTagPrefix(hostId)) == true) {
                manager.cancel(entry.tag, entry.id)
            }
        }
        syncBlockedSummary()
        syncDoneSummary()
    }

    fun cancelHost(hostId: String, profileGeneration: Long) =
        cancelHost(HostProfileKey(hostId, profileGeneration))

    fun cancelHost(profile: HostProfileKey) {
        val prefix = profileTagPrefix(profile)
        for (entry in manager.activeNotifications) {
            if (entry.tag?.startsWith(prefix) == true) {
                manager.cancel(entry.tag, entry.id)
            }
        }
        syncBlockedSummary()
        syncDoneSummary()
    }

    /**
     * Clears only blocked notifications for one current profile generation.
     * Notifications belonging to another host/generation are untouched.
     */
    fun cancelAllExcept(profile: HostProfileKey, livePaneIds: Set<String>) {
        val keep = livePaneIds.map { tagOf(HostPaneKey(profile, it)) }.toSet()
        for (entry in activeEntries(GROUP_KEY)) {
            if (entry.tag?.startsWith(profileTagPrefix(profile)) == true && entry.tag !in keep) {
                manager.cancel(entry.tag, entry.id)
            }
        }
        syncBlockedSummary()
    }

    fun cancelAllExcept(hostId: String, profileGeneration: Long, livePaneIds: Set<String>) =
        cancelAllExcept(HostProfileKey(hostId, profileGeneration), livePaneIds)

    /** Compatibility host-scoped overload used while the shell has no generation. */
    fun cancelAllExcept(livePaneIds: Set<String>) {
        val keep = livePaneIds.map(::slotOf).toSet()
        for (entry in activeEntries(GROUP_KEY)) {
            if (entry.tag == null && entry.id !in keep) manager.cancel(entry.id)
        }
        syncBlockedSummary()
    }

    fun cancelAllExcept(hostId: String, livePaneIds: Set<String>) {
        for (entry in activeEntries(GROUP_KEY)) {
            val tag = entry.tag ?: continue
            if (tag.startsWith(hostTagPrefix(hostId)) &&
                livePaneIds.none { tag.endsWith(".${encodeTagPart(it)}") }
            ) {
                manager.cancel(tag, entry.id)
            }
        }
        syncBlockedSummary()
    }

    /** Auto-clear for done notifications on foreground entry. */
    fun cancelAllDone() {
        for (entry in activeEntries(GROUP_DONE)) manager.cancel(entry.tag, entry.id)
        syncDoneSummary()
    }

    fun cancelAllDone(profile: HostProfileKey) {
        for (entry in activeEntries(GROUP_DONE)) {
            if (entry.tag?.startsWith(profileTagPrefix(profile)) == true) {
                manager.cancel(entry.tag, entry.id)
            }
        }
        syncDoneSummary()
    }

    fun cancelAllDone(hostId: String, profileGeneration: Long) =
        cancelAllDone(HostProfileKey(hostId, profileGeneration))

    /** Removes unqualified notifications posted by the singleton app version. */
    fun cancelLegacy() {
        for (entry in manager.activeNotifications) {
            if (entry.tag == null) manager.cancel(entry.id)
        }
        syncBlockedSummary()
        syncDoneSummary()
    }

    /** Used by host cleanup; unlike [cancelHost], also clears old singleton slots. */
    fun cancelAll() {
        manager.cancelAll()
    }

    private fun showBlockedLegacy(paneId: String, session: SessionDescriptor) {
        if (!preferencesStore.blockedEnabled) return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        postBlockedLegacy(paneId, title, bodyWithBranch("Needs your input", session.liveSummary))
    }

    private fun showDoneLegacy(paneId: String, session: SessionDescriptor) {
        if (!preferencesStore.doneEnabled) return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        postDoneLegacy(paneId, title, bodyWithBranch("Finished", session.doneSummary))
    }

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

    private fun postBlocked(key: HostPaneKey, title: String, body: String) {
        if (!preferencesStore.blockedEnabled) return
        if (muteStore.isMuted(key)) return
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
            .setContentIntent(openPaneIntent(key, BLOCKED))
            .addAction(NotificationReplyReceiver.replyAction(context, key))
            .addAction(NotificationMuteReceiver.muteAction(context, key))
            .build()
        manager.notify(tagOf(key), slotOf(key), notification)
        syncBlockedSummary()
    }

    /** Shares the blocked slot: one pane cannot be both, and latest wins. */
    private fun postErrored(key: HostPaneKey, title: String, body: String) {
        if (!preferencesStore.blockedEnabled) return
        if (muteStore.isMuted(key)) return
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
            .setContentIntent(openPaneIntent(key, ERRORED))
            .addAction(NotificationReplyReceiver.replyAction(context, key))
            .addAction(NotificationMuteReceiver.muteAction(context, key))
            .build()
        manager.notify(tagOf(key), slotOf(key), notification)
        syncBlockedSummary()
    }

    private fun showErroredLegacy(paneId: String, session: SessionDescriptor) {
        if (!preferencesStore.blockedEnabled) return
        val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val title = if (workspace.isEmpty()) session.displayName else "${session.displayName} · $workspace"
        postErroredLegacy(paneId, title, bodyWithBranch("Stopped on an error", session.liveSummary))
    }

    /** Legacy panes carry no profile key; same slot and actions as blocked. */
    private fun postErroredLegacy(paneId: String, title: String, body: String) {
        if (!preferencesStore.blockedEnabled) return
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
            .setContentIntent(openPaneIntentLegacy(paneId, ERRORED))
            .addAction(NotificationReplyReceiver.replyAction(context, paneId))
            .addAction(NotificationMuteReceiver.muteAction(context, paneId))
            .build()
        manager.notify(slotOf(paneId), notification)
        syncBlockedSummary()
    }

    private fun postDone(key: HostPaneKey, title: String, body: String) {
        if (!preferencesStore.doneEnabled) return
        if (muteStore.isMuted(key)) return
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
            .setContentIntent(openPaneIntent(key, DONE))
            .build()
        manager.notify(tagOf(key), doneSlotOf(key), notification)
        syncDoneSummary()
    }

    private fun postBlockedLegacy(paneId: String, title: String, body: String) {
        if (!preferencesStore.blockedEnabled) return
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
            .setContentIntent(openPaneIntentLegacy(paneId, BLOCKED))
            .addAction(NotificationReplyReceiver.replyAction(context, paneId))
            .addAction(NotificationMuteReceiver.muteAction(context, paneId))
            .build()
        manager.notify(slotOf(paneId), notification)
        syncBlockedSummary()
    }

    private fun postDoneLegacy(paneId: String, title: String, body: String) {
        if (!preferencesStore.doneEnabled) return
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
            .setContentIntent(openPaneIntentLegacy(paneId, DONE))
            .build()
        manager.notify(doneSlotOf(paneId), notification)
        syncDoneSummary()
    }

    private fun openPaneIntent(key: HostPaneKey, status: String): PendingIntent {
        val link = resolveNotificationLink(
            click = scoutrChatUri(key.profile, key.paneId, status),
            paneId = key.paneId,
            status = status,
            profile = key.profile,
        ) ?: error("Could not build a host-qualified notification destination")
        val intent = Intent(context, MainActivity::class.java)
            .putHostPaneIdentity(key, status)
            .apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(link.uri)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        return PendingIntent.getActivity(
            context,
            requestCode(key, status),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openPaneIntentLegacy(paneId: String, status: String): PendingIntent {
        val link = resolveNotificationLink(scoutrChatUri(paneId, status), paneId, status)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            link?.let { data = Uri.parse(it.uri) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            if (status == DONE) doneSlotOf(paneId) else slotOf(paneId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun syncBlockedSummary() {
        syncGroupSummary(
            count = activeEntries(GROUP_KEY).size,
            summaryId = SUMMARY_ID,
            channel = CHANNEL_NEEDS_YOU,
            group = GROUP_KEY,
            title = "Agents need you",
        )
    }

    private fun syncDoneSummary() {
        syncGroupSummary(
            count = activeEntries(GROUP_DONE).size,
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

    /**
     * The ongoing notification the update foreground service runs under.
     *
     * Not posted from here: a foreground service must hand its notification to
     * startForeground, or the system kills it for starting without one.
     */
    fun updateProgressNotification(state: UpdateState): Notification {
        ensureChannels()
        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATE_PROGRESS)
            .setSmallIcon(R.drawable.ic_scoutr_notification)
            .setColor(ACCENT)
            .setContentTitle("Updating Scoutr")
            .setOngoing(true)
            // The bar ticks constantly during a multi-megabyte download; each
            // tick must not re-alert.
            .setOnlyAlertOnce(true)
            .setContentIntent(updateContentIntent(null))
            .addAction(UpdateActionReceiver.cancelAction(context))
        when (state) {
            is UpdateState.Downloading -> {
                val percent = if (state.total > 0) (state.bytes * 100 / state.total).toInt() else 0
                builder.setContentText("Downloading… $percent%")
                builder.setProgress(100, percent, state.total <= 0)
            }
            else -> {
                // The host build reports no fraction, so the bar stays
                // indeterminate rather than inventing one.
                builder.setContentText("Building on host…")
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    /** The update finished downloading while the user was somewhere else. */
    override fun showUpdateReady(identity: StagedIdentity) {
        ensureChannels()
        manager.cancel(UPDATE_FAILED_ID)
        manager.notify(
            UPDATE_READY_ID,
            NotificationCompat.Builder(context, CHANNEL_UPDATE_READY)
                .setSmallIcon(R.drawable.ic_scoutr_notification)
                .setColor(ACCENT)
                .setContentTitle("Scoutr ${identity.version} is ready")
                .setContentText("Tap to install")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(updateContentIntent(PendingUpdateAction.Install))
                .build(),
        )
    }

    override fun showUpdateFailed(message: String, resumable: Boolean) {
        ensureChannels()
        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATE_READY)
            .setSmallIcon(R.drawable.ic_scoutr_notification)
            .setColor(ACCENT)
            .setContentTitle("Scoutr update failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(updateContentIntent(null))
        // Resume is only offered when there are bytes to continue from; a build
        // that never produced an APK has nothing to resume. It is an Activity
        // intent, not a broadcast: resuming restarts the dataSync service, and
        // that is legal only once an Activity is actually in the foreground.
        if (resumable) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.stat_sys_download,
                    "Resume",
                    updateContentIntent(PendingUpdateAction.Resume),
                ).build(),
            )
        }
        builder.addAction(UpdateActionReceiver.cancelAction(context))
        manager.notify(UPDATE_FAILED_ID, builder.build())
    }

    override fun cancelUpdateNotifications() {
        manager.cancel(UPDATE_READY_ID)
        manager.cancel(UPDATE_FAILED_ID)
    }

    /**
     * Brings the app forward on Settings, optionally asking it to perform
     * [action] once there. Both actions require a foreground Activity: an
     * install sheet is suppressed without one, and a dataSync service may not
     * be started without one.
     */
    private fun updateContentIntent(action: PendingUpdateAction?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_UPDATE_ACTION, action?.name)
        return PendingIntent.getActivity(
            context,
            // Distinct request codes, or the three intents would collide on
            // FLAG_UPDATE_CURRENT and the last one built would win everywhere.
            UPDATE_READY_ID + (action?.ordinal ?: -1),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Suppress("DEPRECATION")
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
        // App updates get their own channels rather than borrowing the agent
        // ones: "Finished" and its ringtone preference are about agents, and a
        // user who silences agents has not asked to be kept in the dark about
        // their own app updating.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE_PROGRESS, "App update progress", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "A Scoutr update is building or downloading" },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE_READY, "App update ready", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A Scoutr update finished downloading and is ready to install" },
        )
    }

    private fun activeEntries(group: String) = manager.activeNotifications.filter {
        it.id != SUMMARY_ID && it.id != DONE_SUMMARY_ID && it.notification.group == group
    }

    private fun tagOf(key: HostPaneKey): String =
        "${hostTagPrefix(key.profile.hostId)}${key.profile.profileGeneration}.${encodeTagPart(key.paneId)}"

    /** Length-prefixing keeps host A from matching host A.B during cleanup. */
    private fun hostTagPrefix(hostId: String): String = "$TAG_PREFIX${hostId.length}:$hostId."

    private fun profileTagPrefix(profile: HostProfileKey): String =
        "${hostTagPrefix(profile.hostId)}${profile.profileGeneration}."

    private fun encodeTagPart(value: String): String =
        android.util.Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )

    private fun requestCode(key: HostPaneKey, action: String): Int =
        key.encode().hashCode() xor action.hashCode()

    private fun slotOf(key: HostPaneKey): Int = key.encode().hashCode()

    private fun doneSlotOf(key: HostPaneKey): Int = slotOf(key) xor DONE_SLOT_XOR

    private fun slotOf(paneId: String): Int = paneId.hashCode()

    private fun doneSlotOf(paneId: String): Int = paneId.hashCode() xor DONE_SLOT_XOR

    companion object {
        const val CHANNEL_NEEDS_YOU = "needs_you"
        const val CHANNEL_DONE = "agent_done"
        const val GROUP_KEY = "dev.scoutr.app.NEEDS_YOU"
        const val GROUP_DONE = "dev.scoutr.app.AGENT_DONE"

        const val CHANNEL_UPDATE_PROGRESS = "app_update_progress"
        const val CHANNEL_UPDATE_READY = "app_update_ready"

        const val SUMMARY_ID = 0x5C0F7A
        const val DONE_SUMMARY_ID = 0x5C0F7B

        // Fixed slots, outside the agent id space (which is derived from pane
        // identity hashes) and outside both group summaries.
        const val UPDATE_PROGRESS_ID = 0x5C0F80
        const val UPDATE_READY_ID = 0x5C0F81
        const val UPDATE_FAILED_ID = 0x5C0F82

        private const val BLOCKED = "blocked"
        private const val ERRORED = "errored"
        private const val DONE = "done"
        private const val ACCENT = 0xFFE5484D.toInt()
        private const val DONE_SLOT_XOR = 0x40000000.toInt()
        private const val TAG_PREFIX = "scoutr.notification."
        private const val LEGACY_CHANNEL_AGENTS = "agents"
        private const val LEGACY_CHANNEL_MONITOR = "scoutr_monitor"
    }
}
