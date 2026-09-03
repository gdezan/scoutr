package dev.scoutr.app.ui.screens

import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.ui.components.bashOutputText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One live evidence pool per content segment: thinking and prose blocks split
 * the turn, each pool renders inside its anchor's bubble (never as a turn
 * total after the final prose), and a new block freezes the open pool.
 */
class EvidenceGroupingTest {

    private fun bashCall(id: String, command: String) = ContentBlock(
        type = "toolCall",
        id = id,
        name = "bash",
        arguments = buildJsonObject { put("command", command) },
    )

    private fun bashResult(id: String, callId: String, output: String) = SessionEntry(
        entryId = id,
        role = "toolResult",
        content = listOf(ContentBlock(type = "text", text = output)),
        toolCallId = callId,
        toolName = "bash",
    )

    private fun assistant(id: String, vararg blocks: ContentBlock) = SessionEntry(
        entryId = id,
        role = "assistant",
        content = blocks.toList(),
    )

    private fun user(id: String) = SessionEntry(
        entryId = id,
        role = "user",
        content = listOf(ContentBlock(type = "text", text = "hi")),
    )

    private fun commandOf(call: ContentBlock?) =
        call?.arguments?.get("command").toString().trim('"')

    @Test
    fun thinkingBlocksSplitOneTurnIntoPools() {
        val entries = listOf(
            user("u1"),
            assistant("a1", ContentBlock(type = "thinking", thinking = "plan A"), bashCall("c1", "ls")),
            bashResult("r1", "c1", "a.txt"),
            assistant("a2", ContentBlock(type = "thinking", thinking = "plan B"), bashCall("c2", "git status")),
            bashResult("r2", "c2", "clean"),
            assistant("a3", ContentBlock(type = "text", text = "done")),
            user("u2"),
        )

        val pools = evidenceSegments(entries)

        // One pool per thinking block; nothing parked after the final prose.
        assertEquals(setOf("a1", "a2"), pools.keys)
        assertEquals(1, pools.getValue("a1").commands)
        assertEquals("ls", commandOf(pools.getValue("a1").bashRuns.single().call))
        assertEquals(1, pools.getValue("a2").commands)
        assertEquals("git status", commandOf(pools.getValue("a2").bashRuns.single().call))
        assertNull(pools["a3"])
    }

    @Test
    fun proseBlocksSplitPools() {
        val entries = listOf(
            user("u1"),
            assistant("a1", ContentBlock(type = "text", text = "first"), bashCall("c1", "ls")),
            bashResult("r1", "c1", "a.txt"),
            assistant("a2", ContentBlock(type = "text", text = "second"), bashCall("c2", "pwd")),
            bashResult("r2", "c2", "/repo"),
        )

        val pools = evidenceSegments(entries)

        assertEquals(setOf("a1", "a2"), pools.keys)
        assertEquals("ls", commandOf(pools.getValue("a1").bashRuns.single().call))
        assertEquals("pwd", commandOf(pools.getValue("a2").bashRuns.single().call))
    }

    @Test
    fun userMessageClosesThePool() {
        val entries = listOf(
            user("u1"),
            assistant("a1", bashCall("c1", "ls")),
            bashResult("r1", "c1", "a.txt"),
            user("u2"),
            assistant("a2", ContentBlock(type = "text", text = "ok")),
        )

        val pools = evidenceSegments(entries)

        // Tool-free segment yields no pool, so no pill renders.
        assertEquals(setOf("a1"), pools.keys)
        assertEquals(1, pools.getValue("a1").commands)
    }

    @Test
    fun lateResultAfterNewBlockJoinsTheNewPool() {
        val entries = listOf(
            user("u1"),
            assistant("a1", bashCall("c1", "ls")),
            assistant("a2", ContentBlock(type = "thinking", thinking = "next")),
            bashResult("r1", "c1", "a.txt"),
        )

        val pools = evidenceSegments(entries)

        // The new block froze a1's pool: c1 stays pending there...
        assertEquals(setOf("a1", "a2"), pools.keys)
        assertTrue(pools.getValue("a1").bashRuns.isEmpty())
        assertEquals(1, pools.getValue("a1").pendingCalls.size)
        // ...and the late result lands in the open pool, unpaired.
        assertEquals(1, pools.getValue("a2").bashRuns.size)
        assertNull(pools.getValue("a2").bashRuns.single().call)
    }

    @Test
    fun explicitCallIdWinsOverTranscriptOrder() {
        val entries = listOf(
            user("u1"),
            assistant("a1", bashCall("c1", "first"), bashCall("c2", "second")),
            SessionEntry(
                entryId = "r1",
                role = "toolResult",
                content = listOf(ContentBlock(type = "text", text = "out-1")),
                toolName = "bash",
            ),
            bashResult("r2", "c1", "out-2"),
        )

        val runs = evidenceSegments(entries).getValue("a1").bashRuns

        // r2 names c1 explicitly, so the ID-less r1 falls back to c2.
        assertEquals("c2", runs[0].call?.id)
        assertEquals("c1", runs[1].call?.id)
    }

    @Test
    fun commandOutputSpansEveryTextBlock() {
        val entry = SessionEntry(
            entryId = "r1",
            role = "toolResult",
            content = listOf(
                ContentBlock(type = "text", text = "head"),
                ContentBlock(type = "text", text = "tail"),
            ),
            toolName = "bash",
        )

        assertEquals("head\ntail", bashOutputText(entry))
    }

    @Test
    fun resultsBeforeFirstAssistantEntryJoinItsPool() {
        val entries = listOf(
            user("u1"),
            SessionEntry(
                entryId = "r0",
                role = "toolResult",
                content = listOf(ContentBlock(type = "text", text = "early")),
                toolName = "bash",
            ),
            assistant("a1", bashCall("c1", "ls")),
            bashResult("r1", "c1", "a.txt"),
        )

        val pools = evidenceSegments(entries)

        // The orphan waits for a1's pool; every key stays an assistant anchor.
        assertEquals(setOf("a1"), pools.keys)
        val runs = pools.getValue("a1").bashRuns
        assertEquals(2, runs.size)
        assertNull(runs[0].call)
        assertEquals("c1", runs[1].call?.id)
    }

    @Test
    fun orphansCutOffByUserBoundaryAreDropped() {
        val entries = listOf(
            user("u1"),
            SessionEntry(
                entryId = "r0",
                role = "toolResult",
                content = listOf(ContentBlock(type = "text", text = "stray")),
                toolName = "bash",
            ),
            user("u2"),
            assistant("a1", ContentBlock(type = "text", text = "ok")),
        )

        assertTrue(evidenceSegments(entries).isEmpty())
    }
}
