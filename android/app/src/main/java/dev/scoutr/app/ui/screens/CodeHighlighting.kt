package dev.scoutr.app.ui.screens

import dev.scoutr.app.ui.theme.ScoutrSpace
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle as ComposeFontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.scoutr.app.ui.theme.DiffPalette
import dev.scoutr.app.ui.theme.ScoutrMono
import dev.textmate.grammar.Grammar
import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.grammar.raw.RawGrammar
import dev.textmate.grammar.tokenize.StateStack
import dev.textmate.regex.JoniOnigLib
import dev.textmate.theme.FontStyle as TextMateFontStyle
import dev.textmate.theme.Theme
import dev.textmate.theme.ThemeReader
import java.io.InputStream

/** TextMate grammars bundled with the app. The names mirror VS Code language ids. */
internal enum class TextMateLanguage(val grammarAsset: String, val scopeName: String) {
    KOTLIN("kotlin.tmLanguage.json", "source.kotlin"),
    JAVASCRIPT("JavaScript.tmLanguage.json", "source.js"),
    TYPESCRIPT("TypeScript.tmLanguage.json", "source.ts"),
    JSON("JSON.tmLanguage.json", "source.json"),
    MARKDOWN("markdown.tmLanguage.json", "text.html.markdown"),
    JAVA("java.tmLanguage.json", "source.java"),
    PYTHON("MagicPython.tmLanguage.json", "source.python"),
    GO("go.tmLanguage.json", "source.go"),
    RUST("rust.tmLanguage.json", "source.rust"),
    SHELL("shell-unix-bash.tmLanguage.json", "source.shell"),
    YAML("yaml.tmLanguage.json", "source.yaml"),
    SQL("sql.tmLanguage.json", "source.sql"),
    RUBY("ruby.tmLanguage.json", "source.ruby"),
    C("c.tmLanguage.json", "source.c"),
    CPP("cpp.tmLanguage.json", "source.cpp"),
    DART("dart.tmLanguage.json", "source.dart"),
    CSHARP("csharp.tmLanguage.json", "source.cs"),
    SWIFT("swift.tmLanguage.json", "source.swift"),
    PHP("php.tmLanguage.json", "source.php"),
    PERL("perl.tmLanguage.json", "source.perl"),
    CSS("css.tmLanguage.json", "source.css"),
    HTML("html.tmLanguage.json", "text.html.basic"),
    XML("xml.tmLanguage.json", "text.xml"),
    INI("ini.tmLanguage.json", "source.ini"),
    DOCKER("docker.tmLanguage.json", "source.dockerfile"),
}

/** Maps a repository path to the closest bundled VS Code grammar. */
internal fun languageForPath(path: String): TextMateLanguage? {
    val fileName = path.substringAfterLast('/').lowercase()
    return when {
        fileName == "dockerfile" -> TextMateLanguage.DOCKER
        fileName.endsWith(".kt") || fileName.endsWith(".kts") -> TextMateLanguage.KOTLIN
        fileName.endsWith(".ts") || fileName.endsWith(".tsx") -> TextMateLanguage.TYPESCRIPT
        fileName.endsWith(".js") || fileName.endsWith(".jsx") || fileName.endsWith(".mjs") || fileName.endsWith(".cjs") ->
            TextMateLanguage.JAVASCRIPT
        fileName.endsWith(".java") -> TextMateLanguage.JAVA
        fileName.endsWith(".py") -> TextMateLanguage.PYTHON
        fileName.endsWith(".rb") -> TextMateLanguage.RUBY
        fileName.endsWith(".go") -> TextMateLanguage.GO
        fileName.endsWith(".rs") -> TextMateLanguage.RUST
        fileName.endsWith(".c") || fileName.endsWith(".h") -> TextMateLanguage.C
        fileName.endsWith(".cpp") || fileName.endsWith(".cc") || fileName.endsWith(".cxx") ||
            fileName.endsWith(".hpp") -> TextMateLanguage.CPP
        fileName.endsWith(".dart") -> TextMateLanguage.DART
        fileName.endsWith(".cs") -> TextMateLanguage.CSHARP
        fileName.endsWith(".swift") -> TextMateLanguage.SWIFT
        fileName.endsWith(".php") -> TextMateLanguage.PHP
        fileName.endsWith(".pl") -> TextMateLanguage.PERL
        fileName.endsWith(".sh") || fileName.endsWith(".bash") || fileName.endsWith(".zsh") -> TextMateLanguage.SHELL
        fileName.endsWith(".json") || fileName.endsWith(".jsonc") -> TextMateLanguage.JSON
        fileName.endsWith(".md") || fileName.endsWith(".markdown") -> TextMateLanguage.MARKDOWN
        fileName.endsWith(".yml") || fileName.endsWith(".yaml") -> TextMateLanguage.YAML
        fileName.endsWith(".sql") -> TextMateLanguage.SQL
        fileName.endsWith(".css") || fileName.endsWith(".scss") -> TextMateLanguage.CSS
        fileName.endsWith(".html") || fileName.endsWith(".htm") -> TextMateLanguage.HTML
        fileName.endsWith(".xml") -> TextMateLanguage.XML
        fileName.endsWith(".ini") || fileName.endsWith(".toml") -> TextMateLanguage.INI
        else -> null
    }
}

/** A colored range inside one line; [start] inclusive, [end] exclusive. */
internal data class SyntaxSpan(
    val start: Int,
    val end: Int,
    val color: Color,
    val fontStyle: Set<TextMateFontStyle> = emptySet(),
)

/**
 * Thin adapter around KotlinTextMate. It owns mutable grammar state, so one
 * instance is used serially for a render pass and never shared across threads.
 */
internal class TextMateHighlighter(
    private val openAsset: (String) -> InputStream,
) {
    private val grammars = mutableMapOf<TextMateLanguage, Grammar>()
    private val rawGrammars: Map<String, RawGrammar> by lazy {
        buildMap {
            val assets = TextMateLanguage.values().map { it.grammarAsset } + listOf(
                "yaml-1.2.tmLanguage.json",
                "yaml-embedded.tmLanguage.json",
            )
            assets.forEach { asset ->
                runCatching {
                    openAsset("textmate/grammars/$asset").use(GrammarReader::readGrammar)
                }.getOrNull()?.let { put(it.scopeName, it) }
            }
        }
    }
    private val theme: Theme by lazy {
        openAsset("textmate/themes/dark_vs.json").use { base ->
            openAsset("textmate/themes/dark_plus.json").use { overlay ->
                ThemeReader.readTheme(base, overlay)
            }
        }
    }

    fun highlight(language: TextMateLanguage?, lines: List<String>): List<List<SyntaxSpan>> {
        if (language == null || lines.isEmpty()) return List(lines.size) { emptyList() }

        return try {
            val grammar = grammars.getOrPut(language) { loadGrammar(language) }
            var state: StateStack? = null
            lines.map { line ->
                val result = grammar.tokenizeLine(line, state)
                state = result.ruleStack
                result.tokens.mapNotNull { token ->
                    val start = token.startIndex.coerceIn(0, line.length)
                    val end = token.endIndex.coerceIn(0, line.length)
                    if (start >= end) {
                        null
                    } else {
                        val style = theme.match(token.scopes)
                        SyntaxSpan(
                            start = start,
                            end = end,
                            color = Color(style.foreground.toInt()),
                            fontStyle = style.fontStyle,
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // A grammar should never make a review unreadable. The line remains
            // visible with its normal foreground if an asset or regex is broken.
            List(lines.size) { emptyList() }
        }
    }

    private fun loadGrammar(language: TextMateLanguage): Grammar {
        val raw = rawGrammars[language.scopeName]
            ?: openAsset("textmate/grammars/${language.grammarAsset}").use(GrammarReader::readGrammar)
        return Grammar(
            raw.scopeName,
            raw,
            JoniOnigLib(),
            grammarLookup = { scopeName -> rawGrammars[scopeName] },
            injectionLookup = { rawGrammars.values.toList() },
        )
    }
}

@Composable
internal fun rememberTextMateHighlighter(): TextMateHighlighter {
    val context = LocalContext.current
    return remember(context) {
        TextMateHighlighter { asset -> context.assets.open(asset) }
    }
}

private fun SyntaxSpan.asStyle(): SpanStyle = SpanStyle(
    color = color,
    fontWeight = if (TextMateFontStyle.BOLD in fontStyle) FontWeight.Bold else null,
    fontStyle = if (TextMateFontStyle.ITALIC in fontStyle) ComposeFontStyle.Italic else null,
    textDecoration = when {
        TextMateFontStyle.UNDERLINE in fontStyle && TextMateFontStyle.STRIKETHROUGH in fontStyle ->
            TextDecoration.Underline + TextDecoration.LineThrough
        TextMateFontStyle.UNDERLINE in fontStyle -> TextDecoration.Underline
        TextMateFontStyle.STRIKETHROUGH in fontStyle -> TextDecoration.LineThrough
        else -> null
    },
)

internal enum class DiffLineKind { Metadata, Hunk, Context, Added, Deleted }

/** One parsed line from a unified diff, with the diff marker removed from [code]. */
internal data class DiffLineModel(
    val id: Int,
    val raw: String,
    val code: String,
    val kind: DiffLineKind,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
    val innerChange: IntRange? = null,
)

internal data class DiffPair(
    val full: DiffLineModel? = null,
    val left: DiffLineModel? = null,
    val right: DiffLineModel? = null,
)

internal data class DiffDocument(
    val lines: List<DiffLineModel>,
    val pairs: List<DiffPair>,
)

private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

/** Parses unified diff lines without confusing file headers for deletions. */
internal fun parseDiffDocument(rawLines: List<String>): DiffDocument {
    var oldLine: Int? = null
    var newLine: Int? = null
    var inHunk = false
    val lines = rawLines.mapIndexed { index, raw ->
        val header = hunkHeader.find(raw)
        if (header != null) {
            oldLine = header.groupValues[1].toInt()
            newLine = header.groupValues[3].toInt()
            inHunk = true
            return@mapIndexed DiffLineModel(index, raw, "", DiffLineKind.Hunk)
        }
        if (!inHunk && (raw.startsWith(" ") || (raw.startsWith("+") && !raw.startsWith("+++")) || (raw.startsWith("-") && !raw.startsWith("---")))) {
            inHunk = true
        }
        if (!inHunk) return@mapIndexed DiffLineModel(index, raw, raw, DiffLineKind.Metadata)

        when {
            raw.startsWith("+") -> {
                val lineNumber = newLine
                if (lineNumber != null) newLine = lineNumber + 1
                DiffLineModel(index, raw, raw.drop(1), DiffLineKind.Added, newLineNumber = lineNumber)
            }
            raw.startsWith("-") -> {
                val lineNumber = oldLine
                if (lineNumber != null) oldLine = lineNumber + 1
                DiffLineModel(index, raw, raw.drop(1), DiffLineKind.Deleted, oldLineNumber = lineNumber)
            }
            raw.startsWith(" ") -> {
                val oldLineNumber = oldLine
                val newLineNumber = newLine
                if (oldLineNumber != null) oldLine = oldLineNumber + 1
                if (newLineNumber != null) newLine = newLineNumber + 1
                DiffLineModel(
                    index,
                    raw,
                    raw.drop(1),
                    DiffLineKind.Context,
                    oldLineNumber = oldLineNumber,
                    newLineNumber = newLineNumber,
                )
            }
            else -> DiffLineModel(index, raw, raw, DiffLineKind.Metadata)
        }
    }

    val pairs = mutableListOf<DiffPair>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        when {
            line.kind == DiffLineKind.Context -> {
                pairs += DiffPair(left = line, right = line)
                index++
            }
            line.kind == DiffLineKind.Deleted -> {
                val deleted = buildList {
                    var cursor = index
                    while (cursor < lines.size && lines[cursor].kind == DiffLineKind.Deleted) add(lines[cursor++])
                }
                index += deleted.size
                val added = buildList {
                    var cursor = index
                    while (cursor < lines.size && lines[cursor].kind == DiffLineKind.Added) add(lines[cursor++])
                }
                index += added.size
                val count = maxOf(deleted.size, added.size)
                repeat(count) { pairIndex ->
                    pairs += DiffPair(
                        left = deleted.getOrNull(pairIndex),
                        right = added.getOrNull(pairIndex),
                    )
                }
            }
            line.kind == DiffLineKind.Added -> {
                pairs += DiffPair(right = line)
                index++
            }
            else -> {
                pairs += DiffPair(full = line)
                index++
            }
        }
    }

    val withInnerChanges = pairs.map { pair ->
        val left = pair.left
        val right = pair.right
        if (left != null && right != null && left.kind == DiffLineKind.Deleted && right.kind == DiffLineKind.Added) {
            val (leftRange, rightRange) = innerChangeRanges(left.code, right.code)
            pair.copy(left = left.copy(innerChange = leftRange), right = right.copy(innerChange = rightRange))
        } else {
            pair
        }
    }
    return DiffDocument(lines, withInnerChanges)
}

private fun innerChangeRanges(left: String, right: String): Pair<IntRange?, IntRange?> {
    var prefix = 0
    while (prefix < left.length && prefix < right.length && left[prefix] == right[prefix]) prefix++
    var suffix = 0
    while (
        suffix < left.length - prefix &&
        suffix < right.length - prefix &&
        left[left.length - 1 - suffix] == right[right.length - 1 - suffix]
    ) {
        suffix++
    }
    val leftEnd = left.length - suffix
    val rightEnd = right.length - suffix
    return Pair(
        if (prefix < leftEnd) prefix until leftEnd else null,
        if (prefix < rightEnd) prefix until rightEnd else null,
    )
}

private data class DiffSyntax(
    val left: List<SyntaxSpan> = emptyList(),
    val right: List<SyntaxSpan> = emptyList(),
)

private enum class DiffSide { Left, Right }

private fun syntaxByLine(
    document: DiffDocument,
    language: TextMateLanguage?,
    highlighter: TextMateHighlighter,
): Map<Int, DiffSyntax> {
    val output = mutableMapOf<Int, DiffSyntax>()
    val segment = mutableListOf<DiffLineModel>()

    fun flushSegment() {
        if (segment.isEmpty()) return
        val leftLines = segment.filter { it.kind == DiffLineKind.Context || it.kind == DiffLineKind.Deleted }
        val rightLines = segment.filter { it.kind == DiffLineKind.Context || it.kind == DiffLineKind.Added }
        highlighter.highlight(language, leftLines.map { it.code }).forEachIndexed { index, spans ->
            val line = leftLines[index]
            output[line.id] = (output[line.id] ?: DiffSyntax()).copy(left = spans)
        }
        highlighter.highlight(language, rightLines.map { it.code }).forEachIndexed { index, spans ->
            val line = rightLines[index]
            output[line.id] = (output[line.id] ?: DiffSyntax()).copy(right = spans)
        }
        segment.clear()
    }

    document.lines.forEach { line ->
        when (line.kind) {
            DiffLineKind.Context, DiffLineKind.Added, DiffLineKind.Deleted -> segment += line
            DiffLineKind.Metadata, DiffLineKind.Hunk -> flushSegment()
        }
    }
    flushSegment()
    return output
}

private fun diffAccent(kind: DiffLineKind): Color? = when (kind) {
    DiffLineKind.Added -> DiffPalette.Added
    DiffLineKind.Deleted -> DiffPalette.Deleted
    else -> null
}

private fun diffBackground(kind: DiffLineKind): Color = when (kind) {
    DiffLineKind.Added -> DiffPalette.AddedBackground
    DiffLineKind.Deleted -> DiffPalette.DeletedBackground
    else -> Color.Transparent
}

/**
 * Shared diff renderer. Phones use the compact inline view; tablets and desktop
 * windows use paired panes like VS Code, with independent syntax state per side.
 */
@Composable
internal fun DiffLines(
    lines: List<String>,
    language: TextMateLanguage?,
    wrapLines: Boolean,
    horizontalPadding: Dp = ScoutrSpace.lg,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val highlighter = rememberTextMateHighlighter()
    val document = remember(lines) { parseDiffDocument(lines) }
    val spans = remember(document, language, highlighter) { syntaxByLine(document, language, highlighter) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sideBySide = maxWidth >= 720.dp
        val horizontalScroll = rememberScrollState()
        Column(
            Modifier.then(if (!wrapLines && !sideBySide) Modifier.horizontalScroll(horizontalScroll) else Modifier),
        ) {
            if (sideBySide) {
                document.pairs.forEach { pair ->
                    if (pair.full != null) {
                        ScrollableDiffLine(
                            line = pair.full,
                            spans = spans[pair.full.id]?.right.orEmpty(),
                            wrapLines = wrapLines,
                            horizontalPadding = horizontalPadding,
                            style = style,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                            DiffPaneCell(pair.left, spans, DiffSide.Left, wrapLines, style, Modifier.weight(1f))
                            Box(Modifier.width(1.dp).heightIn(min = 18.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            DiffPaneCell(pair.right, spans, DiffSide.Right, wrapLines, style, Modifier.weight(1f))
                        }
                    }
                }
            } else {
                document.lines.forEach { line ->
                    RenderDiffLine(
                        line,
                        (if (line.kind == DiffLineKind.Deleted) spans[line.id]?.left else spans[line.id]?.right).orEmpty(),
                        wrapLines,
                        horizontalPadding,
                        style,
                        lineNumber = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollableDiffLine(
    line: DiffLineModel,
    spans: List<SyntaxSpan>,
    wrapLines: Boolean,
    horizontalPadding: Dp,
    style: TextStyle,
    lineNumber: Int? = null,
    modifier: Modifier = Modifier,
) {
    if (wrapLines) {
        RenderDiffLine(line, spans, wrapLines, horizontalPadding, style, lineNumber, modifier)
    } else {
        Box(modifier.horizontalScroll(rememberScrollState())) {
            RenderDiffLine(line, spans, wrapLines, horizontalPadding, style, lineNumber, Modifier.width(IntrinsicSize.Max))
        }
    }
}
@Composable
private fun DiffPaneCell(
    line: DiffLineModel?,
    spans: Map<Int, DiffSyntax>,
    side: DiffSide,
    wrapLines: Boolean,
    style: TextStyle,
    modifier: Modifier,
) {
    if (line == null) {
        Box(modifier.heightIn(min = 18.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)))
    } else {
        ScrollableDiffLine(
            line = line,
            spans = when (side) {
                DiffSide.Left -> spans[line.id]?.left.orEmpty()
                DiffSide.Right -> spans[line.id]?.right.orEmpty()
            },
            wrapLines = wrapLines,
            horizontalPadding = 6.dp,
            style = style,
            lineNumber = when (side) {
                DiffSide.Left -> line.oldLineNumber
                DiffSide.Right -> line.newLineNumber
            },
            modifier = modifier,
        )
    }
}


@Composable
private fun RenderDiffLine(
    line: DiffLineModel,
    spans: List<SyntaxSpan>,
    wrapLines: Boolean,
    horizontalPadding: Dp,
    style: TextStyle,
    lineNumber: Int?,
    modifier: Modifier = Modifier,
) {
    val accent = diffAccent(line.kind)
    val defaultColor = MaterialTheme.colorScheme.onSurface
    val annotated = remember(line, spans, accent, defaultColor) {
        buildAnnotatedString {
            append(line.raw)
            addStyle(SpanStyle(color = defaultColor), 0, line.raw.length)
            if (accent != null && line.raw.isNotEmpty()) {
                addStyle(SpanStyle(color = accent), 0, 1)
            }
            if (line.kind == DiffLineKind.Context || line.kind == DiffLineKind.Added || line.kind == DiffLineKind.Deleted) {
                val offset = if (line.raw.isNotEmpty()) 1 else 0
                spans.forEach { span ->
                    val start = (offset + span.start).coerceIn(0, line.raw.length)
                    val end = (offset + span.end).coerceIn(0, line.raw.length)
                    if (start < end) addStyle(span.asStyle(), start, end)
                }
                line.innerChange?.let { range ->
                    val start = (offset + range.first).coerceIn(0, line.raw.length)
                    val end = (offset + range.last + 1).coerceIn(0, line.raw.length)
                    if (start < end && accent != null) {
                        addStyle(SpanStyle(background = accent.copy(alpha = 0.18f)), start, end)
                    }
                }
            }
        }
    }
    val widthModifier = if (wrapLines) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max)
    Row(
        modifier
            .then(widthModifier)
            .background(diffBackground(line.kind))
            .padding(horizontal = horizontalPadding, vertical = 1.dp),
    ) {
        Box(
            Modifier
                .width(if (accent != null) 3.dp else 0.dp)
                .heightIn(min = 17.dp)
                .background(accent?.copy(alpha = 0.75f) ?: Color.Transparent),
        )
        if (lineNumber != null) {
            Text(
                text = lineNumber.toString(),
                style = style,
                fontFamily = ScoutrMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(34.dp).padding(horizontal = 4.dp),
                maxLines = 1,
            )
        }
        Text(
            annotated,
            style = style,
            fontFamily = ScoutrMono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (wrapLines) Int.MAX_VALUE else 1,
            softWrap = wrapLines,
            modifier = if (wrapLines) Modifier.weight(1f) else Modifier,
        )
    }
}

/** Syntax-highlighted non-diff source lines with one persistent TextMate state. */
@Composable
internal fun CodeLines(
    lines: List<String>,
    language: TextMateLanguage?,
    wrapLines: Boolean,
    horizontalPadding: Dp = ScoutrSpace.lg,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val highlighter = rememberTextMateHighlighter()
    val spans = remember(lines, language, highlighter) { highlighter.highlight(language, lines) }
    lines.forEachIndexed { index, line ->
        val defaultColor = MaterialTheme.colorScheme.onSurface
        val annotated = remember(line, spans[index], defaultColor) {
            buildAnnotatedString {
                append(line)
                addStyle(SpanStyle(color = defaultColor), 0, line.length)
                spans[index].forEach { span ->
                    val start = span.start.coerceIn(0, line.length)
                    val end = span.end.coerceIn(0, line.length)
                    if (start < end) addStyle(span.asStyle(), start, end)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 1.dp),
        ) {
            Text(
                annotated,
                style = style,
                fontFamily = ScoutrMono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (wrapLines) Int.MAX_VALUE else 1,
                softWrap = wrapLines,
            )
        }
    }
}
