package dev.scoutr.app.ui.screens

import dev.scoutr.app.data.PiSubagentProgress
import dev.scoutr.app.data.PiSubagentUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentProgressFormatTest {
    @Test
    fun formatsTokensCostAndDuration() {
        assertEquals("146,260", formatSubagentTokens(146260L))
        assertEquals("$0.03", formatSubagentCost(0.0288))
        assertEquals("0s", formatSubagentDuration(0))
        assertEquals("42s", formatSubagentDuration(42_000))
        assertEquals("3m 24s", formatSubagentDuration(204_000))
        assertEquals("1h 02m", formatSubagentDuration(3_720_000))
    }

    @Test
    fun buildsMetaAndFactsLines() {
        val rich = PiSubagentProgress(
            runId = "r",
            role = "worker",
            status = "running",
            model = "gpt-5.6-terra",
            thinking = "xhigh",
            contextTokens = 146260,
            usage = PiSubagentUsage(turns = 37, cost = 0.0288),
            durationMs = 204000,
        )
        assertEquals("gpt-5.6-terra · xhigh", subagentModelLine(rich))
        assertEquals("146,260 ctx · 37 turns · $0.03 · 3m 24s", subagentRunFactsLine(rich))

        val sparse = PiSubagentProgress(runId = "r", role = "worker", status = "done")
        assertNull(subagentModelLine(sparse))
        assertNull(subagentRunFactsLine(sparse))
    }

    @Test
    fun glyphsTrackToolStatus() {
        assertEquals("✓", subagentToolGlyph("done"))
        assertEquals("✗", subagentToolGlyph("error"))
        assertEquals("…", subagentToolGlyph("running"))
        assertEquals("·", subagentToolGlyph(null))
    }
}
