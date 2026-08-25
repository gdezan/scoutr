package dev.scoutr.app.terminal

import android.content.Context
import com.termux.view.TerminalView
import dev.scoutr.app.net.PerformanceCounters
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Terminal viewport follow policy at the [TerminalView]↔[RemoteTerminalSession] seam.
 *
 * The user-visible contract: while the reader is scrolled back into the transcript, incoming
 * output must not chase the bottom — the lines on screen stay put. Only a viewport already at
 * the bottom follows live output, and a generation reset (fresh screen replay) re-pins to the
 * bottom.
 *
 * The view is never laid out (zero size), which is exactly what lets [TerminalView.refreshEmulator]
 * run without font metrics: it re-fetches the emulator and applies scroll policy directly, the
 * same code path [dev.scoutr.app.ui.screens.terminal.TerminalScreen]'s repainter drives per batch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalScrollFollowTest {

    private class Harness(context: Context) {
        val callbacks = RemoteTerminalSession.Callbacks()
        val session = RemoteTerminalSession(transcriptRows = 200, callbacks, PerformanceCounters())
        val view = TerminalView(context, null)

        init {
            // Zero-sized view: attachSession's updateSize no-ops, refreshEmulator still works.
            view.attachSession(session)
            session.updateSize(80, 24, 13, 15)
        }

        fun feed(lines: Int) {
            val text = buildString { repeat(lines) { append("row-$it\r\n") } }
            val bytes = text.toByteArray(Charsets.UTF_8)
            session.appendOutput(bytes, 0, bytes.size)
        }

        fun feedRaw(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            session.appendOutput(bytes, 0, bytes.size)
        }

        fun viewportText(): String {
            val emulator = session.emulator!!
            val top = view.topRow
            return emulator.screen.getSelectedText(0, top, emulator.mColumns, top + emulator.mRows)
        }

        /** Runs what TerminalScreen's repainter runs per output batch on the UI thread. */
        fun repaint() = view.refreshEmulator()
    }

    private lateinit var h: Harness

    @Before
    fun setUp() {
        h = Harness(RuntimeEnvironment.getApplication() as Context)
        // Fill the screen and spill into the transcript so scrolling has somewhere to go.
        h.feed(60)
        h.repaint()
    }

    @Test
    fun `output holds the viewport steady while scrolled back`() {
        h.view.setTopRow(-10)
        val readingBefore = h.viewportText()

        h.feed(30)
        h.repaint()

        assertEquals(readingBefore, h.viewportText())
    }

    @Test
    fun `viewport already at the bottom keeps following live output`() {
        h.view.setTopRow(0)

        h.feed(30)
        h.repaint()

        assertEquals(0, h.view.topRow)
    }

    @Test
    fun `generation reset replays pinned to the bottom even from scrolled-back position`() {
        h.view.setTopRow(-10)

        h.session.resetForGeneration(80, 24, 13, 15)
        h.repaint()

        assertEquals(0, h.view.topRow)
    }

    @Test
    fun `several batches between repaints hold as one steady position`() {
        h.view.setTopRow(-10)
        val readingBefore = h.viewportText()

        repeat(3) { h.feed(10) } // pump coalescing: appends pile up before one repaint
        h.repaint()

        assertEquals(readingBefore, h.viewportText())
    }

    @Test
    fun `holding the oldest row clamps instead of running past the transcript cap`() {
        val oldest = -h.session.emulator!!.screen.activeTranscriptRows
        h.view.setTopRow(oldest)

        h.feed(250) // far more scrolling than the 200-row transcript can retain
        h.repaint()

        assertEquals(-h.session.emulator!!.screen.activeTranscriptRows, h.view.topRow)
    }

    @Test
    fun `alternate screen output stays pinned to the bottom`() {
        h.feedRaw("\u001b[?1049h")
        h.repaint()
        h.view.setTopRow(-5)

        h.feed(10)
        h.repaint()

        assertEquals(0, h.view.topRow)
    }
}
