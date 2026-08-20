package dev.scoutr.app.ui.screens

import androidx.compose.material3.darkColorScheme
import dev.scoutr.app.data.AgentStatus
import dev.scoutr.app.data.SessionCatalogItem
import dev.scoutr.app.state.HistoryItem
import dev.scoutr.app.state.HistoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HistoryRepositoryFilterTest {
    @Test
    fun sessionRepoKeyNormalizesAndRejectsMalformedPaths() {
        val home = System.getenv("HOME")?.trimEnd('/')
        if (!home.isNullOrBlank()) {
            assertEquals("$home/scoutr", sessionRepoKey("$home/scoutr/"))
        }
        assertEquals("/opt/scoutr/repo", sessionRepoKey("/opt//scoutr/./repo"))
        assertEquals("/repo/b", sessionRepoKey("/repo/a/../b"))
        assertNotEquals(sessionRepoKey("/clients/a/app"), sessionRepoKey("/clients/b/app"))
        assertEquals("Other", sessionRepoKey(null))
        assertEquals("Other", sessionRepoKey(""))
        assertEquals("Other", sessionRepoKey("repo/relative"))
        assertEquals("Other", sessionRepoKey("/../repo"))
        assertEquals("Other", sessionRepoKey("/repo/${'\u0000'}"))
        assertEquals("Other", sessionRepoKey("/"))
    }

    @Test
    fun repositoryTabsAreRecencyOrderedAndCollapseCanonicalPaths() {
        val items = listOf(
            history("old-a", "/repo/a", active = true, updatedAt = 1.0),
            history("new-b", "/repo/b", active = true, updatedAt = 4.0),
            history("same-b", "/repo/b/./child/..", active = false, updatedAt = 3.0),
            history("other", "relative/path", active = false, updatedAt = 2.0),
        )

        assertEquals(listOf("All", "/repo/b", "Other", "/repo/a"), repositoryTabs(items))
    }

    @Test
    fun blockedSessionUsesNeedsYouErrorColor() {
        val blocked = history("blocked", "/repo/a", active = true, updatedAt = 1.0, status = "blocked")
        val scheme = darkColorScheme()

        assertEquals(AgentStatus.NeedsYou, historyStatus(blocked.session))
        assertEquals(scheme.error, historyStatusColor(AgentStatus.NeedsYou, scheme))
        assertEquals(scheme.primary, historyStatusColor(AgentStatus.Working, scheme))
    }

    @Test
    fun scopeAndRepositoryFiltersRemainIndependent() {
        val items = listOf(
            history("active-a", "/workspace/a", active = true, updatedAt = 3.0),
            history("done-a", "/workspace/a", active = false, updatedAt = 2.0),
            history("pinned-b", "/workspace/b", active = false, pinned = true, updatedAt = 1.0),
            history("archived-a", "/workspace/a", active = false, archived = true, updatedAt = 4.0),
        )

        assertEquals(listOf("active-a", "done-a"), sortedHistoryItems(items, HistoryScope.All, "/workspace/a").map { it.session.id })
        assertEquals(listOf("active-a"), sortedHistoryItems(items, HistoryScope.Active, "/workspace/a").map { it.session.id })
        assertEquals(listOf("done-a"), sortedHistoryItems(items, HistoryScope.Completed, "/workspace/a").map { it.session.id })
        assertEquals(listOf("pinned-b"), sortedHistoryItems(items, HistoryScope.Pinned, "/workspace/b").map { it.session.id })
        assertEquals(listOf("archived-a"), sortedHistoryItems(items, HistoryScope.Archived, "/workspace/a").map { it.session.id })
        assertEquals(2, sortedHistoryItems(items, HistoryScope.Completed, "All").size)
        assertEquals(listOf("pinned-b", "active-a", "done-a"), sortedHistoryItems(items, HistoryScope.All, "All").map { it.session.id })
    }

    private fun history(
        id: String,
        cwd: String,
        active: Boolean,
        pinned: Boolean = false,
        archived: Boolean = false,
        updatedAt: Double,
        status: String? = null,
    ) = HistoryItem(
        session = dev.scoutr.app.data.catalogSessionFixture(
            key = dev.scoutr.app.data.SessionKey("pi", "/sessions/$id.jsonl"),
            cwd = cwd,
            title = id,
            updatedAtMs = updatedAt,
            live = if (active) {
                dev.scoutr.app.data.SessionLiveAttachment(id, "workspace", "tab", status ?: "working", null)
            } else {
                null
            },
        ),
        pinned = pinned,
        archived = archived,
    )
}
