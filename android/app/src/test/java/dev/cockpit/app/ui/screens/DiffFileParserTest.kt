package dev.cockpit.app.ui.screens

import dev.cockpit.app.data.RepoDiffFileStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffFileParserTest {
    @Test
    fun parsesMultipleFilesAndJoinsStats() {
        val files = parseDiffFiles(
            "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n+one\n" +
                "diff --git a/b.txt b/b.txt\n--- a/b.txt\n+++ b/b.txt\n-two",
            listOf(
                RepoDiffFileStat("a.txt", additions = 1, deletions = 0),
                RepoDiffFileStat("b.txt", additions = 0, deletions = 1),
            ),
            truncated = false,
        )
        assertEquals(listOf("a.txt", "b.txt"), files.map { it.path })
        assertEquals(1, files[0].stat?.additions)
        assertTrue(files[0].raw.orEmpty().contains("+one"))
    }

    @Test
    fun preservesRenameBinaryDeletionAndMalformedChunks() {
        val files = parseDiffFiles(
            "diff --git a/old.txt b/new.txt\nrename from old.txt\nrename to new.txt\n" +
                "diff --git a/image.png b/image.png\nBinary files a/image.png and b/image.png differ\n" +
                "diff --git a/removed.txt b/removed.txt\n--- a/removed.txt\n+++ /dev/null\n-old\n" +
                "not a boundary",
            emptyList(),
            truncated = false,
        )
        assertEquals(listOf("new.txt", "image.png", "removed.txt"), files.map { it.path })
        assertEquals(3, files.size)
        assertTrue(files.all { it.raw.orEmpty().isNotEmpty() })
    }

    @Test
    fun marksStatOnlyFilesUnavailableWhenTruncated() {
        val files = parseDiffFiles(
            "diff --git a/a.txt b/a.txt\n+++ b/a.txt\n+one",
            listOf(
                RepoDiffFileStat("a.txt", 1, 0),
                RepoDiffFileStat("b.txt", 2, 3),
            ),
            truncated = true,
        )
        assertEquals(listOf("a.txt", "b.txt"), files.map { it.path })
        assertTrue(files[1].unavailable)
        assertEquals(null, files[1].raw)
    }
}
