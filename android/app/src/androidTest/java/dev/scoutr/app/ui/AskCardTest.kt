package dev.scoutr.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.scoutr.app.data.QuestionEntry
import dev.scoutr.app.data.QuestionOption
import dev.scoutr.app.state.AskDraft
import dev.scoutr.app.state.DraftAnswer
import dev.scoutr.app.ui.components.AskCard
import dev.scoutr.app.ui.theme.ScoutrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The single ask card: question shapes, moving between questions of one round,
 * and the rule that nothing is sent until the whole round is complete.
 */
class AskCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun question(
        id: String,
        header: String = "Scope",
        text: String = "Where should this live?",
        multiSelect: Boolean = false,
        options: List<QuestionOption> = emptyList(),
    ) = QuestionEntry(
        id = id,
        callId = "call1",
        question = text,
        header = header,
        options = options,
        multiSelect = multiSelect,
        answered = false,
        answerText = null,
        selected = emptyList(),
        timestamp = "2026-08-10T10:00:00.000Z",
    )

    private val choiceOptions = listOf(
        QuestionOption("This repo", "Handle it here"),
        QuestionOption("Other repo", "Point me elsewhere"),
        QuestionOption("Skip it", "Leave it open"),
    )

    /**
     * Drives the card the way the view model does: answers and the page live
     * outside it, so the test sees exactly the state the real screen would.
     */
    private fun setCard(
        group: List<QuestionEntry>,
        submitting: Boolean = false,
        submitIsSlow: Boolean = false,
        onSubmit: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ): () -> AskDraft {
        var draft by mutableStateOf(AskDraft())
        compose.setContent {
            ScoutrTheme {
                AskCard(
                    group = group,
                    draft = draft,
                    submitting = submitting,
                    submitIsSlow = submitIsSlow,
                    error = null,
                    onAnswer = { id, answer -> draft = draft.copy(answers = draft.answers + (id to answer)) },
                    onPage = { page -> draft = draft.copy(page = page) },
                    onSubmit = onSubmit,
                    onDismiss = onDismiss,
                )
            }
        }
        return { draft }
    }

    @Test
    fun singleChoiceTapAnswersInOneTap() {
        var submitted = false
        val draft = setCard(listOf(question("q1", options = choiceOptions)), onSubmit = { submitted = true })
        compose.onNodeWithText("Where should this live?").assertIsDisplayed()
        compose.onNodeWithText("Handle it here").assertIsDisplayed()
        compose.onNodeWithText("Other repo").performClick()
        assertEquals(listOf("Other repo"), draft().answerFor("q1").labels)
        // A lone single-select keeps the one-tap shortcut: no Submit step.
        assertTrue(submitted)
    }

    @Test
    fun confirmationShapeRendersTwoButtons() {
        val options = listOf(QuestionOption("Yes"), QuestionOption("No"))
        val draft = setCard(listOf(question("q2", options = options)))
        compose.onNodeWithTag("ask_option_q2_Yes").performClick()
        assertEquals(listOf("Yes"), draft().answerFor("q2").labels)
    }

    @Test
    fun multiSelectAccumulatesLabels() {
        val options = listOf(QuestionOption("Auth"), QuestionOption("Billing"), QuestionOption("Docs"))
        val draft = setCard(listOf(question("q3", multiSelect = true, options = options)))
        compose.onNodeWithText("Auth").performClick()
        compose.onNodeWithText("Docs").performClick()
        assertEquals(listOf("Auth", "Docs"), draft().answerFor("q3").labels)
        // Tapping a checked row again clears it.
        compose.onNodeWithText("Auth").performClick()
        assertEquals(listOf("Docs"), draft().answerFor("q3").labels)
    }

    @Test
    fun freeTextGoesIntoTheDraft() {
        val draft = setCard(listOf(question("q4")))
        compose.onNodeWithTag("ask_input_q4").performTextInput("~/Dev/ibovasco")
        assertEquals("~/Dev/ibovasco", draft().answerFor("q4").text)
    }

    @Test
    fun typingClearsAPickBecauseTheQuestionnaireCannotCarryBoth() {
        val draft = setCard(listOf(question("q5", options = choiceOptions)))
        compose.onNodeWithText("Other repo").performClick()
        compose.onNodeWithText("Type something").performClick()
        compose.onNodeWithTag("ask_input_q5").performTextInput("elsewhere")
        assertEquals(emptyList<String>(), draft().answerFor("q5").labels)
        assertEquals("elsewhere", draft().answerFor("q5").text)
    }

    @Test
    fun aRoundStaysEditableUntilItIsSubmitted() {
        val group = listOf(
            question("q1", header = "Colour", text = "Which colour?", options = choiceOptions),
            question("q2", header = "Size", text = "Which size?", options = choiceOptions),
        )
        var submitted = false
        val draft = setCard(group, onSubmit = { submitted = true })

        compose.onNodeWithText("Other repo").performClick()
        // Answering does not send: the round is buffered, so the card moves on
        // only when the user asks it to.
        assertTrue(!submitted)
        compose.onNodeWithTag("ask_next_call1").performClick()
        compose.onNodeWithText("Which size?").assertIsDisplayed()

        // Back to the first question — and its answer is still changeable,
        // which is the whole point of buffering the round.
        compose.onNodeWithTag("ask_back_call1").performClick()
        compose.onNodeWithText("Which colour?").assertIsDisplayed()
        compose.onNodeWithText("Skip it").performClick()
        assertEquals(listOf("Skip it"), draft().answerFor("q1").labels)
        assertTrue(!submitted)
    }

    @Test
    fun submitIsDisabledUntilEveryQuestionHasAnAnswer() {
        val group = listOf(
            question("q1", header = "Colour", text = "Which colour?", options = choiceOptions),
            question("q2", header = "Size", text = "Which size?", options = choiceOptions),
        )
        var submitted = false
        setCard(group, onSubmit = { submitted = true })

        compose.onNodeWithTag("ask_next_call1").performClick()
        // On the last question the footer offers Submit, but the first
        // question is still blank and the review tab will not take a gap.
        compose.onNodeWithTag("ask_submit_call1").assertIsNotEnabled()
        compose.onNodeWithText("Skip it").performClick()
        compose.onNodeWithTag("ask_submit_call1").assertIsNotEnabled()

        // Jump back via the chip row and fill the gap.
        compose.onNodeWithTag("ask_chip_q1").performClick()
        compose.onNodeWithText("Other repo").performClick()
        compose.onNodeWithTag("ask_chip_q2").performClick()
        compose.onNodeWithTag("ask_submit_call1").assertIsEnabled()
        compose.onNodeWithTag("ask_submit_call1").performClick()
        assertTrue(submitted)
    }

    @Test
    fun aLoneQuestionShowsNoNavigationChrome() {
        setCard(listOf(question("q1", options = choiceOptions)))
        compose.onNodeWithTag("ask_next_call1").assertDoesNotExist()
        compose.onNodeWithTag("ask_back_call1").assertDoesNotExist()
        compose.onNodeWithTag("ask_submit_call1").assertDoesNotExist()
        compose.onNodeWithTag("ask_chip_q1").assertDoesNotExist()
    }

    @Test
    fun dismissIsOfferedUntilTheRoundIsInFlight() {
        var dismissed = false
        setCard(listOf(question("q1", options = choiceOptions)), onDismiss = { dismissed = true })
        compose.onNodeWithTag("ask_dismiss_call1").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun aSubmittingCardIsLockedAndSaysSo() {
        val draft = setCard(listOf(question("q1", options = choiceOptions)), submitting = true)
        compose.onNodeWithText("Sending…").assertIsDisplayed()
        // No dismiss while the keystrokes are in flight, and taps do nothing:
        // the round is already on its way to the agent.
        compose.onNodeWithTag("ask_dismiss_call1").assertDoesNotExist()
        compose.onNodeWithText("Other repo").performClick()
        assertNull(draft().answers["q1"])
    }

    @Test
    fun aStalledRoundOffersDismissAgain() {
        // A round that never resolves would otherwise hold the composer shut
        // for the rest of the session, so the way out comes back.
        var dismissed = false
        setCard(
            listOf(question("q1", options = choiceOptions)),
            submitting = true,
            submitIsSlow = true,
            onDismiss = { dismissed = true },
        )
        compose.onNodeWithText("No response yet").assertIsDisplayed()
        compose.onNodeWithTag("ask_dismiss_call1").performClick()
        assertTrue(dismissed)
    }
}
