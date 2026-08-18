package dev.scoutr.app.ui

import dev.scoutr.app.data.AttentionQuestion
import dev.scoutr.app.data.AttentionSummary
import dev.scoutr.app.data.QuestionOption
import dev.scoutr.app.ui.screens.attentionOpenDescription
import dev.scoutr.app.ui.screens.attentionQuestionCountLabel
import dev.scoutr.app.ui.screens.attentionQuestionText
import dev.scoutr.app.ui.screens.quickAnswerLabel
import dev.scoutr.app.ui.screens.quickAnswerOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a needs-you card applies to the bridge's attention summary: what it
 * previews, when it may offer one-tap answers, and how it shortens for display
 * without touching what would be submitted.
 */
class BoardAttentionTest {

    private fun ask(
        questionCount: Int = 1,
        canQuickAnswer: Boolean = true,
        multiSelect: Boolean = false,
        question: String = "Deploy to production?",
        header: String = "Deploy",
        options: List<String> = listOf("Yes", "No"),
    ) = AttentionSummary(
        kind = "ask",
        callId = "call_1",
        questionCount = questionCount,
        currentQuestion = AttentionQuestion(
            id = "q1",
            header = header,
            question = question,
            options = options.map { QuestionOption(label = it) },
            multiSelect = multiSelect,
        ),
        canQuickAnswer = canQuickAnswer,
    )

    private val prompt = AttentionSummary(kind = "prompt", canQuickAnswer = false)

    @Test
    fun previewsTheOpenQuestion() {
        assertEquals("Deploy to production?", attentionQuestionText(ask()))
        // A question with no prose falls back to its header rather than blank space.
        assertEquals("Deploy", attentionQuestionText(ask(question = "   ")))
        assertNull(attentionQuestionText(ask(question = "  ", header = " ")))
    }

    @Test
    fun promptAttentionHasNoQuestionToPreview() {
        // A blocked pane with no structured ask keeps latest activity as its
        // only preview; the Board never invents question text.
        assertNull(attentionQuestionText(prompt))
        assertNull(attentionQuestionText(null))
        assertTrue(quickAnswerOptions(prompt).isEmpty())
    }

    @Test
    fun questionCountShowsOnlyWhenMoreRemain() {
        assertNull(attentionQuestionCountLabel(ask(questionCount = 1)))
        assertEquals("3 questions", attentionQuestionCountLabel(ask(questionCount = 3, canQuickAnswer = false)))
        assertNull(attentionQuestionCountLabel(prompt))
    }

    @Test
    fun quickAnswerOffersTheServersOwnOptions() {
        val options = quickAnswerOptions(ask(options = listOf("Yes", "No", "Ask me later")))
        assertEquals(listOf("Yes", "No", "Ask me later"), options.map { it.label })
    }

    @Test
    fun quickAnswerRefusesAnythingItCannotSubmitWhole() {
        assertTrue("bridge veto wins", quickAnswerOptions(ask(canQuickAnswer = false)).isEmpty())
        assertTrue("multi-question rounds go to Chat", quickAnswerOptions(ask(questionCount = 2)).isEmpty())
        assertTrue("multi-select goes to Chat", quickAnswerOptions(ask(multiSelect = true)).isEmpty())
        assertTrue("free text goes to Chat", quickAnswerOptions(ask(options = emptyList())).isEmpty())
        assertTrue(
            "more than three options does not fit a card",
            quickAnswerOptions(ask(options = listOf("A", "B", "C", "D"))).isEmpty(),
        )
    }

    @Test
    fun optionLabelsTruncateForDisplayOnly() {
        val long = "Rebuild the whole index from scratch"
        val shown = quickAnswerLabel(long)
        assertTrue("display label is bounded", shown.length <= 18)
        assertTrue("truncation is visible", shown.endsWith("…"))
        // The option itself is untouched, so the submitted label stays exact.
        assertEquals(long, quickAnswerOptions(ask(options = listOf(long))).single().label)
        assertEquals("Yes", quickAnswerLabel("  Yes  "))
    }

    @Test
    fun openAffordanceNamesWhatItOpens() {
        assertEquals(
            "Open Fix billing bug in chat to answer 3 questions",
            attentionOpenDescription(ask(questionCount = 3), "Fix billing bug"),
        )
        assertEquals(
            "Open Fix billing bug in chat to answer",
            attentionOpenDescription(ask(canQuickAnswer = false), "Fix billing bug"),
        )
        assertEquals(
            "Open Fix billing bug in chat to respond",
            attentionOpenDescription(prompt, "Fix billing bug"),
        )
    }
}
