package dev.scoutr.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDescriptorSubagentTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `nested parent JSON decodes compact children and no subagent stamp`() {
        val payload = """
            {"ok":true,"agents":[{
              "key":{"agentKind":"pi","path":"/p/parent.jsonl"},
              "agentKind":"pi","displayName":"Pi","title":"Parent",
              "cwd":"/work","model":null,"thinkingLevel":null,"capabilities":[],
              "updatedAtMs":1.0,"latestActivity":null,"attention":null,
              "live":{"paneId":"w1:p1","workspaceId":"w1","tabId":"w1:t1",
                      "status":"working","statusSinceMs":null},
              "subagents":[{
                "runId":"run_child","paneId":"w1:p2","role":"scout",
                "label":"Explore","status":"working"
              }]
            }]}
        """.trimIndent()
        val response = json.decodeFromString(AgentsResponse.serializer(), payload)
        val parent = response.agents.single()
        assertNull(parent.subagent)
        assertEquals(1, parent.subagents.size)
        val child = parent.subagents.single()
        assertEquals("run_child", child.runId)
        assertEquals("w1:p2", child.paneId)
        assertEquals("scout", child.role)
        assertEquals("Explore", child.label)
        assertEquals("working", child.status)
    }

    @Test
    fun `orphan JSON decodes subagent orphan true and empty children`() {
        val payload = """
            {"ok":true,"agents":[{
              "key":null,"agentKind":"pi","displayName":"Pi","title":"Scout",
              "cwd":"/work","model":null,"thinkingLevel":null,"capabilities":[],
              "updatedAtMs":null,"latestActivity":null,"attention":null,
              "live":{"paneId":"w1:p9","workspaceId":"w1","tabId":"w1:t1",
                      "status":"blocked","statusSinceMs":null},
              "subagent":{"runId":"run_orphan","role":"scout","label":null,"orphan":true},
              "subagents":[]
            }]}
        """.trimIndent()
        val response = json.decodeFromString(AgentsResponse.serializer(), payload)
        val orphan = response.agents.single()
        assertTrue(orphan.subagent!!.orphan)
        assertEquals("run_orphan", orphan.subagent!!.runId)
        assertEquals("scout", orphan.subagent!!.role)
        assertNull(orphan.subagent!!.label)
        assertTrue(orphan.subagents.isEmpty())
    }

    @Test
    fun `legacy agents JSON without subagent fields still decodes`() {
        val payload = """
            {"ok":true,"agents":[{
              "key":{"agentKind":"pi","path":"/p/a.jsonl"},
              "agentKind":"pi","displayName":"Pi","title":"Ship",
              "cwd":"/a","model":null,"thinkingLevel":null,"capabilities":[],
              "updatedAtMs":1.0,"latestActivity":null,"attention":null,
              "live":{"paneId":"w1:p1","workspaceId":"w1","tabId":"w1:t1",
                      "status":"working","statusSinceMs":null}
            }]}
        """.trimIndent()
        val response = json.decodeFromString(AgentsResponse.serializer(), payload)
        val session = response.agents.single()
        assertNull(session.subagent)
        assertTrue(session.subagents.isEmpty())
    }
}
