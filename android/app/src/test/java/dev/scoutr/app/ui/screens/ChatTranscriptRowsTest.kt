package dev.scoutr.app.ui.screens

import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.QuestionEntry
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.state.PendingUserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reverse-history row placement: answered questions wait for their anchor
 * entry; only still-open asks may sit without a loaded transcript row.
 */
class ChatTranscriptRowsTest {

    private fun entry(id: String) = SessionEntry(
        entryId = id,
        role = "assistant",
        content = listOf(ContentBlock(type = "text", text = id)),
    )

    private fun question(
        id: String,
        entryId: String,
        answered: Boolean,
        callId: String = "call-$entryId",
    ) = QuestionEntry(
        id = id,
        callId = callId,
        entryId = entryId,
        question = id,
        answered = answered,
        answerText = if (answered) "yes" else null,
    )

    @Test
    fun answeredQuestionWithoutAnchorIsOmitted() {
        val rows = buildChatRows(
            entries = listOf(entry("e2")),
            pendingMessages = emptyList(),
            questions = listOf(question("q1", entryId = "e1", answered = true)),
            indicatorMode = null,
        )
        assertEquals(listOf("e2"), rows.filterIsInstance<ChatRow.Entry>().map { it.entry.entryId })
        assertTrue(rows.filterIsInstance<ChatRow.Questions>().isEmpty())
    }

    @Test
    fun openQuestionWithoutAnchorStaysAtTheTop() {
        val rows = buildChatRows(
            entries = listOf(entry("e2")),
            pendingMessages = emptyList(),
            questions = listOf(question("q1", entryId = "e1", answered = false)),
            indicatorMode = null,
        )
        val kinds = rows.map {
            when (it) {
                is ChatRow.Entry -> "entry:${it.entry.entryId}"
                is ChatRow.Questions -> "ask:${it.group.first().id}"
                is ChatRow.Pending -> "pending"
                is ChatRow.Indicator -> "indicator"
            }
        }
        assertEquals(listOf("entry:e2", "ask:q1"), kinds)
    }

    @Test
    fun answeredQuestionAppearsAfterItsAnchorOnceLoaded() {
        val rows = buildChatRows(
            entries = listOf(entry("e1"), entry("e2")),
            pendingMessages = emptyList(),
            questions = listOf(question("q1", entryId = "e1", answered = true)),
            indicatorMode = null,
        )
        val kinds = rows.map {
            when (it) {
                is ChatRow.Entry -> "entry:${it.entry.entryId}"
                is ChatRow.Questions -> "ask:${it.group.first().id}"
                is ChatRow.Pending -> "pending"
                is ChatRow.Indicator -> "indicator"
            }
        }
        assertEquals(listOf("entry:e1", "ask:q1", "entry:e2"), kinds)
    }

    @Test
    fun pendingRowStaysAtTheTail() {
        val rows = buildChatRows(
            entries = listOf(entry("e1")),
            pendingMessages = listOf(
                PendingUserMessage(localId = "p1", text = "hi", state = dev.scoutr.app.state.MessageDeliveryState.QUEUED),
            ),
            questions = emptyList(),
            indicatorMode = null,
        )
        assertTrue(rows.last() is ChatRow.Pending)
    }
}
