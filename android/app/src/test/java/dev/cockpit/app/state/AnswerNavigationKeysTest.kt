package dev.cockpit.app.state

import dev.cockpit.app.data.QuestionEntry
import dev.cockpit.app.data.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerNavigationKeysTest {

    private fun q(
        id: String,
        callId: String,
        options: List<String>,
        multiSelect: Boolean = false,
    ) = QuestionEntry(
        id = id,
        callId = callId,
        entryId = "entry-$id",
        question = "Q $id",
        options = options.map { QuestionOption(label = it, description = "") },
        multiSelect = multiSelect,
    )

    private fun group(vararg ids: String, callId: String = "call1"): List<QuestionEntry> =
        ids.map { id -> q(id, callId, listOf("A", "B", "C")) }

    // ---- single-choice option selection ----

    @Test
    fun firstOptionNeedsNoNavigation() {
        val keys = answerNavigationKeys(q("call1#0", "call1", listOf("A", "B", "C")), group("call1#0"), null, "A", listOf("A"))
        assertEquals(listOf("enter"), keys.keys)
        assertTrue(!keys.custom)
    }

    @Test
    fun thirdOptionNavigatesDownTwice() {
        val keys = answerNavigationKeys(q("call1#0", "call1", listOf("A", "B", "C")), group("call1#0"), null, "C", listOf("C"))
        assertEquals(listOf("down", "down", "enter"), keys.keys)
        assertTrue(!keys.custom)
    }

    @Test
    fun secondOptionAfterAnsweringFirstNavigatesFromAdvancedTab() {
        // After answering q0 the questionnaire sits on tab 1; answering q1
        // requires no navigation, answering q0 again wraps through review.
        val first = answerNavigationKeys(q("call1#0", "call1", listOf("A", "B", "C")), group("call1#0"), null, "A", listOf("A"))
        assertEquals(listOf("enter"), first.keys)
        val second = answerNavigationKeys(q("call1#1", "call1", listOf("A", "B", "C")), group("call1#0", "call1#1"), 0, "B", listOf("B"))
        assertEquals(listOf("down", "enter", "enter"), second.keys)
    }

    @Test
    fun outOfOrderAnswerWrapsThroughReviewTab() {
        // Answer q2 first (lastIndex 2 -> review tab), then q0: one tab back
        // through the review tab wrap.
        val keys = answerNavigationKeys(q("call1#0", "call1", listOf("A", "B", "C")), group("call1#0", "call1#1", "call1#2"), 2, "A", listOf("A"))
        assertEquals(listOf("tab", "enter"), keys.keys)
    }

    @Test
    fun lastQuestionOfMultiAskAddsReviewSubmitEnter() {
        val keys = answerNavigationKeys(q("call1#1", "call1", listOf("A", "B", "C")), group("call1#0", "call1#1"), 0, "C", listOf("C"))
        assertEquals(listOf("down", "down", "enter", "enter"), keys.keys)
    }

    @Test
    fun singleQuestionAskHasNoReviewEnter() {
        val keys = answerNavigationKeys(q("call1#0", "call1", listOf("A", "B", "C")), group("call1#0"), null, "B", listOf("B"))
        assertEquals(listOf("down", "enter"), keys.keys)
    }

    // ---- custom answers ----

    @Test
    fun customAnswerOpensTypeSomethingEditor() {
        // Type something is the first entry after the authored options.
        val keys = answerNavigationKeys(q("call1#0", "call1", listOf("A", "B", "C")), group("call1#0"), null, "Mango", emptyList())
        assertEquals(listOf("down", "down", "down", "enter"), keys.keys)
        assertTrue(keys.custom)
        assertEquals(listOf("enter"), keys.trailingKeys)
    }

    @Test
    fun customAnswerOnLastQuestionSubmitsEditorAndReview() {
        val keys = answerNavigationKeys(q("call1#1", "call1", listOf("A", "B", "C")), group("call1#0", "call1#1"), 0, "Mango", emptyList())
        assertEquals(listOf("down", "down", "down", "enter"), keys.keys)
        assertTrue(keys.custom)
        assertEquals(listOf("enter", "enter"), keys.trailingKeys)
    }

    @Test
    fun freeTextQuestionEditorIsFirstOption() {
        val free = q("call1#0", "call1", emptyList())
        val keys = answerNavigationKeys(free, listOf(free), null, "Anything", emptyList())
        assertEquals(listOf("enter"), keys.keys)
        assertTrue(keys.custom)
    }

    @Test
    fun unknownLabelFallsBackToCustom() {
        val keys = answerNavigationKeys(q("call1#0", "call1", listOf("A", "B", "C")), group("call1#0"), null, "Zebra", listOf("Zebra"))
        assertTrue(keys.custom)
    }

    // ---- multi-select ----

    @Test
    fun multiSelectTogglesRelativeAndSubmits() {
        val question = q("call1#0", "call1", listOf("A", "B", "C"), multiSelect = true)
        val keys = answerNavigationKeys(question, listOf(question), null, "A, C", listOf("A", "C"))
        // Index 0 needs no navigation before its space toggle.
        assertEquals(listOf("space", "down", "down", "space", "enter"), keys.keys)
    }

    @Test
    fun multiSelectOutOfOrderSelectionIsSorted() {
        val question = q("call1#0", "call1", listOf("A", "B", "C"), multiSelect = true)
        val keys = answerNavigationKeys(question, listOf(question), null, "C, A", listOf("C", "A"))
        assertEquals(listOf("space", "down", "down", "space", "enter"), keys.keys)
    }

    @Test
    fun multiSelectSelectionOrderIsAscendingOnly() {
        val question = q("call1#0", "call1", listOf("A", "B", "C"), multiSelect = true)
        val keys = answerNavigationKeys(question, listOf(question), null, "C, B", listOf("C", "B"))
        // Sorted indices (B=1, C=2) move only down, never back up.
        assertEquals(listOf("down", "space", "down", "space", "enter"), keys.keys)
    }

    // ---- helpers ----

    @Test
    fun questionGroupFiltersByCallId() {
        val all = listOf(
            q("call1#0", "call1", listOf("A")),
            q("call1#1", "call1", listOf("A")),
            q("call2#0", "call2", listOf("A")),
        )
        assertEquals(listOf("call1#0", "call1#1"), questionGroup(all, all[0]).map { it.id })
    }

    @Test
    fun questionGroupFallsBackToSingleWhenCallIdUnknown() {
        val legacy = QuestionEntry(id = "q1", callId = "", entryId = "e1", question = "Q")
        assertEquals(listOf("q1"), questionGroup(listOf(legacy), legacy).map { it.id })
    }

    @Test
    fun pruneDropsRunsWhoseGroupIsFullyAnswered() {
        val progress = mapOf(
            "call1" to QuestionnaireRun(answered = setOf("call1#0", "call1#1"), lastIndex = 1),
            "call2" to QuestionnaireRun(answered = setOf("call2#0"), lastIndex = 0),
            "gone" to QuestionnaireRun(answered = setOf("gone#0"), lastIndex = 0),
        )
        val questions = listOf(
            q("call1#0", "call1", listOf("A"), ).copy(answered = true),
            q("call1#1", "call1", listOf("A"), ).copy(answered = true),
            q("call2#0", "call2", listOf("A")),
        )
        val pruned = pruneQuestionnaireProgress(progress, questions)
        assertEquals(listOf("call2"), pruned.keys.toList())
    }

    @Test
    fun pruneKeepsRunsWithAnyUnansweredQuestion() {
        val progress = mapOf("call1" to QuestionnaireRun(answered = setOf("call1#0"), lastIndex = 0))
        val questions = listOf(
            q("call1#0", "call1", listOf("A"), ).copy(answered = true),
            q("call1#1", "call1", listOf("A")),
        )
        assertEquals(mapOf("call1" to progress["call1"]), pruneQuestionnaireProgress(progress, questions))
    }
}
