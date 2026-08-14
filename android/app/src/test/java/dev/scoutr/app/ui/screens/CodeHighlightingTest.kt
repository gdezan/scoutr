package dev.scoutr.app.ui.screens

import java.io.ByteArrayInputStream
import java.io.File
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightingTest {

    @Test
    fun languageForPathMapsBundledTextMateGrammars() {
        assertEquals(TextMateLanguage.KOTLIN, languageForPath("src/Foo.kt"))
        assertEquals(TextMateLanguage.TYPESCRIPT, languageForPath("src/index.ts"))
        assertEquals(TextMateLanguage.PYTHON, languageForPath("app.py"))
        assertEquals(TextMateLanguage.SHELL, languageForPath("bin/run.sh"))
        assertEquals(TextMateLanguage.JAVA, languageForPath("Main.java"))
        assertEquals(TextMateLanguage.RUST, languageForPath("lib.rs"))
        assertEquals(TextMateLanguage.GO, languageForPath("main.go"))
        assertEquals(TextMateLanguage.JAVASCRIPT, languageForPath("app.js"))
        assertEquals(TextMateLanguage.JSON, languageForPath("data.json"))
        assertEquals(TextMateLanguage.MARKDOWN, languageForPath("README.md"))
    }

    @Test
    fun languageForPathIsNullForUnknownExtensions() {
        assertEquals(null, languageForPath("notes.txt"))
        assertEquals(null, languageForPath("no-extension"))
    }

    @Test
    fun textMateHighlightingPreservesMultilineCommentState() {
        val grammar = """
            {
              "scopeName": "source.test",
              "patterns": [{"name": "comment.block", "begin": "/\\*", "end": "\\*/"}]
            }
        """.trimIndent()
        val theme = """
            {"name":"test","tokenColors":[
              {"settings":{"foreground":"#FFFFFF"}},
              {"scope":"comment.block","settings":{"foreground":"#FF0000"}}
            ]}
        """.trimIndent()
        val highlighter = TextMateHighlighter { asset ->
            ByteArrayInputStream((if (asset.contains("grammars")) grammar else theme).toByteArray())
        }

        val spans = highlighter.highlight(TextMateLanguage.KOTLIN, listOf("/* open", "still */ val answer = 42"))

        assertTrue(spans[0].isNotEmpty())
        assertEquals(Color(0xFFFF0000L.toInt()), spans[0].first().color)
        assertTrue("the second line must begin in the comment scope", spans[1].first().start == 0)
        assertTrue("the comment scope must continue across lines", spans[1].first().color == spans[0].first().color)
    }

    @Test
    fun bundledKotlinGrammarProducesSpans() {
        val root = listOf(File("src/main/assets"), File("app/src/main/assets"), File("../app/src/main/assets"))
            .firstOrNull { it.isDirectory }
            ?: return
        val highlighter = TextMateHighlighter { asset -> File(root, asset).inputStream() }

        val spans = highlighter.highlight(TextMateLanguage.KOTLIN, listOf("val answer = 42"))

        assertTrue("bundled grammar should produce token spans", spans[0].isNotEmpty())
    }

    @Test
    fun diffParserKeepsHeadersOutOfSyntaxAndFindsInnerChanges() {
        val document = parseDiffDocument(
            listOf(
                "diff --git a/Foo.kt b/Foo.kt",
                "@@ -1 +1 @@",
                "-val oldName = 1",
                "+val newName = 1",
            ),
        )

        assertEquals(DiffLineKind.Metadata, document.lines[0].kind)
        assertEquals(DiffLineKind.Hunk, document.lines[1].kind)
        assertEquals("val oldName = 1", document.lines[2].code)
        assertEquals("val newName = 1", document.lines[3].code)
        val changedPair = document.pairs.last()
        assertNotNull(changedPair.left?.innerChange)
        assertNotNull(changedPair.right?.innerChange)
    }

    @Test
    fun fileEditHunkWithoutHeaderStillRecognizesPatchMarkers() {
        val document = parseDiffDocument(listOf(" # Usage:", "-old line", "+new line"))

        assertEquals(DiffLineKind.Context, document.lines[0].kind)
        assertEquals(DiffLineKind.Deleted, document.lines[1].kind)
        assertEquals(DiffLineKind.Added, document.lines[2].kind)
        assertNull(document.lines[1].oldLineNumber)
        assertNull(document.lines[2].newLineNumber)
    }

    @Test
    fun unknownLanguageDoesNotApplyGenericRegexColors() {
        val highlighter = TextMateHighlighter { error("no grammar should be opened") }
        assertTrue(highlighter.highlight(null, listOf("key: value # not a comment"))[0].isEmpty())
    }
}
