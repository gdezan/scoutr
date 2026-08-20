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
    fun typedPromptSplitsPiSkillCommand() {
        val parsed = typedPromptPresentation("/skill:grill-me on the preferred approach")
        assertEquals("grill-me", parsed.skill?.name)
        assertEquals("/skill:grill-me", parsed.skill?.command)
        assertEquals("on the preferred approach", parsed.text)
        assertEquals("research", typedPromptPresentation("/skill:research").skill?.name)
        assertNull(typedPromptPresentation("not a skill").skill)
    }

    @Test
    fun typedPromptChipsAnySlashCommandOnClaudeOnly() {
        val claude = typedPromptPresentation("/writing-for-agents draft the skill", agentKind = "claude")
        assertEquals("writing-for-agents", claude.skill?.name)
        assertEquals("/writing-for-agents", claude.skill?.command)
        assertEquals("draft the skill", claude.text)

        val pi = typedPromptPresentation("/writing-for-agents draft the skill", agentKind = "pi")
        assertNull(pi.skill)
        assertEquals("/writing-for-agents draft the skill", pi.text)
    }

    @Test
    fun claudeCommandTurnReconstructsAndEchoes() {
        val content = listOf(
            ContentBlock(type = "skill", name = "writing-for-agents", command = "/writing-for-agents"),
            ContentBlock(type = "text", text = "draft the skill"),
        )
        assertEquals("/writing-for-agents draft the skill", reconstructUserPrompt(content))
        assertEquals(
            userMessageEchoKey("/writing-for-agents draft the skill"),
            userMessageEchoKey(content),
        )
    }

    @Test
    fun presentationWithoutSkillIsPlainText() {
        val parsed = userPromptPresentation(listOf(ContentBlock(type = "text", text = "just a prompt")))
        assertNull(parsed.skill)
        assertEquals("just a prompt", parsed.text)
        assertTrue(parsed.text.isNotEmpty())
    }
}
