package dev.cockpit.app.ui.screens

import dev.cockpit.app.data.RepoDiffFileStat

data class ParsedDiffFile(
    val path: String,
    val raw: String?,
    val stat: RepoDiffFileStat?,
    val unavailable: Boolean,
)

/** Splits the bridge's raw diff without dropping any returned file chunk. */
internal fun parseDiffFiles(
    diff: String,
    stats: List<RepoDiffFileStat>,
    truncated: Boolean,
): List<ParsedDiffFile> {
    if (diff.isEmpty()) return stats.map { ParsedDiffFile(it.path, null, it, unavailable = truncated) }

    val boundaries = Regex("(?m)^diff --git ").findAll(diff).map { it.range.first }.toList()
    val starts = if (boundaries.isEmpty()) listOf(0) else {
        buildList {
            if (boundaries.first() > 0) add(0)
            addAll(boundaries)
        }
    }
    val chunks = starts.mapIndexed { index, start ->
        diff.substring(start, starts.getOrNull(index + 1) ?: diff.length)
    }.filter { it.isNotBlank() }

    val parsed = chunks.map { chunk ->
        val path = diffPath(chunk)
        ParsedDiffFile(path, chunk, stats.firstOrNull { it.path == path }, unavailable = false)
    }
    if (!truncated) return parsed

    val parsedPaths = parsed.map { it.path }.toSet()
    return parsed + stats.asSequence()
        .filterNot { it.path in parsedPaths }
        .map { ParsedDiffFile(it.path, raw = null, stat = it, unavailable = true) }
}

private fun diffPath(chunk: String): String {
    val renamePath = chunk.lineSequence()
        .firstOrNull { it.startsWith("rename to ") }
        ?.removePrefix("rename to ")
    if (renamePath != null) return renamePath

    val plusPath = chunk.lineSequence()
        .firstOrNull { it.startsWith("+++ ") }
        ?.removePrefix("+++ ")
        ?.takeUnless { it == "/dev/null" }
        ?.removePrefix("b/")
    if (plusPath != null) return plusPath

    val minusPath = chunk.lineSequence()
        .firstOrNull { it.startsWith("--- ") }
        ?.removePrefix("--- ")
        ?.takeUnless { it == "/dev/null" }
        ?.removePrefix("a/")
    if (minusPath != null) return minusPath

    val boundary = chunk.lineSequence().firstOrNull().orEmpty()
    return boundary.removePrefix("diff --git a/")
        .substringBefore(" b/")
        .ifBlank { "(unknown file)" }
}
