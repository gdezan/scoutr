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
    fun `agents response round-trips simple, multi-question, and absent attention`() {
        val json = """
            {"ok":true,"agents":[
              {"key":{"agentKind":"pi","path":"/p/simple.jsonl"},"agentKind":"pi","displayName":"Pi",
               "title":"Ship","cwd":"/a","model":null,"thinkingLevel":null,"capabilities":[],
               "updatedAtMs":1.0,"latestActivity":"[ask_user_question]",
               "attention":{"kind":"ask","callId":"call_simple","questionCount":1,
                "currentQuestion":{"id":"call_simple#0","header":"Ship","question":"Ship the fix?",
                 "options":[{"label":"Ship it","description":"Deploy now."},
                            {"label":"Hold","description":"Wait for review."}],"multiSelect":false},
                "canQuickAnswer":true},
               "live":{"paneId":"w1:p1","workspaceId":"w1","tabId":"w1:t1","status":"blocked","statusSinceMs":null}},
              {"key":null,"agentKind":"pi","displayName":"Pi","title":"Release","cwd":"/a","model":null,
               "thinkingLevel":null,"capabilities":[],"updatedAtMs":null,"latestActivity":null,
               "attention":{"kind":"ask","callId":"call_multi","questionCount":2,
                "currentQuestion":{"id":"call_multi#0","header":"Ship","question":"Ship the fix?",
                 "options":[{"label":"Ship it","description":""}],"multiSelect":false},
                "canQuickAnswer":false},
               "live":{"paneId":"w1:p2","workspaceId":"w1","tabId":"w1:t1","status":"blocked","statusSinceMs":null}},
              {"key":null,"agentKind":"pi","displayName":"Pi","title":"Busy","cwd":"/a","model":null,
               "thinkingLevel":null,"capabilities":[],"updatedAtMs":null,"latestActivity":null,
               "attention":null,
               "live":{"paneId":"w1:p3","workspaceId":"w1","tabId":"w1:t1","status":"working","statusSinceMs":null}}
            ]}
        """.trimIndent()
        val response = Json.decodeFromString(AgentsResponse.serializer(), json)
        assertEquals(3, response.agents.size)

        val simple = response.agents[0].attention!!
        assertTrue(simple.isAsk)
        assertEquals("call_simple", simple.callId)
        assertEquals(1, simple.questionCount)
        assertTrue(simple.canQuickAnswer)
        assertEquals("call_simple#0", simple.currentQuestion?.id)
        assertEquals("Ship the fix?", simple.currentQuestion?.question)
        assertEquals(listOf("Ship it", "Hold"), simple.currentQuestion?.options?.map { it.label })
        assertFalse(simple.currentQuestion?.multiSelect ?: true)

        val multi = response.agents[1].attention!!
        assertEquals(2, multi.questionCount)
        assertFalse(multi.canQuickAnswer)

        assertEquals(null, response.agents[2].attention)

        // Re-encoding must preserve every field the board decides on.
        val reencoded = Json.decodeFromString(
            AgentsResponse.serializer(),
            Json.encodeToString(AgentsResponse.serializer(), response),
        )
        assertEquals(response, reencoded)
    }

    @Test
    fun `prompt attention carries no question and no quick answer`() {
        val json = """
            {"kind":"prompt","callId":null,"questionCount":0,"currentQuestion":null,"canQuickAnswer":false}
        """.trimIndent()
        val attention = Json.decodeFromString(AttentionSummary.serializer(), json)
        assertFalse(attention.isAsk)
        assertEquals(null, attention.currentQuestion)
        assertEquals(null, attention.callId)
        assertEquals(0, attention.questionCount)
        assertFalse(attention.canQuickAnswer)
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
