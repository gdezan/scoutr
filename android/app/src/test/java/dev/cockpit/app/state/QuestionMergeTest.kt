package dev.cockpit.app.state

import dev.cockpit.app.data.QuestionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionMergeTest {

    private fun question(
        id: String,
        answered: Boolean = false,
        timestamp: String = "2026-08-10T10:00:00.000Z",
    ) = QuestionEntry(id = id, question = "Q $id", answered = answered, timestamp = timestamp)

    @Test
    fun mergeAppendsNewQuestions() {
        val merged = mergeQuestions(listOf(question("a")), listOf(question("b")))
        assertEquals(listOf("a", "b"), merged.map { it.id })
    }

    @Test
    fun mergeUpsertsByStableIdWhenCursorResets() {
        val existing = listOf(question("a"), question("b"))
        val incoming = listOf(
            question("a"),
            question("b", answered = true),
            question("c"),
        )
        val merged = mergeQuestions(existing, incoming)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertTrue(merged[1].answered)
    }

    @Test
    fun mergeKeepsExistingWhenIncomingIsEmpty() {
        val existing = listOf(question("a"))
        assertEquals(existing, mergeQuestions(existing, emptyList()))
    }

    @Test
    fun mergeSortsByTimestamp() {
        val merged = mergeQuestions(
            listOf(question("late", timestamp = "2026-08-10T12:00:00.000Z")),
            listOf(question("early", timestamp = "2026-08-10T09:00:00.000Z")),
        )
        assertEquals(listOf("early", "late"), merged.map { it.id })
    }

    @Test
    fun onlyUnansweredQuestionsCountAsPending() {
        // The working indicator steps aside for a question card, but only
        // while one is actually pending: answered questions stay in the list
        // for the rest of the session as answer bubbles, so treating "any
        // question at all" as pending would silence the indicator forever.
        assertEquals(false, ChatUiState().hasPendingQuestion)
        assertEquals(
            true,
            ChatUiState(questions = listOf(question("a"))).hasPendingQuestion,
        )
        assertEquals(
            false,
            ChatUiState(questions = listOf(question("a", answered = true))).hasPendingQuestion,
        )
        assertEquals(
            true,
            ChatUiState(
                questions = listOf(question("a", answered = true), question("b")),
            ).hasPendingQuestion,
        )
    }

    @Test
    fun sanitizeCollapsesNewlinesAndStripsControlChars() {
        assertEquals("yes", sanitizeAnswerText("  yes  "))
        assertEquals("line1 line2", sanitizeAnswerText("line1\nline2"))
        assertEquals("abc", sanitizeAnswerText("a\u0000b\u0001c"))
        assertEquals(4000, sanitizeAnswerText("x".repeat(5000)).length)
        assertEquals("", sanitizeAnswerText("\n\n \n"))
    }
}
