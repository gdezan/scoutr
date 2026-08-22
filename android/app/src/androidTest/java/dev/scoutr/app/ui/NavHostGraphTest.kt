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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.junit.rules.TestRule

/**
 * NavHost graph test on the real activity: with a saved connection to a
 * bridge that answers the compatibility handshake, the app starts on the
 * Board tab with the bottom bar, and tapping each tab swaps both the top bar
 * title and the destination while the bar stays derived from
 * Destination.routes.
 *
 * The seed must be a live stub bridge, not just any saved connection: the
 * shell shows the bottom bar only when the health probe classified the API as
 * compatible (`showBottomBar` requires `compatible`), and a dead port never
 * completes that probe. The dispatcher answers every request deterministically
 * so background refreshes can never exhaust queued responses mid-assertion.
 *
 * The permission dialog problem: MainActivity requests POST_NOTIFICATIONS
 * before setContent, and a pending request steals the compose test owner's
 * window (ActivityScenario.launch never sees the RESUMED state). The seeded
 * connection must land BEFORE the rule launches the activity, hence the
 * outer rule.
 */
@RunWith(AndroidJUnit4::class)
class NavHostGraphTest {

    /** Runs before the compose rule's activity launch. */
    @get:Rule(order = 0)
    val seed: TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val server = MockWebServer()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val body = when (request.path?.substringBefore('?')) {
                            "/api/health" ->
                                """{"ok":true,"service":"scoutr-bridge","version":"test","api":{"protocol":2,"features":["commands.http.v1"]},"herdr":{"connected":true}}"""
                            "/api/agents" -> """{"ok":true,"agents":[]}"""
                            else -> """{"ok":false,"error":"not stubbed"}"""
                        }
                        return MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(body)
                    }
                }
                server.start()
                try {
                    ConnectionStore(ApplicationProvider.getApplicationContext())
                        .save(server.url("/").toString().trimEnd('/'), "test_token")
                    base.evaluate()
                } finally {
                    server.shutdown()
                }
            }
        }
    }

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = compose.onAllNodesWithText(label)
        .filter(hasClickAction())[0]

    @Test
    fun bottomBarShowsAllFourTabsAndSwitchesBetweenThem() {
        // Compatible stub bridge: the NavHost starts on Board with the bar.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Board").filter(hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
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
