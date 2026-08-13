package dev.scoutr.app.ui.screens

import dev.scoutr.app.data.ContentBlock
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
}
