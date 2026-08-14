package dev.scoutr.app.ui

/**
 * Paths arriving from the bridge are absolute on the *host*, not on the phone, so
 * the app cannot read HOME to shorten them. The design contract still requires
 * `~/…` in every mono metadata slot (`docs/design/Scoutr Design System.dc.html`
 * §9a — "shorten paths to ~/repo"), so the home prefix is recognised by shape.
 *
 * A path that does not look like a home directory is returned untouched; the full
 * value stays available in headers and long-press copy.
 */
fun shortenHostPath(path: String?): String? {
    val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val trimmed = if (raw.length > 1) raw.trimEnd('/') else raw
    HOME_PREFIXES.forEach { prefix ->
        val match = prefix.matchAt(trimmed, 0) ?: return@forEach
        val rest = trimmed.substring(match.value.length)
        return if (rest.isEmpty()) "~" else "~/$rest"
    }
    return trimmed
}

/** `/home/<user>/`, `/Users/<user>/` (macOS), and `/root/` all collapse to `~`. */
private val HOME_PREFIXES = listOf(
    Regex("""/(?:home|Users)/[^/]+/?"""),
    Regex("""/root/?"""),
)
