package dev.cockpit.app.ui.screens

import androidx.compose.ui.graphics.Color
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme

/**
 * Syntax token colors tuned for the always-dark Cockpit theme. They sit on
 * top of the diff marker colors: added/deleted lines keep their cyan/red
 * identity and the background tint, while tokens inside them gain semantic
 * color.
 */
internal object SyntaxPalette {
    val Keyword = Color(0xFFC792EA)
    val String = Color(0xFFC3E88D)
    val Number = Color(0xFFF78C6C)
    val Comment = Color(0xFF676E95)
    val Type = Color(0xFFFFCB6B)
}

/** A colored range inside one line; [start] inclusive, [end] exclusive. */
internal data class SyntaxSpan(val start: Int, val end: Int, val color: Color)

private fun Color.rgb24(): Int = ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()

/**
 * The Highlights engine colors tokens by returning the theme slot's rgb, so
 * we feed it our own palette and get our colors back as span rangers.
 */
private val highlightTheme: SyntaxTheme = SyntaxTheme(
    key = "cockpit-dark",
    code = 0xEDEDED,
    keyword = SyntaxPalette.Keyword.rgb24(),
    string = SyntaxPalette.String.rgb24(),
    literal = SyntaxPalette.Number.rgb24(),
    comment = SyntaxPalette.Comment.rgb24(),
    metadata = SyntaxPalette.Type.rgb24(),
    multilineComment = SyntaxPalette.Comment.rgb24(),
    punctuation = 0xEDEDED, // keep punctuation in the line's base color
    mark = 0xEDEDED,
)

/** Maps a file path to a Highlights grammar; null means the generic fallback. */
internal fun languageForPath(path: String): SyntaxLanguage? = when (path.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> SyntaxLanguage.KOTLIN
    "java" -> SyntaxLanguage.JAVA
    "ts", "tsx" -> SyntaxLanguage.TYPESCRIPT
    "js", "jsx" -> SyntaxLanguage.JAVASCRIPT
    "py" -> SyntaxLanguage.PYTHON
    "rb" -> SyntaxLanguage.RUBY
    "go" -> SyntaxLanguage.GO
    "rs" -> SyntaxLanguage.RUST
    "c", "h" -> SyntaxLanguage.C
    "cpp", "cc", "cxx", "hpp" -> SyntaxLanguage.CPP
    "dart" -> SyntaxLanguage.DART
    "cs" -> SyntaxLanguage.CSHARP
    "sh", "bash", "zsh" -> SyntaxLanguage.SHELL
    "swift" -> SyntaxLanguage.SWIFT
    "php" -> SyntaxLanguage.PHP
    "pl" -> SyntaxLanguage.PERL
    else -> null
}

/** Generic tokenizer for files without a grammar (JSON, Markdown, YAML, text). */
private val fallbackPatterns = listOf(
    Regex("\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"") to SyntaxPalette.String,
    Regex("'[^'\\\\]*(?:\\\\.[^'\\\\]*)*'") to SyntaxPalette.String,
    Regex("\\b(0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b") to SyntaxPalette.Number,
    Regex("\\b(true|false|null|undefined|None|True|False)\\b") to SyntaxPalette.Number,
    Regex("//[^\\n]*|#[^\\n]*") to SyntaxPalette.Comment,
    Regex(
        "\\b(def|class|fun|function|return|if|else|elif|for|while|in|is|as|when|import|from|export|const|let|var|" +
            "struct|enum|interface|type|val|private|public|internal|override|package|extends|implements|new|object|" +
            "data|sealed|switch|case|break|continue|try|catch|finally|throw|async|await)\\b",
    ) to SyntaxPalette.Keyword,
)

/** Highlights one line; returns token spans, never throws. */
internal fun highlightLine(line: String, language: SyntaxLanguage?): List<SyntaxSpan> {
    if (line.isBlank()) return emptyList()
    if (language == null) {
        return fallbackPatterns.flatMap { (pattern, color) ->
            pattern.findAll(line).map { SyntaxSpan(it.range.first, it.range.last + 1, color) }
        }.sortedBy { it.start }
    }
    return try {
        val highlights: List<CodeHighlight> = Highlights.Builder()
            .code(line)
            .language(language)
            .theme(highlightTheme)
            .build()
            .getHighlights()
        highlights.mapNotNull { highlight ->
            val color = (highlight as? ColorHighlight) ?: return@mapNotNull null
            val location = color.location
            SyntaxSpan(location.start.coerceAtLeast(0), location.end.coerceAtMost(line.length), Color(color.rgb or -0x1000000))
        }.sortedBy { it.start }
    } catch (_: Exception) {
        emptyList()
    }
}
