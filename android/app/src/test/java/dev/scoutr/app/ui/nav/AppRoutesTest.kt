package dev.scoutr.app.ui.nav

import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.decodeSessionKey
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route builders, argument names, and pure navigation decisions: if any of
 * these drift from the NavHost declarations, arguments are silently dropped,
 * so they are pinned here as plain JVM tests.
 */
class AppRoutesTest {

    @Test
    fun chatPatternAndBuilderShareArgumentNames() {
        // The builder must fill exactly the names the chat pattern declares.
        val args = AppRoutes.ChatArgs
        assertTrue(AppRoutes.CHAT.startsWith("chat?${args.SESSION_KEY}={${args.SESSION_KEY}}"))
        assertTrue(AppRoutes.CHAT.contains("&${args.BOOTSTRAP_PANE_ID}={${args.BOOTSTRAP_PANE_ID}}"))
        assertTrue(AppRoutes.CHAT.endsWith("&${args.STATUS}={${args.STATUS}}"))
    }

    @Test
    fun everyBuilderMatchesItsDeclaredRouteSegment() {
        val pairs = listOf(
            AppRoutes.FILE_BROWSER to AppRoutes.fileBrowser("/x"),
            AppRoutes.FILE_VIEWER to AppRoutes.fileViewer("/x", "f"),
            AppRoutes.TERMINAL to AppRoutes.terminal(),
            AppRoutes.CHAT to AppRoutes.chat(SessionKey("pi", "/t.jsonl"), "working"),
            AppRoutes.CHAT to AppRoutes.bootstrapChat("p1", "working"),
        )
        for ((pattern, filled) in pairs) {
            assertEquals(pattern.substringBefore('?'), filled.substringBefore('?'))
        }
    }

    @Test
    fun chatBuilderRoundTripsCanonicalSessionKeys() {
        val key = SessionKey(
            agentKind = "pi",
            path = "/home/gd/Dev/scoutr/.herdr/agents/pi-abc123.jsonl",
        )
        val url = AppRoutes.chat(key, "working")
        val encoded = url.removePrefix("chat?sessionKey=").substringBefore("&status=")
        assertEquals(key, decodeSessionKey(encoded))
        assertTrue(url.endsWith("&status=working"))
    }

    @Test
    fun bootstrapChatEncodesReservedPaneIdCharacters() {
        val paneId = "w1&x=?#p"
        val url = AppRoutes.bootstrapChat(paneId, "working")
        val encoded = url.removePrefix("chat?bootstrapPaneId=").substringBefore("&status=")
        assertEquals(paneId, URLDecoder.decode(encoded, "UTF-8"))
        // An unencoded reserved character would split the query string.
        assertFalse(encoded.contains('&'))
        assertFalse(encoded.contains('?'))
    }

    @Test
    fun fileRoutesEncodePathsWithSpacesAndReservedCharacters() {
        val cwd = "/home/gd/My Folder & notes"
        assertEquals("files?cwd=%2Fhome%2Fgd%2FMy+Folder+%26+notes", AppRoutes.fileBrowser(cwd))

        val viewerUrl = AppRoutes.fileViewer(cwd, "report (v2).txt")
        val encodedCwd = viewerUrl.removePrefix("file-viewer?cwd=").substringBefore("&file=")
        val encodedFile = viewerUrl.substringAfter("&file=")
        assertEquals(cwd, URLDecoder.decode(encodedCwd, "UTF-8"))
        assertEquals("report (v2).txt", URLDecoder.decode(encodedFile, "UTF-8"))
    }

    @Test
    fun terminalWithoutTargetIsEmptyAndStable() {
        // Empty optional value: the ViewModel resolves the pane itself.
        assertEquals("terminal?paneId=", AppRoutes.terminal())
        // Identical construction is what makes launchSingleTop reuse the live route.
        assertEquals(AppRoutes.terminal(), AppRoutes.terminal())
    }

    @Test
    fun terminalEncodesTheRequestedPaneId() {
        val url = AppRoutes.terminal("w1/pane 2")
        assertEquals("w1/pane 2", URLDecoder.decode(url.removePrefix("terminal?paneId="), "UTF-8"))
    }

    @Test
    fun chatRoutePrefersCanonicalKeyOverBootstrapPane() {
        val key = SessionKey("pi", "/t.jsonl")
        assertEquals(
            AppRoutes.chat(key, "reviewing"),
            chatRoute(key, "stale-pane", "reviewing"),
        )
    }

    @Test
    fun chatRouteFallsBackToBootstrapPaneWhenNoKey() {
        assertEquals(
            AppRoutes.bootstrapChat("p9", "working"),
            chatRoute(null, "p9", "working"),
        )
    }

    @Test
    fun chatRouteIsNullWhenNeitherIdentityIsKnown() {
        assertNull(chatRoute(null, null, "working"))
    }

    @Test
    fun blankOrMalformedSessionKeyArgumentsDecodeToNull() {
        assertNull(decodedChatSessionKey(null))
        assertNull(decodedChatSessionKey(""))
        assertNull(decodedChatSessionKey("   "))
        // Legacy raw paths and malformed values never become a session key.
        assertNull(decodedChatSessionKey("/home/gd/Dev/scoutr/transcript.jsonl"))
        assertNull(decodedChatSessionKey("sk1.only-two-parts"))
    }

    @Test
    fun pairedDevicesStartOnBoardOthersOnConnect() {
        assertEquals(Destination.Board.route, initialStartDestination(paired = true))
        assertEquals(AppRoutes.CONNECT, initialStartDestination(paired = false))
    }
}
