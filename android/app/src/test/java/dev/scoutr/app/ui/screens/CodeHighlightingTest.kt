package dev.scoutr.app.ui.screens

import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightingTest {

    @Test
    fun languageForPathMapsKnownExtensions() {
        assertEquals(SyntaxLanguage.KOTLIN, languageForPath("src/Foo.kt"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, languageForPath("src/index.ts"))
        assertEquals(SyntaxLanguage.PYTHON, languageForPath("app.py"))
        assertEquals(SyntaxLanguage.SHELL, languageForPath("bin/run.sh"))
        assertEquals(SyntaxLanguage.JAVA, languageForPath("Main.java"))
        assertEquals(SyntaxLanguage.RUST, languageForPath("lib.rs"))
        assertEquals(SyntaxLanguage.GO, languageForPath("main.go"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, languageForPath("app.js"))
    }

    @Test
    fun languageForPathIsNullForUnknownExtensions() {
        assertEquals(null, languageForPath("notes.txt"))
        assertEquals(null, languageForPath("README.md"))
        assertEquals(null, languageForPath("data.json"))
        assertEquals(null, languageForPath("no-extension"))
    }

    @Test
    fun highlightLineReturnsSpansForKotlinCode() {
        val spans = highlightLine("val answer = 42", SyntaxLanguage.KOTLIN)
        assertTrue("expected token spans for kotlin line, got $spans", spans.isNotEmpty())
        // Spans must stay inside the line bounds.
        spans.forEach {
            assertTrue(it.start >= 0)
            assertTrue(it.end <= 15)
            assertTrue(it.end > it.start)
        }
    }

    @Test
    fun highlightLineFallsBackToGenericTokensForUnknownLanguage() {
        val spans = highlightLine("""{"key": "value", "n": 3}""", null)
        assertTrue("expected generic spans for JSON, got $spans", spans.isNotEmpty())
    }

    @Test
    fun highlightLineReturnsEmptyOnBlankOrError() {
        assertTrue(highlightLine("", null).isEmpty())
        assertTrue(highlightLine("   ", SyntaxLanguage.KOTLIN).isEmpty())
    }
}
