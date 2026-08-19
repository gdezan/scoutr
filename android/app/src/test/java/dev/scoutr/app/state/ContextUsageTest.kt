package dev.scoutr.app.state

import dev.scoutr.app.data.EntryUsage
import dev.scoutr.app.data.SessionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextUsageTest {

    private fun entry(
        id: String,
        role: String = "assistant",
        usage: EntryUsage? = null,
    ) = SessionEntry(entryId = id, role = role, usage = usage)

    @Test
    fun lastAssistantEntryWinsOverEarlierLargerUsage() {
        val entries = listOf(
            entry("a1", usage = EntryUsage(input = 500_000)),
            entry("a2", usage = EntryUsage(input = 100_000)),
        )
        assertEquals(100_000L, contextUsageOf(entries, 200_000)!!.usedTokens)
    }

    @Test
    fun sumsInputCacheReadCacheWriteAndExcludesOutput() {
        val entries = listOf(
            entry("a1", usage = EntryUsage(input = 10_000, output = 999_999, cacheRead = 2_000, cacheWrite = 3_000)),
        )
        assertEquals(15_000L, contextUsageOf(entries, 200_000)!!.usedTokens)
    }

    @Test
    fun nullComponentCountsAsZero() {
        val entries = listOf(entry("a1", usage = EntryUsage(input = 10_000, cacheRead = null, cacheWrite = null)))
        assertEquals(10_000L, contextUsageOf(entries, 200_000)!!.usedTokens)
    }

    @Test
    fun nonAssistantEntriesWithUsageAreIgnored() {
        val entries = listOf(
            entry("u1", role = "user", usage = EntryUsage(input = 999_999)),
            entry("a1", role = "assistant", usage = EntryUsage(input = 10_000)),
        )
        assertEquals(10_000L, contextUsageOf(entries, 200_000)!!.usedTokens)
    }

    @Test
    fun assistantEntryWithAllNullUsageComponentsIsSkipped() {
        val entries = listOf(
            entry("a1", usage = EntryUsage(input = 10_000)),
            entry("a2", usage = EntryUsage(input = null, cacheRead = null, cacheWrite = null, output = 5_000)),
        )
        assertEquals(10_000L, contextUsageOf(entries, 200_000)!!.usedTokens)
    }

    @Test
    fun noQualifyingEntryReturnsNull() {
        val entries = listOf(
            entry("u1", role = "user"),
            entry("a1", role = "assistant", usage = null),
        )
        assertNull(contextUsageOf(entries, 200_000))
    }

    @Test
    fun labelFractionAndToneWithWindow() {
        val usage = contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 124_000))), 200_000)!!
        assertEquals("124k/200k", usage.label)
        assertEquals(0.62f, usage.fraction!!, 0.001f)
        assertEquals(ContextTone.Quiet, usage.tone)
    }

    @Test
    fun thresholds() {
        val warning = contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 160_000))), 200_000)!!
        assertEquals(ContextTone.Warning, warning.tone)

        val critical = contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 190_000))), 200_000)!!
        assertEquals(ContextTone.Critical, critical.tone)

        val stillQuiet = contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 159_999))), 200_000)!!
        assertEquals(ContextTone.Quiet, stillQuiet.tone)
    }

    @Test
    fun nullOrNonPositiveWindowHasNoFractionOrTone() {
        val nullWindow = contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 124_000))), null)!!
        assertEquals("124k", nullWindow.label)
        assertNull(nullWindow.fraction)
        assertEquals(ContextTone.Quiet, nullWindow.tone)

        val zeroWindow = contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 124_000))), 0)!!
        assertEquals("124k", zeroWindow.label)
        assertNull(zeroWindow.fraction)
        assertEquals(ContextTone.Quiet, zeroWindow.tone)
    }

    @Test
    fun usedAboveWindowClampsFractionAndStaysCritical() {
        val usage = contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 260_000))), 200_000)!!
        assertEquals(1f, usage.fraction!!, 0.001f)
        assertEquals(ContextTone.Critical, usage.tone)
        assertEquals("260k/200k", usage.label)
    }

    @Test
    fun formatterBoundaries() {
        assertEquals("842", contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 842))), null)!!.label)
        assertEquals("2k", contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 1_500))), null)!!.label)
        assertEquals("1M", contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 1_048_576))), null)!!.label)
        assertEquals("1.2M", contextUsageOf(listOf(entry("a1", usage = EntryUsage(input = 1_200_000))), null)!!.label)
    }
}
