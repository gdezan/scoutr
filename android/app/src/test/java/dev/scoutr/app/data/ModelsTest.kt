package dev.scoutr.app.data

import kotlinx.serialization.json.Json
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
        assertEquals(AgentStatus.Done, AgentStatus.fromWire("completed"))
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
    @Test
    fun `session catalog item decodes from the bridge shape`() {
        // Regression: the DTO once expected `sessionId` while the bridge sends
        // `id`, so every catalog response silently failed to decode and the
        // Sessions screen stayed empty. Pin the real wire shape.
        val json = """
            {"id":"019f-abc","path":"/home/u/.pi/agent/sessions/--tmp--/x.jsonl",
             "cwd":"/tmp","title":"Reply with a demo",
             "preview":"Reply with a demo","createdAt":1786392951904,
             "updatedAt":1786392956741.0754,"model":"opencode-go/deepseek-v4-flash",
             "active":false,"paneId":null,"workspaceId":null,"status":"completed"}
        """.trimIndent()
        val item = Json.decodeFromString(SessionCatalogItem.serializer(), json)
        assertEquals("019f-abc", item.id)
        assertEquals("/tmp", item.cwd)
        assertFalse(item.active)
        assertEquals("completed", item.status)
    }

    @Test
    fun `session catalog response decodes a full payload`() {
        val json = """
            {"ok":true,"truncated":false,"sessions":[
              {"id":"a","path":"/p/a.jsonl","cwd":"/a","title":"t","preview":"",
               "createdAt":1,"updatedAt":2.5,"model":null,"active":true,
               "paneId":"w1:p1","workspaceId":"w1","status":"working"}
            ]}
        """.trimIndent()
        val response = Json.decodeFromString(SessionCatalogResponse.serializer(), json)
        assertEquals(1, response.sessions.size)
        assertTrue(response.sessions[0].active)
        assertEquals("w1:p1", response.sessions[0].paneId)
    }
}
