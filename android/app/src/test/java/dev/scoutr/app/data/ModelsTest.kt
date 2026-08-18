package dev.scoutr.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    private fun card(status: String, paneId: String = "w1:p1") = dev.scoutr.app.data.liveSessionFixture(
        paneId = paneId,
        workspaceId = "w1",
        tabId = "w1:t1",
        agentKind = "pi",
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
        assertEquals("a", board.needsYou[0].live?.paneId)
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
        val json = """
            {"session":{"key":{"agentKind":"pi","path":"/home/u/.pi/agent/sessions/--tmp--/x.jsonl"},
             "agentKind":"pi","displayName":"Pi","title":"Reply with a demo","cwd":"/tmp",
             "model":"opencode-go/deepseek-v4-flash","thinkingLevel":null,"capabilities":[],
             "updatedAtMs":1786392956741.0754,"latestActivity":"Reply with a demo","live":null},
             "createdAtMs":1786392951904}
        """.trimIndent()
        val item = Json.decodeFromString(SessionCatalogItem.serializer(), json)
        assertEquals("x", item.id)
        assertEquals("/tmp", item.cwd)
        assertFalse(item.active)
        assertEquals("done", item.status)
    }

    @Test
    fun `session catalog response decodes a full payload`() {
        val json = """
            {"ok":true,"truncated":false,"sessions":[
              {"session":{"key":{"agentKind":"pi","path":"/p/a.jsonl"},"agentKind":"pi",
               "displayName":"Pi","title":"t","cwd":"/a","model":null,"thinkingLevel":null,
               "capabilities":[],"updatedAtMs":2.5,"latestActivity":null,
               "live":{"paneId":"w1:p1","workspaceId":"w1","tabId":"w1:t1","status":"working","statusSinceMs":null}},
               "createdAtMs":1}
            ]}
        """.trimIndent()
        val response = Json.decodeFromString(SessionCatalogResponse.serializer(), json)
        assertEquals(1, response.sessions.size)
        assertTrue(response.sessions[0].active)
        assertEquals("w1:p1", response.sessions[0].session.live?.paneId)
    }
}
