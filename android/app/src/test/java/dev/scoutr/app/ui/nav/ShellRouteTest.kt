package dev.scoutr.app.ui.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wide shell shows on the four tabs plus Chat, and nowhere else. */
class ShellRouteTest {

    @Test
    fun everyTabRouteKeepsTheShell() {
        for (route in Destination.routes) {
            assertTrue("$route should keep the shell", isShellRoute(route))
        }
    }

    @Test
    fun chatKeepsTheShell() {
        assertTrue(isShellRoute(CHAT_ROUTE))
    }

    @Test
    fun fullWindowRoutesDoNotKeepTheShell() {
        val fullWindow = listOf(
            "settings",
            "terminal?paneId={paneId}",
            "files?cwd={cwd}",
            "file-viewer?cwd={cwd}&file={file}",
            "connect",
            AppRoutes.SUBAGENT_PROGRESS,
        )
        for (route in fullWindow) {
            assertFalse("$route should own the whole window", isShellRoute(route))
        }
        assertFalse(isShellRoute(null))
    }

    @Test
    fun aFilledChatUrlIsNotTheRoutePattern() {
        // NavHost reports patterns; the predicate must compare against the
        // pattern, not a navigated URL.
        assertFalse(isShellRoute("chat?sessionKey=pi%3A%2Fs.jsonl&status=working"))
    }
}
