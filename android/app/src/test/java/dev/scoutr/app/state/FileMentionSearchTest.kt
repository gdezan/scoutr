package dev.scoutr.app.state

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMentionSearchTest {

    private val files = listOf(
        "AGENTS.md",
        "README.md",
        "android/app/build.gradle.kts",
        "android/app/src/main/java/dev/scoutr/app/ui/screens/ChatScreen.kt",
        "android/app/src/main/java/dev/scoutr/app/chat/state/Screener.kt",
        "bridge/src/dirs.ts",
        "bridge/src/files.ts",
    )

    // ── token parsing ──

    @Test
    fun mentionOpensAtStartAndAfterWhitespace() {
        assertEquals("src", activeFileMention("@src", 4)?.query)
        assertEquals("src", activeFileMention("look at @src", 12)?.query)
        assertEquals(8, activeFileMention("look at @src", 12)?.start)
    }

    @Test
    fun emailLikeAtSignsAreNotMentions() {
        assertNull(activeFileMention("mail me@example.com", 19))
    }

    @Test
    fun queryRunsToTheCaretNotTheTokenEnd() {
        val mention = activeFileMention("look at @src/Chat.kt and explain", 13)
        assertEquals("src/", mention?.query)
        // The completion still replaces the whole token, not just the prefix.
        assertEquals(20, mention?.end)
    }

    @Test
    fun whitespaceClosesAnUnquotedMention() {
        assertNull(activeFileMention("@src/Chat.kt and more", 21))
    }

    @Test
    fun caretOutsideTheTokenClosesTheMention() {
        // Caret sits before the `@`.
        assertNull(activeFileMention("look at @src/Chat.kt", 3))
    }

    @Test
    fun quotedMentionSurvivesInternalWhitespace() {
        val text = """look at @"my notes/todo.md" now"""
        val mention = activeFileMention(text, 26)
        assertEquals("my notes/todo.md", mention?.query)
        assertEquals(8, mention?.start)
        assertEquals(27, mention?.end)
    }

    @Test
    fun unclosedQuoteStaysAnOpenMention() {
        val mention = activeFileMention("""@"my no""", 7)
        assertEquals("my no", mention?.query)
    }

    // ── matching ──

    @Test
    fun bareMentionBrowsesTopLevelDirectoriesThenFiles() {
        val matches = matchFileMentions(files, "")
        assertEquals(
            listOf("android/", "bridge/", "AGENTS.md", "README.md"),
            matches.map { it.path },
        )
        assertTrue(matches.first().isDirectory)
        assertTrue(!matches.last().isDirectory)
    }

    @Test
    fun trailingSlashBrowsesThatDirectorysDirectChildren() {
        assertEquals(listOf("bridge/src/"), matchFileMentions(files, "bridge/").map { it.path })
        assertEquals(
            listOf("bridge/src/dirs.ts", "bridge/src/files.ts"),
            matchFileMentions(files, "bridge/src/").map { it.path },
        )
    }

    @Test
    fun fuzzyMatchesAcrossDepthsWithFilenameHitsFirst() {
        val matches = matchFileMentions(files, "chatscr").map { it.path }
        assertEquals(
            "android/app/src/main/java/dev/scoutr/app/ui/screens/ChatScreen.kt",
            matches.first(),
        )
        // Screener.kt matches only as a scattered subsequence, so it ranks below.
        assertTrue(matches.size > 1)
    }

    @Test
    fun fuzzyMatchIsScopedToTheDirectoryPrefix() {
        val matches = matchFileMentions(files, "bridge/fil").map { it.path }
        assertEquals(listOf("bridge/src/files.ts"), matches)
    }

    @Test
    fun directoriesAreCandidatesForATextQuery() {
        val matches = matchFileMentions(files, "scree")
        assertTrue(matches.any { it.isDirectory && it.path.endsWith("screens/") })
    }

    @Test
    fun noMatchesReturnsEmpty() {
        assertEquals(emptyList<FileCandidate>(), matchFileMentions(files, "zzzz"))
    }

    @Test
    fun candidateNameAndParentSplitThePath() {
        val file = FileCandidate("bridge/src/files.ts", isDirectory = false)
        assertEquals("files.ts", file.name)
        assertEquals("bridge/src", file.parent)
        val dir = FileCandidate("bridge/src/", isDirectory = true)
        assertEquals("src", dir.name)
        assertEquals("bridge", dir.parent)
    }

    // ── completion ──

    @Test
    fun completingAFileMidSentenceReusesTheExistingSpace() {
        val value = TextFieldValue("look at @brid and explain", TextRange(13))
        val mention = activeFileMention(value.text, 13)!!
        val completed = completeFileMention(value, mention, FileCandidate("bridge/src/files.ts", false))
        assertEquals("look at @bridge/src/files.ts and explain", completed.text)
        // Caret lands past the separator that was already there.
        assertEquals(29, completed.selection.start)
    }

    @Test
    fun completingAFileAtTheEndAddsATrailingSpace() {
        val value = TextFieldValue("look at @brid", TextRange(13))
        val mention = activeFileMention(value.text, 13)!!
        val completed = completeFileMention(value, mention, FileCandidate("bridge/src/files.ts", false))
        assertEquals("look at @bridge/src/files.ts ", completed.text)
        assertEquals(29, completed.selection.start)
    }

    @Test
    fun completingADirectoryKeepsTheMentionOpen() {
        val value = TextFieldValue("@and", TextRange(4))
        val mention = activeFileMention(value.text, 4)!!
        val completed = completeFileMention(value, mention, FileCandidate("android/", isDirectory = true))
        assertEquals("@android/", completed.text)
        // No trailing space: the caret stays inside a still-open mention.
        assertEquals("android/", activeFileMention(completed.text, completed.selection.start)?.query)
    }

    @Test
    fun pathsWithSpacesAreQuoted() {
        val value = TextFieldValue("@my", TextRange(3))
        val mention = activeFileMention(value.text, 3)!!
        val completed = completeFileMention(value, mention, FileCandidate("my notes/todo.md", false))
        assertEquals("""@"my notes/todo.md" """, completed.text)
    }

    @Test
    fun drillingIntoASpacedDirectoryClosesItsQuoteAndKeepsTheRestOfTheMessage() {
        val value = TextFieldValue("look at @my and explain", TextRange(11))
        val mention = activeFileMention(value.text, 11)!!
        val completed = completeFileMention(value, mention, FileCandidate("my dir/", isDirectory = true))
        assertEquals("""look at @"my dir/" and explain""", completed.text)
        // Caret parks inside the quotes so the menu stays open on the prefix…
        assertEquals(17, completed.selection.start)
        assertEquals("my dir/", activeFileMention(completed.text, completed.selection.start)?.query)
        // …and completing from there leaves the trailing text alone.
        val next = activeFileMention(completed.text, completed.selection.start)!!
        val file = completeFileMention(completed, next, FileCandidate("my dir/todo.md", false))
        assertEquals("""look at @"my dir/todo.md" and explain""", file.text)
    }

    @Test
    fun aHandTypedUnclosedQuoteEndsAtTheCaret() {
        val value = TextFieldValue("""look at @"my no and explain""", TextRange(15))
        val mention = activeFileMention(value.text, 15)!!
        assertEquals("my no", mention.query)
        val completed = completeFileMention(value, mention, FileCandidate("my notes/todo.md", false))
        assertEquals("""look at @"my notes/todo.md" and explain""", completed.text)
    }

    @Test
    fun completingOutOfAQuotedDrillDownClosesTheQuote() {
        val value = TextFieldValue("""@"my notes/""", TextRange(11))
        val mention = activeFileMention(value.text, 11)!!
        assertEquals("my notes/", mention.query)
        val completed = completeFileMention(value, mention, FileCandidate("my notes/todo.md", false))
        assertEquals("""@"my notes/todo.md" """, completed.text)
    }
}
