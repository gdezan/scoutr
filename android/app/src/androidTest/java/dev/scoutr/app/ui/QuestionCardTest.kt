package dev.scoutr.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.scoutr.app.data.QuestionEntry
import dev.scoutr.app.data.QuestionOption
import dev.scoutr.app.ui.components.QuestionCard
import dev.scoutr.app.ui.theme.ScoutrTheme
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
            ScoutrTheme {
                QuestionCard(
                    question = question("q1", options = choiceOptions),
                    sending = false,
                    onAnswer = { answer, _ -> sent = answer },
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
            ScoutrTheme {
                QuestionCard(
                    question = question("q2", options = listOf(QuestionOption("Yes"), QuestionOption("No"))),
                    sending = false,
                    onAnswer = { answer, _ -> sent = answer },
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
            ScoutrTheme {
                QuestionCard(
                    question = question(
                        "q3",
                        multiSelect = true,
                        options = listOf(QuestionOption("Auth"), QuestionOption("Billing"), QuestionOption("Docs")),
                    ),
                    sending = false,
                    onAnswer = { answer, _ -> sent = answer },
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
            ScoutrTheme {
                QuestionCard(
                    question = question("q4"),
                    sending = false,
                    onAnswer = { answer, _ -> sent = answer },
                )
            }
        }
        compose.onNodeWithTag("question_input_q4").performTextInput("~/Dev/ibovasco")
        compose.onNodeWithTag("question_send_q4").performClick()
        assertEquals("~/Dev/ibovasco", sent)
    }

    @Test
    fun answeredQuestionRendersAsUserBubble() {
        compose.setContent {
            ScoutrTheme {
                QuestionCard(
                    question = question(
                        "q5",
                        options = choiceOptions,
                        answered = true,
                        answerText = "Other repo",
                    ),
                    sending = false,
                    onAnswer = { _, _ -> },
                )
            }
        }
        // The card is dismissed: the answer appears as a user-style bubble
        // (tag question_answer_<id>) and no interactive choices remain.
        compose.onNodeWithTag("question_answer_q5").assertIsDisplayed()
        compose.onNodeWithText("Other repo").assertIsDisplayed()
        compose.onNodeWithTag("question_card_q5").assertDoesNotExist()
        compose.onNodeWithText("Handle it here").assertDoesNotExist()
    }

    @Test
    fun answeredMultiSelectJoinsSelectedLabels() {
        compose.setContent {
            ScoutrTheme {
                QuestionCard(
                    question = question(
                        "q6",
                        multiSelect = true,
                        options = choiceOptions,
                        answered = true,
                        answerText = null,
                        selected = listOf("Auth", "Docs"),
                    ),
                    sending = false,
                    onAnswer = { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag("question_answer_q6").assertIsDisplayed()
        compose.onNodeWithText("Auth, Docs").assertIsDisplayed()
    }
}
