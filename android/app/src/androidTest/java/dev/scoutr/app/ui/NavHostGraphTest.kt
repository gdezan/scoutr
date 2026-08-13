package dev.scoutr.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.scoutr.app.MainActivity
import dev.scoutr.app.data.ConnectionStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.junit.rules.TestRule

/**
 * NavHost graph test on the real activity: with a saved connection the app
 * starts on the Board tab with the bottom bar, and tapping each tab swaps
 * both the top bar title and the destination while the bar stays derived
 * from Destination.routes. The connection points at a dead port so no
 * bridge traffic can interfere with the assertions.
 *
 * The permission dialog problem: MainActivity requests POST_NOTIFICATIONS
 * before setContent, and a pending request steals the compose test owner's
 * window (ActivityScenario.launch never sees the RESUMED state). The request
 * is gated on background monitoring being enabled (MonitoringStore), so the
 * seed rule resets monitoring off — no dialog, no pm-grant dance, on any
 * device regardless of leftover QA state. The seeded connection must also
 * land BEFORE the rule launches the activity, hence the outer rule.
 */
@RunWith(AndroidJUnit4::class)
class NavHostGraphTest {

    /** Runs before the compose rule's activity launch. */
    @get:Rule(order = 0)
    val seed: TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<android.content.Context>()
                ConnectionStore(context).save("http://127.0.0.1:1", "t", null, null)
                dev.scoutr.app.state.MonitoringStore(context).also { it.enabled = false }
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = compose.onAllNodesWithText(label)
        .filter(hasClickAction())[0]

    @Test
    fun bottomBarShowsAllFourTabsAndSwitchesBetweenThem() {
        // Seeded connection: the NavHost starts on Board with the bar.
        compose.onAllNodesWithText("Board").filter(hasClickAction())[0].assertIsDisplayed()
        compose.onAllNodesWithText("Sessions").filter(hasClickAction())[0].assertIsDisplayed()
        compose.onAllNodesWithText("Usage").filter(hasClickAction())[0].assertIsDisplayed()
        compose.onAllNodesWithText("Review").filter(hasClickAction())[0].assertIsDisplayed()

        // Each tab swap changes the top bar title; the bar must not move.
        tab("Review").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Review").fetchSemanticsNodes().size >= 2
        }
        tab("Sessions").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().size >= 2
        }
        tab("Usage").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Usage").fetchSemanticsNodes().size >= 2
        }
        tab("Board").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Board").fetchSemanticsNodes().size >= 2
        }
    }
}