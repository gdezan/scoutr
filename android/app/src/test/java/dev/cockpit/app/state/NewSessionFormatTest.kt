package dev.cockpit.app.state

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
    fun crumbLabelsHomeAsTildeAndLeafOtherwise() {
        assertEquals("~", crumbLabel("/home/gdezan", "/home/gdezan"))
        assertEquals("agents-mobile", crumbLabel("/home/gdezan/Dev/agents-mobile", "/home/gdezan"))
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
}
