package dev.scoutr.app.state

/**
 * Workspace file references inside transcript text (F1).
 *
 * Agents name absolute host paths when they report what they read, wrote, or
 * generated ("wrote the report to /home/gd/site/report.html"). The bridge only
 * serves files under the session's active-agent cwd, so a reference is
 * actionable exactly when it sits under [cwd] and looks like a file (its final
 * segment carries an extension). Directories stay out: they open in the file
 * browser, not the viewer, and bare directory prefixes in prose are usually
 * not meant as links.
 *
 * Pure text parsing — no bridge round-trip. Existence is resolved lazily by
 * the viewer, which already owns the missing/moved triage states.
 */
data class WorkspaceFileRef(
    /** Absolute host path, as matched in the transcript. */
    val absolutePath: String,
    /** cwd-relative path, the form the file-viewer route takes. */
    val relativePath: String,
    /** Final segment, the chip label. */
    val name: String,
)

/** Chips past this per message are unreachable noise; the viewer stays one tap away. */
const val MAX_WORKSPACE_REFS = 8

/**
 * Absolute workspace paths in [text], in first-mention order, deduplicated.
 * Strips markdown emphasis, trailing sentence punctuation, and `:line`/`:line:col`
 * suffixes agents append ("main.kt:42"). Returns empty when [cwd] is blank.
 */
fun extractWorkspaceRefs(text: String, cwd: String): List<WorkspaceFileRef> {
    val root = cwd.trimEnd('/')
    if (root.isEmpty() || !text.contains(root)) return emptyList()
    val pattern = Regex(Regex.escape(root) + "/[^\\s`\"'()\\[\\]{}<>]+")
    val refs = linkedMapOf<String, WorkspaceFileRef>()
    for (match in pattern.findAll(text)) {
        val ref = toWorkspaceRef(match.value, root) ?: continue
        refs[ref.absolutePath] = ref
        if (refs.size >= MAX_WORKSPACE_REFS) break
    }
    return refs.values.toList()
}

/**
 * [path] as a viewer reference, absolute or cwd-relative (file-edit diffs
 * carry both: pi reports absolute paths, other backends relative ones).
 * Lexically normalized before containment, so `sub/../a.kt` resolves and
 * `../escape.kt` is refused. Used by surfaces that already hold a path
 * instead of mining prose.
 */
fun workspaceRefForPath(path: String, cwd: String): WorkspaceFileRef? {
    val root = cwd.trimEnd('/')
    if (root.isEmpty()) return null
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return null
    val joined = if (trimmed.startsWith('/')) trimmed else "$root/$trimmed"
    return toWorkspaceRef(joined, root, requireExtension = false)
}

/**
 * [absolutePath] as a viewer reference when it sits under [cwd] and looks
 * like a file, else null. Absolute-only twin of [workspaceRefForPath].
 */
fun absoluteToWorkspaceRef(absolutePath: String, cwd: String): WorkspaceFileRef? {
    if (!absolutePath.trim().startsWith('/')) return null
    return workspaceRefForPath(absolutePath, cwd)
}

private fun toWorkspaceRef(raw: String, root: String, requireExtension: Boolean = true): WorkspaceFileRef? {
    var path = raw.trimEnd('.', ',', ';', ':', '!', '?', '*', '\'', '"')
    path = LINE_SUFFIX_REGEX.replace(path, "")
    // A trailing slash marks a directory; normalization would erase that
    // signal, so refuse before collapsing `.` and `..` segments. A `..`
    // that escapes the workspace then refuses at containment; one that
    // stays inside resolves.
    if (path.endsWith('/')) return null
    path = normalizeLexicalPath(path)
    if (!path.startsWith("$root/") || path.length <= root.length + 1) return null
    val relative = path.removePrefix("$root/")
    if (relative.isEmpty() || relative.endsWith('/')) return null
    val name = relative.substringAfterLast('/')
    // A dot in the final segment is what separates a file from a directory
    // prefix in prose; hidden files (".gitignore") still qualify.
    if (requireExtension && !name.contains('.')) return null
    return WorkspaceFileRef(absolutePath = path, relativePath = relative, name = name)
}

private val LINE_SUFFIX_REGEX = Regex(":\\d+(:\\d+)?$")

/** Collapse `.`, `..`, and duplicate slashes without touching the filesystem. */
private fun normalizeLexicalPath(path: String): String {
    val absolute = path.startsWith('/')
    val kept = ArrayDeque<String>()
    for (segment in path.split('/')) {
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." -> if (kept.isNotEmpty()) kept.removeLast()
            else -> kept.add(segment)
        }
    }
    val joined = kept.joinToString("/")
    return if (absolute) "/$joined" else joined
}
