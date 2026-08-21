package dev.scoutr.app.notify

import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import dev.scoutr.app.data.NotificationPreferencesStore
import dev.scoutr.app.data.RepoSummary
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.SessionLiveAttachment
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
    )

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
    fun exactlyOneChannelExistsAndTheLegacyOnesAreGone() {
        // Simulate an upgrading install that still carries the dead channels.
        manager.createNotificationChannel(
            android.app.NotificationChannel("agents", "Agents", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            android.app.NotificationChannel("scoutr_monitor", "Monitor", NotificationManager.IMPORTANCE_LOW),
        )

        presenter.showBlocked(blocked("w1:p1"))

        assertEquals(
            setOf(NotificationPresenter.CHANNEL_NEEDS_YOU, NotificationPresenter.CHANNEL_DONE),
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
}
