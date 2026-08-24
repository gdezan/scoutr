package dev.scoutr.app.state

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("scoutr_review", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun lastRepoPath_is_scoped_by_host() {
        val a = ReviewStore(context, "host-a")
        val b = ReviewStore(context, "host-b")

        a.lastRepoPath = "/repos/shared"

        assertEquals("/repos/shared", a.lastRepoPath)
        assertNull(b.lastRepoPath)
        assertEquals("/repos/shared", a.lastRepoPath("host-a"))
        assertNull(b.lastRepoPath("host-b"))
    }

    @Test
    fun legacy_path_moves_only_when_first_host_migration_is_explicit() {
        context.getSharedPreferences("scoutr_review", Context.MODE_PRIVATE)
            .edit().putString("lastRepoPath", "/legacy/repo").commit()
        val host = ReviewStore(context, "host-a")

        assertNull(host.lastRepoPath)
        host.adoptLegacyPath("host-a")

        assertEquals("/legacy/repo", host.lastRepoPath)
        assertNull(
            context.getSharedPreferences("scoutr_review", Context.MODE_PRIVATE)
                .getString("lastRepoPath", null),
        )
    }

    @Test
    fun retiredHostCannotRepopulateClearedReviewState() {
        val staleStore = ReviewStore(context, "host-a") { _, _ -> false }
        staleStore.lastRepoPath = "/stale"

        assertNull(staleStore.lastRepoPath)
    }

    @Test
    fun clearHost_removes_only_retired_host_review_state() {
        ReviewStore(context, "host-a").lastRepoPath = "/a"
        ReviewStore(context, "host-b").lastRepoPath = "/b"

        ReviewStore(context, "host-a").clearHost("host-a")

        assertNull(ReviewStore(context, "host-a").lastRepoPath)
        assertEquals("/b", ReviewStore(context, "host-b").lastRepoPath)
    }
}
