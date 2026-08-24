package dev.scoutr.app.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import android.content.ClipboardManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import dev.scoutr.app.data.AttentionQuestion
import dev.scoutr.app.data.RepoSummary
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.onAllNodesWithTag
import dev.scoutr.app.data.AttentionSummary
import dev.scoutr.app.data.QuestionOption
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.ui.screens.BoardScreen
import dev.scoutr.app.ui.theme.ScoutrTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import java.io.FileOutputStream
/** Board card composition: phase, model, latest activity, and needs-you emphasis. */
class BoardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, overlay: Boolean = false) {
        val file = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!.resolve("$name.png")
        FileOutputStream(file).use { output ->
            if (overlay) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            } else {
                compose.onNodeWithTag("board_capture_root").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        println("BOARD_SCREENSHOT=${file.absolutePath}")
    }

    private fun largeFontEnabled(): Boolean {
        val requested = InstrumentationRegistry.getArguments().getString("fontScale") == "1.3"
        if (requested) {
            val applied = Settings.System.getFloat(
                InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
                Settings.System.FONT_SCALE,
            )
            assertTrue("large-font evidence requires font_scale 1.3, was $applied", abs(applied - 1.3f) <= 0.01f)
        }
        return requested
    }

    @Test
    fun compactBoardContentKeepsSixteenDpGutters() {
        compose.setContent {
            ScoutrTheme {
                Box(Modifier.width(320.dp)) {
                    BoardScreen(
                        onOpenAgent = {},
                        viewModel = StaticBoards.boardViewModel(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", null, null))),
                    )
                }
            }
        }
        val contentBounds = compose.onNodeWithTag("board_capture_root").getUnclippedBoundsInRoot()
        assertTrue("compact board should start at 12dp", abs(contentBounds.left.value - 12f) <= 1f)
        assertTrue("compact board should be 296dp wide", abs((contentBounds.right - contentBounds.left).value - 296f) <= 1f)
    }

    @Test
    fun wideBoardContentUsesReadableBound() {
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onOpenAgent = {},
                    viewModel = StaticBoards.boardViewModel(listOf(
                                    blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"),
                                    workingAgent("p2", "Docs refresh", "/repo/b", "anthropic/claude-sonnet-4-6", "Updating docs"),
                                ),),
                )
            }
        }
        val contentBounds = compose.onNodeWithTag("board_capture_root").getUnclippedBoundsInRoot()
        val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()
        if (rootBounds.right - rootBounds.left > 1008.dp) {
            assertTrue("wide board should be 960dp", abs((contentBounds.right - contentBounds.left).value - 960f) <= 1f)
            assertTrue("wide board should be centered", abs(((contentBounds.left + contentBounds.right) - (rootBounds.left + rootBounds.right)).value) <= 2f)
            assertTrue("wide Board overflow remains reachable", compose.onAllNodes(androidx.compose.ui.test.hasTestTag("agent_actions_p1")).fetchSemanticsNodes().isNotEmpty())
            capture(if (largeFontEnabled()) "board-wide-large-font" else "board-wide")
        }
    }


    private fun blockedAgent(
        paneId: String,
        title: String,
        cwd: String,
        model: String?,
        activity: String?,
    ) = dev.scoutr.app.data.liveSessionFixture(
        paneId = paneId,
        workspaceId = "ws_$paneId",
        tabId = "tab_$paneId",
        agentKind = "pi",
        status = "blocked",
        cwd = cwd,
        title = title,
        key = dev.scoutr.app.data.SessionKey("pi", "/sessions/$paneId.jsonl"),
        statusSinceMs = (System.currentTimeMillis() - 90_000).toDouble(),
        model = model,
        latestActivity = activity,
        updatedAtMs = (System.currentTimeMillis() - 30_000).toDouble(),
    )

    private fun workingAgent(
        paneId: String,
        title: String,
        cwd: String,
        model: String?,
        activity: String?,
    ) = blockedAgent(paneId, title, cwd, model, activity).let { descriptor ->
        descriptor.copy(live = descriptor.live?.copy(status = "working"))
    }

    @Test
    fun cardsShowPhaseSectionModelActivityAndTime() {
        val uiSessions = listOf(
                blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found the rounding error in the tax module"),
                workingAgent("p2", "Docs refresh", "/repo/b", "anthropic/claude-sonnet-4-6", "Updating the README with the new flags"),
            )
        compose.setContent {
            ScoutrTheme {
                BoardScreen(onOpenAgent = {}, viewModel = StaticBoards.boardViewModel(uiSessions))
            }
        }

        // The header's word and count are separate nodes now (they carry
        // different colors), so the pair is read from the merged description.
        compose.onNodeWithContentDescription("NEEDS YOU 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("WORKING 1").assertIsDisplayed()
        compose.onNodeWithTag("agent_card_p1").assertIsDisplayed()
        compose.onNodeWithText("Found the rounding error in the tax module").assertIsDisplayed()
        // Path and model are one mono line now: `~/repo · gpt-5.4` (§8b).
        println("SCRATCH_CARDS_TREE_START")
        println(compose.onRoot().printToString())
        println("SCRATCH_CARDS_TREE_END")
        compose.onNodeWithText("gpt-5.4", substring = true).assertIsDisplayed()
        // The status word gave way to time-in-state; the ring carries the phase.
        compose.onNodeWithTag("board_section_needs_you").assertIsDisplayed()
        capture("board-card", overlay = true)
    }

    @Test
    fun visibleOverflowMenuExposesSharedActions() {
        var reviewed = false
        var closed: String? = null
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onReviewAgent = { reviewed = true },
                    onCloseAgent = { closed = it.session.live?.paneId },
                    viewModel = StaticBoards.boardViewModel(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                )
            }
        }
        compose.onNodeWithTag("agent_actions_p1").assertIsDisplayed()
        compose.onNodeWithTag("agent_actions_p1").assertContentDescriptionContains("Agent actions for Fix billing bug")
        compose.onNodeWithTag("agent_actions_p1").performClick()
        compose.onNodeWithTag("board_menu_review_p1").assertIsDisplayed()
        compose.onNodeWithTag("board_menu_copy_p1").assertIsDisplayed()
        compose.onNodeWithTag("board_menu_close_p1").assertIsDisplayed()
        capture("board-menu", overlay = true)
        compose.onNodeWithTag("board_menu_review_p1").performClick()
        assertTrue("menu review should use the shared callback", reviewed)
        compose.onNodeWithTag("agent_actions_p1").performClick()
        compose.onNodeWithTag("board_menu_copy_p1").performClick()
        val clip = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("menu copy should use the shared callback", "/repo/a", clip)
        compose.onNodeWithTag("agent_actions_p1").performClick()
        compose.onNodeWithTag("board_menu_close_p1").performClick()
        compose.onNodeWithText("Close agent?").assertIsDisplayed()
        capture("board-close-confirm", overlay = true)
        assertEquals("close remains confirmation-gated", null, closed)
        compose.onAllNodesWithText("Close").filterToOne(hasClickAction()).performClick()
        assertEquals("confirmed menu close should use the shared callback", "p1", closed)
    }

    @Test
    fun loadingFeedbackShownWhileNoAgents() {
        val staticVm = StaticBoards.emptyHostsViewModel()
        compose.setContent {
            ScoutrTheme {
                BoardScreen(onOpenAgent = {}, viewModel = staticVm)
            }
        }
        compose.onNodeWithText("Loading agents…").assertIsDisplayed()
    }

    @Test
    fun emptyStateWhenNoAgents() {
        compose.setContent {
            ScoutrTheme {
                BoardScreen(onOpenAgent = {}, viewModel = StaticBoards.connectedEmptyViewModel())
            }
        }
        compose.onNodeWithText("No agents running").assertIsDisplayed()
    }

    @Test
    fun incompatibleBridgeShowsUpdateGuidanceAndSettingsAction() {
        var openedSettings = false
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onResolveCompatibility = { openedSettings = true },
                    viewModel = StaticBoards.incompatibleViewModel(),
                )
            }
        }

        // The blocked host renders as a compact issue card naming the alias.
        println("SCRATCH_INCOMPAT_TREE=" + compose.onRoot().printToString().lines().filter { "Text =" in it || "Tag:" in it }.joinToString(" || "))
        compose.onNodeWithText("bridge is unavailable", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Host incompatible", ignoreCase = true, substring = true).assertIsDisplayed()
        compose.onNodeWithText("No agents running").assertDoesNotExist()
        compose.onNodeWithTag("board_compatibility_retry").assertIsDisplayed()
        compose.onNodeWithText("Manage in Settings").performClick()
        assertTrue(openedSettings)
    }

    @Test
    fun swipeLeftRevealsReviewAndFiresIt() {
        var reviewedCwd: String? = null
        var opened: String? = null
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onOpenAgent = { opened = it.session.live?.paneId },
                    onReviewAgent = { reviewedCwd = it.session.cwd },
                    viewModel = StaticBoards.boardViewModel(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                )
            }
        }
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("board_action_review_p1").assertIsDisplayed()
        compose.onNodeWithTag("board_action_review_p1").performClick()
        compose.waitForIdle()
        assertTrue("review callback should carry the agent cwd", reviewedCwd == "/repo/a")
        assertTrue("review must not also open the card", opened == null)
    }

    @Test
    fun swipeLeftRevealsCopyAndCopiesPath() {
        var opened: String? = null
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onOpenAgent = { opened = it.session.live?.paneId },
                    viewModel = StaticBoards.boardViewModel(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                )
            }
        }
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("board_action_copy_p1").assertIsDisplayed()
        compose.onNodeWithTag("board_action_copy_p1").performClick()
        compose.waitForIdle()
        assertTrue("copy must not also open the card", opened == null)
        val clip = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("clipboard should hold the agent cwd", "/repo/a", clip)
    }

    @Test
    fun swipeLeftRevealsCloseAndFiresIt() {
        var closed: String? = null
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onCloseAgent = { closed = it.session.live?.paneId },
                    viewModel = StaticBoards.boardViewModel(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                )
            }
        }
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("board_action_close_p1").performClick()
        compose.waitForIdle()
        // Close stops a live pane, so it asks first — same gate as Sessions.
        assertTrue("close must not fire before the confirm", closed == null)
        compose.onNodeWithText("Close agent?").assertIsDisplayed()
        capture("board-close-confirm", overlay = true)
        compose.onAllNodesWithText("Close").filterToOne(hasClickAction()).performClick()
        compose.waitForIdle()
        assertTrue("close callback should carry the pane id", closed == "p1")
    }

    @Test
    fun dismissingTheCloseConfirmLeavesTheAgentRunning() {
        var closed: String? = null
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onCloseAgent = { closed = it.session.live?.paneId },
                    viewModel = StaticBoards.boardViewModel(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                )
            }
        }
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("board_action_close_p1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue("cancelling must not close the agent", closed == null)
        compose.onNodeWithTag("agent_card_p1").assertIsDisplayed()
    }

    @Test
    fun coveredBoardActionIsNotTappable() {
        var reviewed = false
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onReviewAgent = { reviewed = true },
                    viewModel = StaticBoards.boardViewModel(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                )
            }
        }
        // With the card covering the bar, a click at the action's coordinates
        // must hit the card (opening nothing here) — never the hidden action.
        compose.onNodeWithTag("board_action_review_p1").performClick()
        compose.waitForIdle()
        assertTrue("covered action must not fire", !reviewed)
    }

    @Test
    fun revealedActionBarMatchesRowHeight() {
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    viewModel = StaticBoards.boardViewModel(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                )
            }
        }
        val card = compose.onNodeWithTag("agent_card_p1").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        val action = compose.onNodeWithTag("board_action_review_p1").getUnclippedBoundsInRoot()
        assertTrue(
            "action bar should match the card height",
            abs((card.bottom - card.top).value - (action.bottom - action.top).value) < 1f,
        )
    }

    @Test
    fun swipeDownRequestsFreshBoard() {
        val agent = blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it")
        val harness = dev.scoutr.app.state.BoardHarness(InstrumentationRegistry.getInstrumentation().targetContext)
        harness.addHost("host-a", alias = "bridge")
        val fake = harness.apiFor("host-a").apply {
            agentsResult = Result.success(AgentsResponse(agents = listOf(agent)))
        }
        val viewModel = harness.viewModel()
        viewModel.startPolling()
        compose.waitUntil(5_000) { viewModel.ui.value.hostedSessions.size == 1 }
        // Polling stays active: the swipe must trigger a real extra cycle.
        val callsBeforeSwipe = fake.calls.count { it.name == "agents" }
        compose.setContent {
            ScoutrTheme { BoardScreen(viewModel = viewModel) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("board_refresh_root").performTouchInput { swipeDown() }

        compose.waitUntil(5_000) { fake.calls.count { it.name == "agents" } > callsBeforeSwipe }
        assertTrue("pulling down should request a fresh board", fake.calls.count { it.name == "agents" } > callsBeforeSwipe)
    }

    // --- Needs-you attention -------------------------------------------------

    private fun askAttention(
        questionCount: Int = 1,
        canQuickAnswer: Boolean = true,
        multiSelect: Boolean = false,
        question: String = "Deploy to production?",
        options: List<String> = listOf("Yes", "No"),
    ) = AttentionSummary(
        kind = "ask",
        callId = "call_1",
        questionCount = questionCount,
        currentQuestion = AttentionQuestion(
            id = "q1",
            header = "Deploy",
            question = question,
            options = options.map { QuestionOption(label = it) },
            multiSelect = multiSelect,
        ),
        canQuickAnswer = canQuickAnswer,
    )

    private fun waitingAgent(attention: AttentionSummary?, activity: String? = "Waiting for you") =
        blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", activity)
            .copy(attention = attention)

    private fun showBoard(
        agent: SessionDescriptor,
        onOpenAgent: (dev.scoutr.app.state.HostedSession) -> Unit = {},
        onQuickAnswer: (dev.scoutr.app.state.HostedSession, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onOpenAgent = onOpenAgent,
                    onQuickAnswer = onQuickAnswer,
                    viewModel = StaticBoards.boardViewModel(listOf(agent)),
                )
            }
        }
    }

    @Test
    fun simpleAskShowsTheQuestionAndAnswersWithTheServerLabel() {
        var answered: Pair<String?, String>? = null
        showBoard(
            waitingAgent(askAttention()),
            onQuickAnswer = { agent, label -> answered = agent.session.live?.paneId to label },
        )

        println("SCRATCH_ASK_TREE=" + compose.onRoot().printToString().lines().filter { "Text =" in it || "Tag:" in it }.joinToString("\n"))
        compose.onNodeWithTag("board_attention_question_p1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Deploy to production?").assertIsDisplayed()
        // One question: no "N questions" line, and no Open fallback.
        compose.onNodeWithTag("board_attention_count_p1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("board_attention_open_p1").assertDoesNotExist()
        compose.onNodeWithTag("board_quick_answer_p1_Yes").assertIsDisplayed()
        compose.onNodeWithTag("board_quick_answer_p1_No").assertIsDisplayed()

        compose.onNodeWithTag("board_quick_answer_p1_Yes").performClick()
        compose.waitForIdle()
        assertEquals("the card's own agent and the server's label travel", "p1" to "Yes", answered)
    }

    @Test
    fun quickAnswerShowsAtMostThreeControls() {
        showBoard(waitingAgent(askAttention(options = listOf("A", "B", "C"))))
        compose.onNodeWithTag("board_quick_answer_p1_A").assertIsDisplayed()
        compose.onNodeWithTag("board_quick_answer_p1_B").assertIsDisplayed()
        compose.onNodeWithTag("board_quick_answer_p1_C").assertIsDisplayed()
        compose.onNodeWithTag("board_attention_open_p1").assertDoesNotExist()
    }

    @Test
    fun aFourthOptionFallsBackToOpen() {
        // Four choices do not fit a card, so the ask goes to Chat whole.
        showBoard(waitingAgent(askAttention(options = listOf("A", "B", "C", "D"))))
        compose.onNodeWithTag("board_quick_answer_p1_A").assertDoesNotExist()
        compose.onNodeWithTag("board_attention_open_p1").assertIsDisplayed()
    }

    @Test
    fun multiQuestionAskIsOpenOnly() {
        var opened: String? = null
        var answered: String? = null
        showBoard(
            waitingAgent(askAttention(questionCount = 3, canQuickAnswer = false)),
            onOpenAgent = { opened = it.session.live?.paneId },
            onQuickAnswer = { _, label -> answered = label },
        )

        compose.onNodeWithText("Deploy to production?").assertIsDisplayed()
        compose.onNodeWithTag("board_attention_count_p1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("3 questions").assertIsDisplayed()
        compose.onNodeWithTag("board_quick_answer_p1_Yes").assertDoesNotExist()

        compose.onNodeWithTag("board_attention_open_p1")
            .assertContentDescriptionContains("Open Fix billing bug in chat to answer 3 questions")
        compose.onNodeWithTag("board_attention_open_p1").performClick()
        compose.waitForIdle()
        assertEquals("Open routes the ask into Chat", "p1", opened)
        assertTrue("a complex ask never submits from the Board", answered == null)
    }

    @Test
    fun plainPromptKeepsActivityAndOffersOnlyOpen() {
        var opened: String? = null
        showBoard(
            waitingAgent(AttentionSummary(kind = "prompt"), activity = "Allow running the migration?"),
            onOpenAgent = { opened = it.session.live?.paneId },
        )

        // No structured ask: the card's own activity line is the preview and
        // the Board invents no choices.
        compose.onNodeWithText("Allow running the migration?").assertIsDisplayed()
        compose.onNodeWithTag("board_attention_question_p1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("board_attention_count_p1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("board_attention_open_p1")
            .assertContentDescriptionContains("Open Fix billing bug in chat to respond")
        compose.onNodeWithTag("board_attention_open_p1").performClick()
        compose.waitForIdle()
        assertEquals("p1", opened)
    }

    @Test
    fun longAttentionTextTruncatesForDisplayOnly() {
        val longOption = "Rebuild the whole index from scratch"
        val longQuestion = "The migration touches every table in the billing schema " +
            "and cannot be rolled back once it starts, so how should the deploy proceed tonight?"
        var answered: String? = null
        showBoard(
            waitingAgent(askAttention(question = longQuestion, options = listOf(longOption, "Skip"))),
            onQuickAnswer = { _, label -> answered = label },
        )

        // The question is bounded to two lines, so its node is no taller than
        // a two-line body row, and the button shows an elided label.
        println("SCRATCH_ASK_TREE=" + compose.onRoot().printToString().lines().filter { "Text =" in it || "Tag:" in it }.joinToString("\n"))
        compose.onNodeWithTag("board_attention_question_p1", useUnmergedTree = true).assertIsDisplayed()
        val questionHeight = compose.onNodeWithTag("board_attention_question_p1", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        assertTrue("question must stay bounded, was ${questionHeight}dp", questionHeight < 64f)
        compose.onNodeWithText("Rebuild the whole…").assertIsDisplayed()

        compose.onNodeWithTag("board_quick_answer_p1_$longOption").performClick()
        compose.waitForIdle()
        assertEquals("the untruncated server label is what would be submitted", longOption, answered)
    }

    @Test
    fun attentionControlsPreserveSwipeActions() {
        var reviewedCwd: String? = null
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onReviewAgent = { reviewedCwd = it.session.cwd },
                    viewModel = StaticBoards.boardViewModel(listOf(waitingAgent(askAttention()))),
                )
            }
        }
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("board_action_review_p1").performClick()
        compose.waitForIdle()
        assertEquals("swipe actions survive the attention block", "/repo/a", reviewedCwd)
    }


    @Test
    fun doneCardShowsDirtyRepoEvidence() {
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    viewModel = StaticBoards.boardViewModel(listOf(
                                doneAgent("p1", "Ship notes", "/repo/a", null, null, dirtySummary()),
                            )),
                )
            }
        }
        compose.onNodeWithTag("board_done_summary_p1").assertIsDisplayed()
        compose.onNodeWithText("2 files · +248 −91").assertIsDisplayed()
        compose.onNodeWithText("main · uncommitted").assertIsDisplayed()
    }

    @Test
    fun cleanDoneCardSaysWorkingTreeCleanNeverReady() {
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    viewModel = StaticBoards.boardViewModel(listOf(
                                doneAgent("p1", "Tidy up", "/repo/a", null, null, cleanSummary()),
                            )),
                )
            }
        }
        compose.onNodeWithTag("board_done_summary_p1").assertIsDisplayed()
        compose.onNodeWithText("Working tree clean").assertIsDisplayed()
        // The evidence labels facts; nothing may claim readiness or safety.
        assertTrue(compose.onAllNodesWithText("ready", substring = true, ignoreCase = true)
            .fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("safe", substring = true, ignoreCase = true)
            .fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun doneCardOmitsAheadBehindWithoutUpstreamAndShowsItWhenTracked() {
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    viewModel = StaticBoards.boardViewModel(listOf(
                                doneAgent("p1", "No upstream", "/repo/a", null, null, dirtySummary()),
                                doneAgent("p2", "Tracked", "/repo/b", null, null,
                                    dirtySummary(upstream = "origin/main", ahead = 2)),
                            )),
                )
            }
        }
        compose.onNodeWithText("main · uncommitted").assertIsDisplayed()
        compose.onNodeWithText("main · uncommitted · 2 ahead · 0 behind").assertIsDisplayed()
    }

    @Test
    fun truncatedSummaryShowsQuietIndicationInsteadOfFalseExactness() {
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    viewModel = StaticBoards.boardViewModel(listOf(
                                doneAgent("p1", "Huge diff", "/repo/a", null, null,
                                    dirtySummary(statusTruncated = true)),
                            )),
                )
            }
        }
        compose.onNodeWithText("2 files · +248 −91 · summary truncated").assertIsDisplayed()
    }

    @Test
    fun doneCardWithoutSummaryRendersNoEvidenceBlock() {
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    viewModel = StaticBoards.boardViewModel(listOf(
                                doneAgent("p1", "Non-repo work", "/not-a-repo", null, null, null),
                            )),
                )
            }
        }
        assertTrue(compose.onAllNodesWithTag("board_done_summary_p1").fetchSemanticsNodes().isEmpty())
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun doneSummaryExposesReviewThroughAccessibilityAction() {
        var reviewedCwd: String? = null
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onReviewAgent = { reviewedCwd = it.session.cwd },
                    viewModel = StaticBoards.boardViewModel(listOf(
                                doneAgent("p1", "Ship notes", "/repo/a", null, null, dirtySummary()),
                            )),
                )
            }
        }
        val summary = compose.onNodeWithTag("board_done_summary_p1")
        summary.assertContentDescriptionContains("uncommitted", substring = true)
        summary.assertContentDescriptionContains("Review changes", substring = true)
        summary.performCustomAccessibilityActionWithLabel("Review changes")
        compose.waitForIdle()
        assertEquals("the accessibility action drills into Review", "/repo/a", reviewedCwd)
    }

    private fun doneAgent(
        paneId: String,
        title: String,
        cwd: String,
        model: String?,
        activity: String?,
        summary: RepoSummary?,
    ) = blockedAgent(paneId, title, cwd, model, activity).let { descriptor ->
        descriptor.copy(live = descriptor.live?.copy(status = "done"), doneSummary = summary)
    }

    private fun dirtySummary(
        upstream: String? = null,
        ahead: Int = 0,
        behind: Int = 0,
        statusTruncated: Boolean = false,
    ) = RepoSummary(
        repoRoot = "/repo/a",
        branch = "main",
        upstream = upstream,
        ahead = ahead,
        behind = behind,
        changedFiles = 2,
        additions = 248,
        deletions = 91,
        dirty = true,
        statusTruncated = statusTruncated,
    )

    private fun cleanSummary() = RepoSummary(
        repoRoot = "/repo/a",
        branch = "main",
        dirty = false,
    )

}
