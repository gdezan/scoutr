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
 * One evidence group per assistant run: every command between thinking blocks
 * in the same turn lands in a single pill under the run's last assistant bubble.
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

    @Test
    fun multipleCommandsInOneTurnCollapseToOnePill() {
        val entries = listOf(
            user("u1"),
            assistant("a1", bashCall("c1", "ls")),
            bashResult("r1", "c1", "a.txt"),
            assistant("a2", bashCall("c2", "git status")),
            bashResult("r2", "c2", "clean"),
            assistant("a3", ContentBlock(type = "text", text = "done")),
            user("u2"),
        )

        val byEntry = evidenceByRun(entries)

        // Single group, owned by the run's last assistant bubble.
        assertEquals(setOf("a3"), byEntry.keys)
        val summary = byEntry.getValue("a3")
        assertEquals(2, summary.commands)
        assertEquals(0, summary.fileCount)
        assertTrue(summary.hasEvidence)
        // Each result paired with the command that was run.
        assertEquals(
            listOf("ls", "git status"),
            summary.bashRuns.map { it.call?.arguments?.get("command").toString().trim('"') },
        )
        assertNull(byEntry["a1"])
        assertNull(byEntry["a2"])
    }

    @Test
    fun userMessageStartsANewRun() {
        val entries = listOf(
            user("u1"),
            assistant("a1", bashCall("c1", "ls")),
            bashResult("r1", "c1", "a.txt"),
            user("u2"),
            assistant("a2", ContentBlock(type = "text", text = "ok")),
        )

        val byEntry = evidenceByRun(entries)

        assertEquals(setOf("a1", "a2"), byEntry.keys)
        assertEquals(1, byEntry.getValue("a1").commands)
        // Tool-free run yields an empty summary, so no pill renders.
        assertTrue(!byEntry.getValue("a2").hasEvidence)
    }

    @Test
    fun streamingCallWithoutResultCountsAsPendingCommand() {
        val entries = listOf(
            user("u1"),
            assistant("a1", bashCall("c1", "sleep 60")),
        )

        val summary = evidenceByRun(entries).getValue("a1")

        assertEquals(1, summary.commands)
        assertEquals(1, summary.pendingCalls.size)
        assertTrue(summary.bashRuns.isEmpty())
        assertTrue(summary.hasEvidence)
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

        val runs = evidenceByRun(entries).getValue("a1").bashRuns

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
}
