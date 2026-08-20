package dev.scoutr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInvocationTest {

    @Test
    fun presentationTakesFirstSkillBlockAndRawLeftover() {
        val parsed = userPromptPresentation(
            listOf(
                ContentBlock(type = "skill", name = "grill-me", text = "Ask hard questions."),
                ContentBlock(type = "skill", name = "ignored", text = "nope"),
                ContentBlock(type = "text", text = "on the preferred approach"),
            ),
        )
        assertEquals("grill-me", parsed.skill?.name)
        assertEquals("Ask hard questions.", parsed.skill?.body)
        assertEquals("on the preferred approach", parsed.text)
    }

    @Test
    fun unpeeledXmlStaysLeftoverText() {
        val xml = """<skill name="grill-me">body</skill>

on the preferred approach"""
        val parsed = userPromptPresentation(listOf(ContentBlock(type = "text", text = xml)))
        assertNull(parsed.skill)
        assertEquals(xml, parsed.text)
    }

    @Test
    fun entryTextUsesSkillPreviewNotInjectedBody() {
        val content = listOf(
            ContentBlock(type = "skill", name = "grill-me", text = "# Grill me\nAsk hard questions."),
            ContentBlock(type = "text", text = "on the preferred approach"),
        )
        assertEquals("[skill: grill-me] on the preferred approach", entryText(content))
    }

    @Test
    fun reconstructUserPromptRewritesSkillTurnAsSlashCommand() {
        val content = listOf(
            ContentBlock(type = "skill", name = "grill-me", text = "body"),
            ContentBlock(type = "text", text = "on the preferred approach"),
        )
        assertEquals("/skill:grill-me on the preferred approach", reconstructUserPrompt(content))
    }

    @Test
    fun slashCommandAndPeeledSkillShareAnEchoKey() {
        val skillContent = listOf(
            ContentBlock(type = "skill", name = "grill-me", text = "body"),
            ContentBlock(type = "text", text = "on the preferred approach"),
        )
        assertEquals(
            userMessageEchoKey("/skill:grill-me on the preferred approach"),
            userMessageEchoKey(skillContent),
        )
    }

    @Test
    fun parseSlashSkillCommandReadsNameAndArgs() {
        assertEquals(
            "grill-me" to "on the preferred approach",
            parseSlashSkillCommand("/skill:grill-me on the preferred approach"),
        )
        assertEquals("research" to "", parseSlashSkillCommand("/skill:research"))
        assertNull(parseSlashSkillCommand("not a skill"))
    }

    @Test
    fun presentationWithoutSkillIsPlainText() {
        val parsed = userPromptPresentation(listOf(ContentBlock(type = "text", text = "just a prompt")))
        assertNull(parsed.skill)
        assertEquals("just a prompt", parsed.text)
        assertTrue(parsed.text.isNotEmpty())
    }
}
