package dev.scoutr.app.state

import dev.scoutr.app.data.SlashCommandInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandSearchTest {
    private val commands = listOf(
        SlashCommandInfo("compact", "Compact the session", "builtin"),
        SlashCommandInfo("copy", "Copy the last message", "builtin"),
        SlashCommandInfo("skill:research", "Research a topic", "skill", "<request>"),
    )

    @Test
    fun queryOnlyCoversTheCommandName() {
        assertEquals("", slashCommandQuery("/"))
        assertEquals("skill:res", slashCommandQuery("/skill:res"))
        assertNull(slashCommandQuery("plain text"))
        assertNull(slashCommandQuery("/model openai/gpt"))
    }

    @Test
    fun matchesNamesBeforeDescriptions() {
        assertEquals(listOf("compact", "copy"), matchSlashCommands(commands, "co").map { it.name })
        assertEquals(listOf("skill:research"), matchSlashCommands(commands, "topic").map { it.name })
    }

    @Test
    fun fillsArgumentCommandsWithSpace() {
        assertEquals("/compact", fillSlashCommand(commands[0]))
        assertEquals("/skill:research ", fillSlashCommand(commands[2]))
    }
}
