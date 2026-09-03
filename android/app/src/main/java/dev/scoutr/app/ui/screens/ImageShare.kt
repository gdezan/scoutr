package dev.scoutr.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * System handoff for workspace images the viewer downloaded into [imagesDir].
 * Only files the viewer itself staged are ever shared — [shareUriFor] refuses
 * is no `file://` fallback. Grants are read-only, one-intent, chooser-wrapped.
 */
object ImageShare {
    /** Content URI for a staged image; throws when [cacheFile] escapes the image cache. */
    fun shareUriFor(context: Context, imagesDir: File, cacheFile: File): Uri {
        val root = imagesDir.canonicalFile
        if (cacheFile.canonicalFile.startsWith(root).not()) {
            throw IllegalArgumentException("refusing to share a file outside the image cache")
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
    }

    /**
     * Hands a staged image to a system gallery/editor via a chooser.
     * Returns false when no app can open it (caller shows the no-handler copy).
     * The handler pre-check is load-bearing: a chooser-wrapped intent always
     * resolves, so without it the no-handler branch would be dead. It needs the
     * matching `<queries>` entry in the manifest on API 30+.
     */
    fun openWith(context: Context, imagesDir: File, cacheFile: File, mime: String?): Boolean {
        val uri = shareUriFor(context, imagesDir, cacheFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: "image/*")
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) return false
        return try {
            context.startActivity(Intent.createChooser(intent, "Open image with"))
            true
        } catch (notFound: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Copies a staged image into Downloads on API 29+ via MediaStore (no
     * storage permission). Returns the stored display name, or throws with a
     * user-presentable message. The cache file is never deleted by a save.
     */
    suspend fun saveToDownloads(
        context: Context,
        cacheFile: File,
        filename: String,
        mime: String?,
    ): String = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        val values = downloadValues(filename, mime)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("could not create the download entry")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                cacheFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("could not write the download entry")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            if (resolver.update(uri, done, null, null) == 0) {
                throw IllegalStateException("could not publish the download entry")
            }
            filename
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    /** Streams a staged image into a SAF `ACTION_CREATE_DOCUMENT` destination (API 26–28). */
    suspend fun saveToUri(context: Context, cacheFile: File, destination: Uri) =
        withContext(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver
            resolver.openOutputStream(destination)?.use { out ->
                cacheFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("could not write the chosen file")
        }

    /** Pending-row values for a Downloads insert; `IS_PENDING` clears only after the bytes land. */
    internal fun downloadValues(filename: String, mime: String?): ContentValues =
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mime ?: "image/*")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
}
