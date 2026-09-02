package dev.scoutr.app.notify

import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.NotificationPreferencesStore
import dev.scoutr.app.data.RepoSummary
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.SessionLiveAttachment
import dev.scoutr.app.data.SessionSubagent
import dev.scoutr.app.state.MuteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The presenter's contract, asserted through the real NotificationManager:
 * one slot per pane, a summary only from two up, and exactly one channel.
 * These are the invariants that the two old builders violated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPresenterTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var presenter: NotificationPresenter
    private lateinit var mutes: MuteStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        context.getSharedPreferences(MuteStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(dev.scoutr.app.data.NotificationPreferencesStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        mutes = MuteStore(context)
        presenter = NotificationPresenter(context, mutes)
    }

    private fun blocked(
        paneId: String,
        name: String = "pi",
        cwd: String? = "/home/gdezan/Dev/scoutr",
        liveSummary: RepoSummary? = null,
    ) = session(paneId, status = "blocked", name = name, cwd = cwd, liveSummary = liveSummary)

    private fun session(
        paneId: String,
        status: String,
        name: String = "pi",
        cwd: String? = "/home/gdezan/Dev/scoutr",
        liveSummary: RepoSummary? = null,
        doneSummary: RepoSummary? = null,
        subagent: SessionSubagent? = null,
    ) = SessionDescriptor(
        agentKind = "pi",
        displayName = name,
        title = "$name pane",
        cwd = cwd,
        liveSummary = liveSummary,
        doneSummary = doneSummary,
        live = SessionLiveAttachment(
            paneId = paneId,
            workspaceId = "w1",
            tabId = "t1",
            status = status,
        ),
        subagent = subagent,
    )


    @Test
    fun orphanBlockedOmitsReplyAndOpensProgress() {
        val profile = HostProfileKey("host-a", 1)
        presenter.showBlocked(
            profile,
            session(
                paneId = "same-pane",
                status = "blocked",
                subagent = SessionSubagent(runId = "run-abc", role = "researcher", orphan = true),
            ),
        )

        val posted = slots().single().notification
        assertEquals(listOf("Mute this agent"), posted.actions.map { it.title.toString() })
        val intent = org.robolectric.Shadows.shadowOf(posted.contentIntent).savedIntent
        assertEquals("scoutr://subagent/host-a/1/run-abc", intent.dataString)
    }

    /**
     * Done pushes are opt-in (`DEFAULT_DONE = false`), so every done-path
     * test flips the same toggle the user would.
     */
    private fun enableDoneNotifications() {
        NotificationPreferencesStore(context).doneEnabled = true
    }

    /** Dirty working tree on main: the case worth naming in a push. */
    private fun dirtyMainSummary() = RepoSummary(
        repoRoot = "/home/gdezan/Dev/scoutr",
        branch = "main",
        changedFiles = 2,
        additions = 3,
        deletions = 1,
        dirty = true,
    )

    private fun cleanMainSummary() = dirtyMainSummary().copy(dirty = false)

    private fun slots() = manager.activeNotifications.filter { it.id != NotificationPresenter.SUMMARY_ID && it.id != NotificationPresenter.DONE_SUMMARY_ID }

    private fun summary() =
        manager.activeNotifications.firstOrNull { it.id == NotificationPresenter.SUMMARY_ID }
    @Test
    fun oneBlockedAgentPostsOneSlotAndNoSummary() {
        presenter.showBlocked(blocked("w1:p1"))

        assertEquals(1, slots().size)
        assertNull("a group of one renders as a stray header", summary())
    }

    @Test
    fun cancelLegacyRemovesOnlyUntaggedSingletonNotifications() {
        presenter.showBlocked(blocked("legacy-pane"))
        presenter.showBlocked(HostProfileKey("host-a", 1), blocked("qualified-pane"))

        presenter.cancelLegacy()

        val remaining = slots().single()
        assertTrue(remaining.tag?.contains("host-a") == true)
    }

    @Test
    fun samePaneOnTwoHostsUsesIndependentTaggedSlotsAndIntents() {
        val hostA = HostProfileKey("host-a", 1)
        val hostB = HostProfileKey("host-b", 2)

        presenter.showBlocked(hostA, blocked("same-pane"))
        presenter.showBlocked(hostB, blocked("same-pane", name = "claude"))

        val children = slots()
        assertEquals(2, children.size)
        assertTrue(children.mapNotNull { it.tag }.any { it.contains("host-a") })
        assertTrue(children.mapNotNull { it.tag }.any { it.contains("host-b") })
        val links = children.mapNotNull { child ->
            child.notification.contentIntent?.let { pending ->
                org.robolectric.Shadows.shadowOf(pending).savedIntent?.data?.toString()
            }
        }
        assertTrue(links.any { it.contains("host-a") && it.contains("/1/") })
        assertTrue(links.any { it.contains("host-b") && it.contains("/2/") })

        presenter.cancelHost("host-a")
        assertEquals(1, slots().size)
        assertTrue(slots().single().tag!!.contains("host-b"))
    }

    @Test
    fun hostQualifiedNotificationsRespectDisabledPreferences() {
        val profile = HostProfileKey("host-a", 1)
        NotificationPreferencesStore(context).blockedEnabled = false

        presenter.showBlocked(profile, blocked("pane-a"))
        presenter.showDone(profile, session("pane-b", status = "done"))

        assertTrue(slots().isEmpty())
    }

    @Test
    fun theTitleNamesTheAgentAndItsWorkspace() {
        presenter.showBlocked(blocked("w1:p1", name = "π"))

        val extras = slots().single().notification.extras
        assertEquals("π · scoutr", extras.getString("android.title"))
        assertEquals("Needs your input", extras.getString("android.text"))
    }

    @Test
    fun anAgentWithNoWorkspacePathStillGetsANamedTitle() {
        presenter.showBlocked(blocked("w1:p1", name = "claude", cwd = null))

        assertEquals("claude", slots().single().notification.extras.getString("android.title"))
    }

    @Test
    fun twoPanesProduceTwoSlotsUnderOneSummary() {
        presenter.showBlocked(blocked("w1:p1"))
        presenter.showBlocked(blocked("w1:p2", name = "claude"))

        assertEquals(2, slots().size)
        assertNotNull(summary())
    }

    @Test
    fun repostingTheSamePaneUpdatesItsSlotInPlace() {
        presenter.showBlocked(blocked("w1:p1"))
        presenter.showBlocked(blocked("w1:p2"))
        val idsBefore = slots().map { it.id }.toSet()

        presenter.showBlocked(blocked("w1:p1", name = "renamed"))

        assertEquals("a second block must not stack a second notification", 2, slots().size)
        assertEquals(idsBefore, slots().map { it.id }.toSet())
        assertNotNull(summary())
    }

    @Test
    fun cancelRemovesThatPaneAndDropsTheSummaryAtOneRemaining() {
        presenter.showBlocked(blocked("w1:p1"))
        presenter.showBlocked(blocked("w1:p2"))

        presenter.cancel("w1:p1")

        assertEquals(1, slots().size)
        assertEquals("w1:p2".hashCode(), slots().single().id)
        assertNull(summary())
    }

    @Test
    fun cancellingAnUnknownPaneChangesNothing() {
        presenter.showBlocked(blocked("w1:p1"))

        presenter.cancel("w1:p-never-blocked")

        assertEquals(1, slots().size)
    }

    @Test
    fun cancelAllExceptClearsPanesThatAreNoLongerBlocked() {
        presenter.showBlocked(blocked("w1:p1"))
        presenter.showBlocked(blocked("w1:p2"))
        presenter.showBlocked(blocked("w1:p3"))

        presenter.cancelAllExcept(setOf("w1:p2"))

        assertEquals(1, slots().size)
        assertEquals("w1:p2".hashCode(), slots().single().id)
        assertNull(summary())
    }

    @Test
    fun cancelAllExceptWithNoLivePanesClearsEverything() {
        presenter.showBlocked(blocked("w1:p1"))
        presenter.showBlocked(blocked("w1:p2"))

        presenter.cancelAllExcept(emptySet())

        assertTrue(slots().isEmpty())
        assertNull(summary())
    }

    @Test
    fun theDegradedNotificationTakesTheSameSlotAsTheNamedOne() {
        presenter.showDegraded("w1:p1")

        assertEquals("w1:p1".hashCode(), slots().single().id)
        assertEquals("An agent needs you", slots().single().notification.extras.getString("android.title"))

        // The identity fetch succeeding later replaces it rather than stacking.
        presenter.showBlocked(blocked("w1:p1"))
        assertEquals(1, slots().size)
        assertEquals("pi · scoutr", slots().single().notification.extras.getString("android.title"))
    }

    @Test
    fun onlyTheIntendedChannelsExistAndTheLegacyOnesAreGone() {
        // Simulate an upgrading install that still carries the dead channels.
        manager.createNotificationChannel(
            android.app.NotificationChannel("agents", "Agents", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            android.app.NotificationChannel("scoutr_monitor", "Monitor", NotificationManager.IMPORTANCE_LOW),
        )

        presenter.showBlocked(blocked("w1:p1"))

        assertEquals(
            setOf(
                NotificationPresenter.CHANNEL_NEEDS_YOU,
                NotificationPresenter.CHANNEL_DONE,
                NotificationPresenter.CHANNEL_UPDATE_PROGRESS,
                NotificationPresenter.CHANNEL_UPDATE_READY,
            ),
            manager.notificationChannels.map { it.id }.toSet(),
        )
    }

    @Test
    fun everySlotCarriesBothActions() {
        presenter.showBlocked(blocked("w1:p1"))

        val actions = slots().single().notification.actions
        assertEquals(listOf("Reply", "Mute this agent"), actions.map { it.title.toString() })
    }
    @Test
    fun replyActionPendingIntentIsMutable() {
        presenter.showBlocked(blocked("w1:p1"))

        val reply = slots().single().notification.actions.single { it.title == "Reply" }
        val shadow = org.robolectric.Shadows.shadowOf(reply.actionIntent)
        assertTrue(
            "RemoteInput actions must be FLAG_MUTABLE or notify() is rejected",
            !shadow.isImmutable,
        )
    }

    @Test
    fun aMutedPanePostsNothing() {
        mutes.mute("w1:p1")

        presenter.showBlocked(blocked("w1:p1"))
        presenter.showDegraded("w1:p1")

        assertTrue("mute must hold for the degraded path too", slots().isEmpty())
    }

    @Test
    fun aBlockedAgentNamesItsBranchAndUncommittedWork() {
        presenter.showBlocked(blocked("w1:p1", liveSummary = dirtyMainSummary()))

        assertEquals("Needs your input · main · uncommitted", slots().single().notification.extras.getString("android.text"))
    }

    @Test
    fun aDoneAgentNamesWhereItsWorkSits() {
        enableDoneNotifications()
        presenter.showDone(session("w1:p1", status = "done", doneSummary = cleanMainSummary()))

        assertEquals("Finished · main", slots().single().notification.extras.getString("android.text"))
    }

    @Test
    fun aDoneAgentWithNoBranchStillFlagsUncommittedWork() {
        enableDoneNotifications()
        val detached = dirtyMainSummary().copy(branch = null)
        presenter.showDone(session("w1:p1", status = "done", doneSummary = detached))

        assertEquals("Finished · uncommitted", slots().single().notification.extras.getString("android.text"))
    }

    @Test
    fun notificationsWithoutRepoEvidenceStayUnchanged() {
        enableDoneNotifications()
        presenter.showBlocked(blocked("w1:p1", name = "pi"))
        presenter.showDone(session("w1:p2", status = "done", name = "claude"))

        val texts = slots().associate {
            it.notification.extras.getString("android.title") to it.notification.extras.getString("android.text")
        }
        assertEquals("Needs your input", texts["pi · scoutr"])
        assertEquals("Finished", texts["claude · scoutr"])
    }

    @Test
    fun mutingOnePaneLeavesTheOthersAlone() {
        mutes.mute("w1:p1")

        presenter.showBlocked(blocked("w1:p1"))
        presenter.showBlocked(blocked("w1:p2"))

        assertEquals(1, slots().size)
        assertEquals("w1:p2".hashCode(), slots().single().id)
        assertNull("one live slot needs no summary", summary())
    }

    @Test
    fun `update notifications live on their own channels, not the agent ones`() {
        presenter.showUpdateReady(
            dev.scoutr.app.update.StagedIdentity(commit = "abc1234", sha256 = "ab", size = 10, version = "0.4.0"),
        )

        val progress = manager.getNotificationChannel(NotificationPresenter.CHANNEL_UPDATE_PROGRESS)
        val ready = manager.getNotificationChannel(NotificationPresenter.CHANNEL_UPDATE_READY)
        assertNotNull(progress)
        assertNotNull(ready)
        // Progress rides along with the foreground service for minutes, so it
        // must never make a sound; "ready" is the one moment worth alerting on.
        assertEquals(NotificationManager.IMPORTANCE_LOW, progress!!.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, ready!!.importance)

        val posted = manager.activeNotifications.single { it.id == NotificationPresenter.UPDATE_READY_ID }
        assertEquals(NotificationPresenter.CHANNEL_UPDATE_READY, posted.notification.channelId)
        assertNull("an update belongs to no agent group", posted.notification.group)
    }

    @Test
    fun `update notifications ignore the agent notification preferences`() {
        // "Notify when blocked" and "Ring for finished" are about agents. A user
        // who turned those off has not asked to be kept in the dark about their
        // own app finishing an update.
        val prefs = NotificationPreferencesStore(context)
        prefs.blockedEnabled = false
        prefs.doneEnabled = false
        val gated = NotificationPresenter(context, mutes, prefs)

        gated.showUpdateReady(
            dev.scoutr.app.update.StagedIdentity(commit = "abc1234", sha256 = "ab", size = 10, version = "0.4.0"),
        )
        gated.showUpdateFailed("connection reset", resumable = true)

        assertNotNull(manager.activeNotifications.firstOrNull { it.id == NotificationPresenter.UPDATE_READY_ID })
        assertNotNull(manager.activeNotifications.firstOrNull { it.id == NotificationPresenter.UPDATE_FAILED_ID })
    }

    @Test
    fun `a failure with nothing downloaded offers no resume`() {
        presenter.showUpdateFailed("the host build failed", resumable = false)
        val without = manager.activeNotifications.single { it.id == NotificationPresenter.UPDATE_FAILED_ID }
        val withoutLabels = without.notification.actions.orEmpty().map { it.title.toString() }

        presenter.showUpdateFailed("connection reset", resumable = true)
        val with = manager.activeNotifications.single { it.id == NotificationPresenter.UPDATE_FAILED_ID }
        val withLabels = with.notification.actions.orEmpty().map { it.title.toString() }

        assertEquals(listOf("Cancel"), withoutLabels)
        assertEquals(listOf("Resume", "Cancel"), withLabels)
    }

    @Test
    fun `cancelling update notifications leaves agent notifications alone`() {
        presenter.showBlocked(blocked("pane-1"))
        presenter.showUpdateReady(
            dev.scoutr.app.update.StagedIdentity(commit = "abc1234", sha256 = "ab", size = 10, version = "0.4.0"),
        )

        presenter.cancelUpdateNotifications()

        assertNull(manager.activeNotifications.firstOrNull { it.id == NotificationPresenter.UPDATE_READY_ID })
        assertTrue("the agent notification must survive", manager.activeNotifications.isNotEmpty())
    }
}
