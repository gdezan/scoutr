package dev.scoutr.app.terminal

import dev.scoutr.app.net.PerformanceCounters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the vendored Termux emulator through the app's transport-neutral session seam. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteTerminalSessionTest {

    private class Harness {
        val callbacks = RemoteTerminalSession.Callbacks()
        val counters = PerformanceCounters()
        val session = RemoteTerminalSession(transcriptRows = 200, callbacks, counters)
        var screenUpdates = 0
        var titleChanges = 0
        var sessionFinished = 0
        var bells = 0
        var colorsChanged = 0
        var cursorStateChanges = 0
        var clipboardPasteRequests = 0

        init {
            callbacks.onScreenUpdated = { screenUpdates++ }
            callbacks.onTitleChanged = { titleChanges++ }
            callbacks.onSessionFinished = { sessionFinished++ }
            callbacks.onBell = { bells++ }
            callbacks.onColorsChanged = { colorsChanged++ }
            callbacks.onCursorStateChange = { cursorStateChanges++ }
            callbacks.onClipboardPasteRequest = { clipboardPasteRequests++ }
        }

        fun feed(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            session.appendOutput(bytes, 0, bytes.size)
        }

        fun transcript(): String = session.emulator!!.screen.getTranscriptTextWithoutJoinedLines()

        /** Feed chunks the way the pump does: one contiguous batch per drain. */
        fun feedBatched(chunks: List<String>) {
            val batch = chunks.joinToString("").toByteArray(Charsets.UTF_8)
            session.appendOutput(batch, 0, batch.size)
        }
    }


    private fun harness(): Harness {
        val h = Harness()
        h.session.updateSize(80, 24, 13, 15)
        return h
    }

    @Test
    fun ansiOutputRendersAndNotifiesScreenUpdated() {
        val h = harness()
        h.feed("\u001b[31mHello")
        h.feed(" world")
        assertEquals("Hello world", h.transcript())
        assertTrue(h.screenUpdates >= 2)
    }

    @Test
    fun titleBellAndColorChangesReachCallbacks() {
        val h = harness()
        h.feed("\u001b]2;My Title\u0007")
        assertEquals("My Title", h.session.title)
        assertEquals(1, h.titleChanges)

        h.feed("\u0007")
        assertEquals(1, h.bells)

        h.feed("\u001b]104\u0007")
        // The emulator constructor resets the palette once (upstream behavior), so this is the
        // second onColorsChanged.
        assertEquals(2, h.colorsChanged)
    }

    @Test
    fun osc52ClipboardSetIsBlockedAndTerminalKeepsWorking() {
        val h = harness()
        // OSC 52;c;base64("secret") — must be dropped, never forwarded (no clipboard callback
        // exists) and must not disrupt the session.
        h.feed("\u001b]52;c;c2VjcmV0\u0007")
        h.feed("still alive")
        assertEquals("still alive", h.transcript())
        assertEquals(0, h.clipboardPasteRequests)
    }

    @Test
    fun writesRouteThroughInputSinkAndAreDroppedWithoutOne() {
        val h = harness()
        // No sink: writes are dropped without failing (mirrors upstream pre-process behavior).
        h.session.writeCodePoint(false, 'A'.code)
        h.session.write("ab".toByteArray(), 0, 2)

        val received = mutableListOf<ByteArray>()
        h.session.inputSink = { received.add(it) }
        h.session.writeCodePoint(false, 'A'.code)
        h.session.writeCodePoint(true, 'A'.code)
        h.session.write("ab".toByteArray(), 0, 2)

        assertEquals(
            listOf("A", "\u001bA", "ab"),
            received.map { it.toString(Charsets.UTF_8) },
        )

        // Unwiring restores drop behavior.
        h.session.inputSink = null
        h.session.write("zz".toByteArray(), 0, 2)
        assertEquals(3, received.size)
    }

    @Test
    fun resizeReflowsWrappedLines() {
        val h = Harness()
        h.session.updateSize(20, 10, 13, 15)
        h.feed("x".repeat(40))
        assertEquals("x".repeat(20) + "\n" + "x".repeat(20), h.transcript())

        h.session.updateSize(40, 10, 13, 15)
        assertEquals("x".repeat(40), h.transcript())
    }

    @Test
    fun resetForGenerationReplacesTranscriptAndNotifies() {
        val h = harness()
        // 30 lines scroll the 24-row screen, pushing 6 rows into the transcript.
        h.feed((1..30).joinToString("\n") { "old $it" })
        assertTrue(h.session.emulator!!.screen.activeTranscriptRows > 0)

        val updatesBefore = h.screenUpdates
        h.session.resetForGeneration(80, 24, 13, 15)

        assertEquals(0, h.session.emulator!!.screen.activeTranscriptRows)
        assertTrue(h.screenUpdates > updatesBefore)

        h.feed("new generation")
        assertEquals("new generation", h.transcript())
    }

    // --- Batched delivery (one append + one screen update per drained batch) ---

    @Test
    fun oneBatchProducesOneAppendAndOneScreenUpdate() {
        val h = harness()
        val updatesBefore = h.screenUpdates
        val appendsBefore = h.counters.snapshot().terminal.emulatorAppends

        // 40 network chunks the pump would have drained into a single batch.
        val chunks = (1..40).map { "line $it\r\n" }
        h.feedBatched(chunks)

        assertEquals(1, h.screenUpdates - updatesBefore)
        assertEquals(1L, h.counters.snapshot().terminal.emulatorAppends - appendsBefore)
        assertEquals(
            chunks.joinToString("") { it.removeSuffix("\r\n") + "\n" }.trimEnd('\n'),
            h.transcript(),
        )
    }

    @Test
    fun largeBatchRendersEveryLine() {
        val h = harness()
        // Under the harness' 200-row transcript, so nothing scrolls out of history.
        val lines = (1..150).map { "row $it" }
        h.feedBatched(lines.map { "$it\r\n" })

        val transcript = h.transcript().split("\n")
        assertEquals(lines, transcript.filter { it.isNotEmpty() })
        assertEquals(1L, h.counters.snapshot().terminal.emulatorAppends)
    }

    @Test
    fun escapeSequenceSplitAcrossNetworkChunksStillRenders() {
        // The pump can slice a burst anywhere, including mid-escape. Fed as
        // separate appends, the emulator's parser state must span them.
        val h = harness()
        h.feed("\u001b[")
        h.feed("31")
        h.feed("mHello")
        assertEquals("Hello", h.transcript())

        // Same bytes coalesced into one batch must render identically.
        val batched = harness()
        batched.feedBatched(listOf("\u001b[", "31", "mHello"))
        assertEquals(h.transcript(), batched.transcript())
    }

    @Test
    fun titleBellAndColorCallbacksSurviveCoalescing() {
        val h = harness()
        // One batch carrying a title change, a BEL, a palette reset and text.
        h.feedBatched(listOf("\u001b]2;Batched\u0007", "\u0007", "\u001b]104\u0007", "done"))

        assertEquals("Batched", h.session.title)
        assertEquals(1, h.titleChanges)
        assertEquals(1, h.bells)
        // The emulator constructor resets the palette once, so this is the second.
        assertEquals(2, h.colorsChanged)
        assertEquals("done", h.transcript())
    }

    @Test
    fun appendRejectsANonZeroOffsetInsteadOfFeedingTheWrongBytes() {
        val h = harness()
        val bytes = "skip me".toByteArray()
        try {
            h.session.appendOutput(bytes, 2, bytes.size - 2)
            throw AssertionError("expected a rejected offset")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("offset 0"))
        }
    }
}
