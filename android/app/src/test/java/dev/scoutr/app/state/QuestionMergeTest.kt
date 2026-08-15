package dev.scoutr.app.state

import dev.scoutr.app.data.QuestionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionMergeTest {

    private fun question(
        id: String,
        answered: Boolean = false,
        timestamp: String = "2026-08-10T10:00:00.000Z",
        callId: String = "call",
    ) = QuestionEntry(
        id = id,
        callId = callId,
        question = "Q $id",
        answered = answered,
        timestamp = timestamp,
    )

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
    fun aCardStaysOpenUntilTheTranscriptRecordsTheAnswer() {
        // Nothing is overlaid locally: the round is shown as delivered only
        // once its toolResult lands, so the card never claims an answer the
        // agent may not have received.
        val state = ChatUiState(questions = listOf(question("a"), question("b")))
        assertTrue(state.questionCards.none { it.answered })
        assertTrue(state.hasPendingQuestion)
    }

    @Test
    fun openAsksGroupEveryQuestionOfOneCallIntoOneRound() {
        val state = ChatUiState(
            questions = listOf(
                question("a"),
                question("b"),
                question("c").copy(callId = "other"),
            ),
        )
        val rounds = state.openAsks
        assertEquals(2, rounds.size)
        assertEquals(listOf("a", "b"), rounds.first { it.size == 2 }.map { it.id })
    }

    @Test
    fun anAnsweredAskLeavesNoPendingQuestion() {
        val state = ChatUiState(questions = listOf(question("a", answered = true)))
        assertEquals(false, state.hasPendingQuestion)
        assertTrue(state.openAsks.isEmpty())
    }

    @Test
    fun aDraftIsCompleteOnlyWhenEveryQuestionHasAPickOrText() {
        val group = listOf(question("a"), question("b"))
        val partial = AskDraft(answers = mapOf("a" to DraftAnswer(labels = listOf("Yes"))))
        // Submit stays disabled here: the review tab will not accept a gap.
        assertEquals(false, partial.isComplete(group))
        val full = AskDraft(
            answers = mapOf(
                "a" to DraftAnswer(labels = listOf("Yes")),
                "b" to DraftAnswer(text = "something else"),
            ),
        )
        assertTrue(full.isComplete(group))
        // Blank text is not an answer, however it got there.
        assertEquals(false, full.copy(answers = full.answers + ("b" to DraftAnswer(text = "   "))).isComplete(group))
    }

    @Test
    fun draftsAreDroppedOnceTheirAskIsAnswered() {
        val drafts = mapOf("call" to AskDraft(), "gone" to AskDraft())
        val pruned = pruneAskDrafts(drafts, listOf(question("a", answered = true)))
        // Neither ask is open any more, so no draft survives to be re-shown.
        assertTrue(pruned.isEmpty())
    }

    @Test
    fun aDraftSurvivesTheRoundTripThroughSavedState() {
        val drafts = mapOf(
            "call" to AskDraft(
                page = 1,
                answers = mapOf(
                    "a" to DraftAnswer(labels = listOf("Yes", "No")),
                    "b" to DraftAnswer(text = "with, a comma"),
                ),
            ),
        )
        assertEquals(drafts, decodeAskDrafts(encodeAskDrafts(drafts)))
        assertEquals(emptyMap<String, AskDraft>(), decodeAskDrafts(""))
    }

    @Test
    fun aDismissedAskStopsCountingAsPending() {
        // The transcript keeps listing a dismissed ask as unanswered — nothing
        // writes a cancelled question back as answered — so the app's own
        // record of the dismissal is what unlocks the composer.
        val state = ChatUiState(
            questions = listOf(question("a"), question("b")),
            dismissedCallIds = setOf("call"),
        )
        assertEquals(false, state.hasPendingQuestion)
        assertTrue(state.openAsks.isEmpty())
        assertTrue(state.questionCards.isEmpty())
    }

    @Test
    fun dismissingOneAskLeavesAnotherOpen() {
        val state = ChatUiState(
            questions = listOf(question("a"), question("c").copy(callId = "other")),
            dismissedCallIds = setOf("call"),
        )
        assertTrue(state.hasPendingQuestion)
        assertEquals(listOf(listOf("c")), state.openAsks.map { round -> round.map { it.id } })
    }

    @Test
    fun aDismissedAskThatTurnsOutAnsweredStillShowsItsBubble() {
        // Dismiss and an answer in the terminal can cross. If the answer wins,
        // the transcript's version is the truth and belongs on screen.
        val state = ChatUiState(
            questions = listOf(question("a", answered = true)),
            dismissedCallIds = setOf("call"),
        )
        assertEquals(listOf("a"), state.questionCards.map { it.id })
        assertEquals(false, state.hasPendingQuestion)
    }

    @Test
    fun dismissalsAreForgottenOnceTheirAskCloses() {
        // mergeQuestions upserts and never removes, so without pruning the set
        // would grow for the life of the screen.
        assertEquals(
            emptySet<String>(),
            pruneDismissedAsks(setOf("call"), listOf(question("a", answered = true))),
        )
        assertEquals(
            setOf("call"),
            pruneDismissedAsks(setOf("call", "stale"), listOf(question("a"))),
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
