package dev.scoutr.app.state

import java.io.File
import java.security.MessageDigest

/**
 * Bounded on-disk home for workspace images the viewer downloads.
 *
 * The viewer pre-downloads `/api/file/bytes` into `cacheDir/images/` (C3:
 * auto-purgeable cache, never persistent storage) and hands the file to Coil,
 * so auth never enters the image pipeline. Keys namespace by host/profile plus
 * absolute workspace path, so two hosts serving the same path never share a
 * file. A missing cache file is a normal miss — the caller re-downloads.
 *
 * Pure `java.io`: no Context, JVM-testable.
 */
class ImageFileCache(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    /**
     * Stable cache file for one workspace image; creates the directory on demand.
     * The triage [sizeBytes] versions the key, so an overwritten image never
     * mixes a stale prefix with a fresh tail — a size change is a new key.
     */
    fun cacheFileFor(hostKey: String, absolutePath: String, sizeBytes: Long?, filename: String): File {
        dir.mkdirs()
        return File(dir, keyFor(hostKey, absolutePath, sizeBytes) + extensionOf(filename))
    }

    /** Deletes oldest-first by last-modified until the directory fits [maxBytes]. Best-effort. */
    fun trimToMaxBytes(max: Long = maxBytes) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= max) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= max) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun keyFor(hostKey: String, absolutePath: String, sizeBytes: Long?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$hostKey|$absolutePath|${sizeBytes?.toString() ?: "unknown"}".toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                append(HEX[(b.toInt() ushr 4) and 0xF])
                append(HEX[b.toInt() and 0xF])
            }
        }
    }

    private fun extensionOf(filename: String): String {
        val name = filename.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot)
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 100L * 1024 * 1024

        /** Image types the viewer renders instead of binary triage. SVG stays out (I5). */
        val IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        /**
         * Single eligibility predicate shared by the ViewModel and the UI branch,
         * so the two can never drift: binary triage with a renderable mime.
         * The byte-cap pre-check is triage only — the bridge 413 is authoritative.
         */
        fun isImagePreviewable(binary: Boolean, mime: String?): Boolean =
            binary && mime in IMAGE_MIMES

        private const val HEX = "0123456789abcdef"
    }
}
