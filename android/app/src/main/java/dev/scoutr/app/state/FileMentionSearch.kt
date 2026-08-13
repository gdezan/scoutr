package dev.scoutr.app.state

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * `@` file-mention completion for the chat composer.
 *
 * Unlike [slashCommandQuery], which owns the whole input, a mention is a token
 * inside arbitrary text ("look at @src/Chat.kt and explain it"), so parsing is
 * caret-relative and completion rewrites only the token. Paths containing a
 * space are quoted — `@"my notes/todo.md"` — and the parser reads that form
 * back, including while the closing quote is still missing mid-drill-down.
 *
 * The bridge sends files only; directories are derived here so they can act as
 * navigation ([FileCandidate.isDirectory] completions keep the menu open).
 */
data class FileMention(
    /** Offset of the `@`. */
    val start: Int,
    /** Offset just past the token, where a completion's replacement ends. */
    val end: Int,
    /** Token text from after the `@` (and its opening quote) up to the caret. */
    val query: String,
)

data class FileCandidate(
    /** cwd-relative path; directories carry a trailing slash. */
    val path: String,
    val isDirectory: Boolean,
) {
    /** Final segment, without the directory slash. */
    val name: String get() = path.trimEnd('/').substringAfterLast('/')

    /** Everything before [name], "" at the top level. */
    val parent: String get() = path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
}

/** Menu rows past this are unreachable scrolling on a phone; matching stops there. */
const val MAX_FILE_MATCHES = 50

/**
 * The mention the caret sits in, or null when the caret is not inside one.
 * A mention opens on `@` at the start of the input or after whitespace.
 */
fun activeFileMention(text: String, caret: Int): FileMention? {
    if (caret !in 0..text.length) return null
    val at = text.lastIndexOf('@', startIndex = (caret - 1).coerceAtLeast(0))
    if (at < 0 || at >= caret) return null
    if (at > 0 && !text[at - 1].isWhitespace()) return null
    val quoted = text.getOrNull(at + 1) == '"'
    val contentStart = if (quoted) at + 2 else at + 1
    if (caret < contentStart) return null
    // Whitespace does not end a quoted mention; the closing quote does.
    // Without one there is no way to tell where the path was meant to stop,
    // so the token ends at the caret — completing must never swallow the rest
    // of the message. (Completions always close their own quote, so this only
    // arises for a quote typed by hand.)
    val closingQuote = if (quoted) text.indexOf('"', startIndex = contentStart) else -1
    val end = when {
        quoted && closingQuote >= 0 -> closingQuote + 1
        quoted -> caret
        else -> text.indexOfFirst(contentStart) { it.isWhitespace() }
    }
    val contentEnd = when {
        quoted && closingQuote >= 0 -> closingQuote
        quoted -> text.length
        else -> end
    }
    if (caret > contentEnd) return null
    return FileMention(start = at, end = end, query = text.substring(contentStart, caret))
}

private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (index in from until length) if (predicate(this[index])) return index
    return length
}

/**
 * Candidates for [query] over the cwd's [files].
 *
 * An empty query, or one ending in `/`, browses that directory's direct
 * children (directories first, then files, alphabetical). Anything else
 * fuzzy-matches every descendant beneath the query's directory prefix.
 */
fun matchFileMentions(files: List<String>, query: String): List<FileCandidate> {
    val prefix = query.substringBeforeLast('/', missingDelimiterValue = "")
        .let { if (it.isEmpty()) "" else "$it/" }
    val needle = query.substringAfterLast('/')
    val under = files.filter { it.startsWith(prefix) && it.length > prefix.length }
    if (needle.isEmpty()) return browseChildren(under, prefix)
    return under
        .flatMap { file -> candidatesIn(file, prefix) }
        .distinct()
        .mapNotNull { candidate -> scoreCandidate(candidate, prefix, needle)?.let { candidate to it } }
        .sortedWith(
            compareBy(
                { it.second },
                { it.first.path.length },
                { it.first.path },
            ),
        )
        .take(MAX_FILE_MATCHES)
        .map { it.first }
}

/** Direct children of [prefix]: each file, or the directory that contains it. */
private fun browseChildren(under: List<String>, prefix: String): List<FileCandidate> {
    val dirs = sortedSetOf<String>()
    val leaves = sortedSetOf<String>()
    for (file in under) {
        val remainder = file.removePrefix(prefix)
        val slash = remainder.indexOf('/')
        if (slash < 0) leaves += file else dirs += prefix + remainder.substring(0, slash + 1)
    }
    return (dirs.map { FileCandidate(it, isDirectory = true) } +
        leaves.map { FileCandidate(it, isDirectory = false) }).take(MAX_FILE_MATCHES)
}

/** A file and every directory between [prefix] and it, all as candidates. */
private fun candidatesIn(file: String, prefix: String): List<FileCandidate> {
    val candidates = mutableListOf(FileCandidate(file, isDirectory = false))
    var cursor = file.indexOf('/', startIndex = prefix.length)
    while (cursor >= 0) {
        candidates += FileCandidate(file.substring(0, cursor + 1), isDirectory = true)
        cursor = file.indexOf('/', startIndex = cursor + 1)
    }
    return candidates
}

/**
 * Lower is better, null when [needle] does not match at all. Hits inside the
 * final segment outrank hits that only span directory names, and contiguous
 * runs outrank scattered subsequences — so "chatscr" finds ChatScreen.kt
 * ahead of chat/state/Screener.kt.
 */
private fun scoreCandidate(candidate: FileCandidate, prefix: String, needle: String): Int? {
    val nameScore = segmentScore(candidate.name, needle)
    if (nameScore != null) return nameScore
    val remainder = candidate.path.removePrefix(prefix)
    return segmentScore(remainder, needle)?.plus(3)
}

private fun segmentScore(text: String, needle: String): Int? = when {
    text.startsWith(needle, ignoreCase = true) -> 0
    text.contains(needle, ignoreCase = true) -> 1
    isSubsequence(text, needle) -> 2
    else -> null
}

private fun isSubsequence(text: String, needle: String): Boolean {
    var cursor = 0
    for (char in text) {
        if (cursor == needle.length) break
        if (char.equalsIgnoreCase(needle[cursor])) cursor++
    }
    return cursor == needle.length
}

private fun Char.equalsIgnoreCase(other: Char): Boolean =
    this == other || lowercaseChar() == other.lowercaseChar()

/**
 * Replace [mention] with [candidate].
 *
 * Files insert a trailing space and leave the caret past it; directories
 * insert their trailing slash with no space, so the menu stays open on the new
 * prefix and the next tap drills one level deeper. Paths containing a space
 * are quoted; a directory's quote stays open until a file closes it.
 */
fun completeFileMention(
    value: TextFieldValue,
    mention: FileMention,
    candidate: FileCandidate,
): TextFieldValue {
    val quoted = candidate.path.contains(' ')
    // Completing mid-sentence lands on text that already has a separator;
    // adding another would leave a double space behind the caret.
    val spaceFollows = value.text.getOrNull(mention.end)?.isWhitespace() == true
    val appendSpace = !candidate.isDirectory && !spaceFollows
    val inserted = buildString {
        append('@')
        if (quoted) append('"')
        append(candidate.path)
        // A directory closes its quote too, with the caret parked inside it:
        // an open quote would make the token run to the end of the message,
        // and completing it would then delete whatever followed.
        if (quoted) append('"')
        if (appendSpace) append(' ')
    }
    val caret = when {
        // Inside the quotes, ready to keep drilling.
        candidate.isDirectory && quoted -> mention.start + inserted.length - 1
        candidate.isDirectory -> mention.start + inserted.length
        // Skip past the separator that was already there, so typing continues
        // after the mention rather than butting up against it.
        spaceFollows -> mention.start + inserted.length + 1
        else -> mention.start + inserted.length
    }
    val text = value.text.replaceRange(mention.start, mention.end, inserted)
    return TextFieldValue(text = text, selection = TextRange(caret))
}
