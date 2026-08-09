package dev.cockpit.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    private fun card(status: String, paneId: String = "w1:p1") = AgentCard(
        paneId = paneId,
        workspaceId = "w1",
        tabId = "w1:t1",
        agent = "pi",
        status = status,
        cwd = "/tmp/x",
    )

    @Test
    fun `group places cards into status buckets`() {
        val board = BoardState.group(
            listOf(
                card("blocked", "a"),
                card("working", "b"),
                card("done", "c"),
                card("idle", "d"),
                card("unknown", "e"),
            ),
        )
        assertEquals(1, board.needsYou.size)
        assertEquals(1, board.working.size)
        assertEquals(1, board.done.size)
        assertEquals(1, board.idle.size)
        assertEquals(1, board.unknown.size)
        assertEquals(5, board.total)
        assertEquals("a", board.needsYou[0].paneId)
    }

    @Test
    fun `empty list produces empty board`() {
        val board = BoardState.group(emptyList())
        assertEquals(0, board.total)
        assertTrue(board.needsYou.isEmpty())
        assertTrue(board.working.isEmpty())
    }

    @Test
    fun `blocked flag matches status`() {
        assertTrue(card("blocked").blocked)
        assertFalse(card("working").blocked)
    }

    @Test
    fun `AgentStatus parses wire names and falls back to unknown`() {
        assertEquals(AgentStatus.NeedsYou, AgentStatus.fromWire("blocked"))
        assertEquals(AgentStatus.Working, AgentStatus.fromWire("working"))
        assertEquals(AgentStatus.Done, AgentStatus.fromWire("done"))
        assertEquals(AgentStatus.Unknown, AgentStatus.fromWire("nonsense"))
    }

    @Test
    fun `entryText flattens text and toolCall blocks`() {
        val content = listOf(
            ContentBlock(type = "thinking", thinking = "skip me"),
            ContentBlock(type = "toolCall", name = "bash"),
            ContentBlock(type = "text", text = "  hello\nworld "),
        )
        assertEquals("[bash] hello world", entryText(content))
    }
}
