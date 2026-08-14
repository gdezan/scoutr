package dev.scoutr.app.ui.screens

import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.SessionEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFormatTest {

    @Test
    fun `bash command is extracted from toolCall arguments`() {
        val block = ContentBlock(
            type = "toolCall",
            name = "bash",
            arguments = buildJsonObject { put("command", "git log --oneline -3") },
        )
        assertEquals("git log --oneline -3", toolCallCommand(block))
    }

    @Test
    fun `edit file path is extracted without repeating the tool name`() {
        val block = ContentBlock(
            type = "toolCall",
            name = "edit",
            arguments = buildJsonObject { put("file_path", "src/main.kt") },
        )
        assertEquals("src/main.kt", toolCallCommand(block))
    }

    @Test
    fun `falls back to the tool name without arguments`() {
        assertEquals("read", toolCallCommand(ContentBlock(type = "toolCall", name = "read")))
    }

    @Test
    fun `prefers a meaningful subject over raw JSON`() {
        val block = ContentBlock(
            type = "toolCall",
            name = "todo",
            arguments = buildJsonObject {
                put("action", "create")
                put("subject", "Fix 1: Chat composer overlap")
            },
        )
        assertEquals("Fix 1: Chat composer overlap", toolCallCommand(block))
    }

    @Test
    fun `falls back to a truncated JSON dump for unknown shapes`() {
        val long = "x".repeat(100)
        val block = ContentBlock(
            type = "toolCall",
            name = "write",
            arguments = buildJsonObject { put("content", long) },
        )
        val out = toolCallCommand(block)
        assertEquals(true, out.startsWith("{"))
        assertEquals(true, out.length <= 64 + 3)
    }

    @Test
    fun `a tool result carrying a fileEdit block is recognised as an edit`() {
        val entry = SessionEntry(
            entryId = "e1",
            role = "toolResult",
            toolName = "Edit",
            content = listOf(
                ContentBlock(type = "text", text = "The file has been updated successfully."),
                ContentBlock(type = "fileEdit", path = "/repo/scripts/install-app.sh", added = 156, removed = 41),
            ),
        )
        val edit = fileEditOf(entry)
        assertEquals("/repo/scripts/install-app.sh", edit?.path)
        assertEquals(156, edit?.added)
    }

    @Test
    fun `an ordinary tool result has no edit`() {
        val entry = SessionEntry(
            entryId = "e1",
            role = "toolResult",
            toolName = "bash",
            content = listOf(ContentBlock(type = "text", text = "a.txt")),
        )
        assertEquals(null, fileEditOf(entry))
    }

    @Test
    fun `a fileEdit without a path is not rendered as one`() {
        val entry = SessionEntry(
            entryId = "e1",
            role = "toolResult",
            content = listOf(ContentBlock(type = "fileEdit", path = "", added = 1)),
        )
        assertEquals(null, fileEditOf(entry))
    }

    @Test
    fun `the chip labels an edit with its file name`() {
        val block = ContentBlock(type = "fileEdit", path = "/home/x/Dev/scoutr/scripts/install-app.sh")
        assertEquals("install-app.sh", fileEditFileName(block))
    }

    @Test
    fun `the expanded diff keeps the last two path segments`() {
        assertEquals(
            "…/scripts/install-app.sh",
            fileEditDisplayPath(ContentBlock(type = "fileEdit", path = "/home/x/Dev/scoutr/scripts/install-app.sh")),
        )
    }

    @Test
    fun `a short path is shown whole`() {
        assertEquals("src/main.kt", fileEditDisplayPath(ContentBlock(type = "fileEdit", path = "src/main.kt")))
        assertEquals("Makefile", fileEditDisplayPath(ContentBlock(type = "fileEdit", path = "Makefile")))
    }
}
