package dev.cockpit.app.state

import dev.cockpit.app.data.ModelInfo
import dev.cockpit.app.data.ModelProvider
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.data.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewSessionFormatTest {

    @Test
    fun quickPicksAreHomeAndDev() {
        assertEquals(listOf("/home/gdezan", "/home/gdezan/Dev"), quickPicks("/home/gdezan"))
    }

    @Test
    fun breadcrumbSplitsHomeRelativePaths() {
        val crumbs = breadcrumb("/home/gdezan/Dev/agents-mobile", "/home/gdezan")
        assertEquals(
            listOf("/home/gdezan", "/home/gdezan/Dev", "/home/gdezan/Dev/agents-mobile"),
            crumbs,
        )
    }

    @Test
    fun breadcrumbOfHomeIsJustHome() {
        assertEquals(listOf("/home/gdezan"), breadcrumb("/home/gdezan", "/home/gdezan"))
    }

    @Test
    fun crumbLabelsHomeAsTildeAndLeafOtherwise() {
        assertEquals("~", crumbLabel("/home/gdezan", "/home/gdezan"))
        assertEquals("agents-mobile", crumbLabel("/home/gdezan/Dev/agents-mobile", "/home/gdezan"))
    }

    @Test
    fun providerLabelIsTheProviderName() {
        assertEquals("openai-codex", providerLabel(ModelProvider("openai-codex", emptyList())))
    }

    @Test
    fun lastUserMessageFindsMostRecentUserEntry() {
        val state = ChatUiState(
            entries = listOf(
                SessionEntry("1", role = "assistant", content = listOf(ContentBlock(type = "text", text = "hi"))),
                SessionEntry("2", role = "user", content = listOf(ContentBlock(type = "text", text = "first ask"))),
                SessionEntry("3", role = "assistant", content = listOf(ContentBlock(type = "text", text = "ok"))),
                SessionEntry("4", role = "user", content = listOf(ContentBlock(type = "text", text = "second ask"))),
            ),
        )
        assertEquals("second ask", state.lastUserMessage)
    }

    @Test
    fun lastUserMessageIsNullWithoutUserEntries() {
        val state = ChatUiState(
            entries = listOf(
                SessionEntry("1", role = "assistant", content = listOf(ContentBlock(type = "text", text = "hi"))),
            ),
        )
        assertNull(state.lastUserMessage)
    }

    @Test
    fun modelGroupsRenderByName() {
        val providers = listOf(
            ModelProvider(
                "openai-codex",
                listOf(ModelInfo(id = "gpt-5.4", name = "GPT-5.4", provider = "openai-codex")),
            ),
            ModelProvider("deepseek", listOf(ModelInfo(id = "deepseek-v4-flash", provider = "deepseek"))),
        )
        assertEquals(2, providers.size)
        assertEquals("GPT-5.4", providers[0].models[0].name)
        assertEquals("deepseek", providers[1].name)
    }
}
