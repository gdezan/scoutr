package dev.cockpit.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.cockpit.app.data.QuestionEntry
import dev.cockpit.app.data.QuestionOption
import dev.cockpit.app.ui.components.QuestionCard
import dev.cockpit.app.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Structured question cards: shapes, answering, and answered-state recovery. */
class QuestionCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun question(
        id: String,
        multiSelect: Boolean = false,
        options: List<QuestionOption> = emptyList(),
        answered: Boolean = false,
        answerText: String? = null,
        selected: List<String> = emptyList(),
    ) = QuestionEntry(
        id = id,
        question = "Where should this live?",
        header = "Scope",
        options = options,
        multiSelect = multiSelect,
        answered = answered,
        answerText = answerText,
        selected = selected,
        timestamp = "2026-08-10T10:00:00.000Z",
    )

    private val choiceOptions = listOf(
        QuestionOption("This repo", "Handle it here"),
        QuestionOption("Other repo", "Point me elsewhere"),
        QuestionOption("Skip it", "Leave it open"),
    )

    @Test
    fun singleChoiceTapSendsOptionLabel() {
        var sent: String? = null
        compose.setContent {
            CockpitTheme {
                QuestionCard(
                    question = question("q1", options = choiceOptions),
                    sending = false,
                    onAnswer = { sent = it },
                )
            }
        }
        compose.onNodeWithText("Where should this live?").assertIsDisplayed()
        compose.onNodeWithText("Handle it here").assertIsDisplayed()
        compose.onNodeWithText("Other repo").performClick()
        assertEquals("Other repo", sent)
    }

    @Test
    fun confirmationShapeRendersTwoButtons() {
        var sent: String? = null
        compose.setContent {
            CockpitTheme {
                QuestionCard(
                    question = question("q2", options = listOf(QuestionOption("Yes"), QuestionOption("No"))),
                    sending = false,
                    onAnswer = { sent = it },
                )
            }
        }
        compose.onNodeWithTag("question_confirm_q2").performClick()
        assertEquals("Yes", sent)
    }

    @Test
    fun multiSelectJoinsSelectedLabels() {
        var sent: String? = null
        compose.setContent {
            CockpitTheme {
                QuestionCard(
                    question = question(
                        "q3",
                        multiSelect = true,
                        options = listOf(QuestionOption("Auth"), QuestionOption("Billing"), QuestionOption("Docs")),
                    ),
                    sending = false,
                    onAnswer = { sent = it },
                )
            }
        }
        // Submit is disabled with no selection.
        compose.onNodeWithTag("question_submit_q3").assertIsNotEnabled()
        compose.onNodeWithText("Auth").performClick()
        compose.onNodeWithText("Docs").performClick()
        compose.onNodeWithTag("question_submit_q3").performClick()
        assertEquals("Auth, Docs", sent)
    }

    @Test
    fun freeTextInputSendsTypedAnswer() {
        var sent: String? = null
        compose.setContent {
            CockpitTheme {
                QuestionCard(
                    question = question("q4"),
                    sending = false,
                    onAnswer = { sent = it },
                )
            }
        }
        compose.onNodeWithTag("question_input_q4").performTextInput("~/Dev/ibovasco")
        compose.onNodeWithTag("question_send_q4").performClick()
        assertEquals("~/Dev/ibovasco", sent)
    }

    @Test
    fun answeredCardShowsRecoveredAnswer() {
        compose.setContent {
            CockpitTheme {
                QuestionCard(
                    question = question(
                        "q5",
                        options = choiceOptions,
                        answered = true,
                        answerText = "Other repo",
                    ),
                    sending = false,
                    onAnswer = {},
                )
            }
        }
        compose.onNodeWithText("Answered: Other repo").assertIsDisplayed()
        // No interactive choices remain.
        compose.onNodeWithText("Handle it here").assertDoesNotExist()
    }
}
