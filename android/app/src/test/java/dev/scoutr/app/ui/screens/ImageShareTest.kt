package dev.scoutr.app.ui.screens

import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Provider least-privilege and save-mapping proof for the image handoff. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageShareTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var imagesDir: File

    @Before
    fun setUp() {
        imagesDir = File(app.cacheDir, "images").apply { mkdirs() }
    }

    // FileProvider caches its path strategy statically per authority, but each
    // Robolectric test gets a fresh sandbox cache dir — reset between tests.
    @After
    fun tearDown() {
        val cache = androidx.core.content.FileProvider::class.java
            .getDeclaredField("sCache")
            .apply { isAccessible = true }
            .get(null) as MutableMap<*, *>
        cache.clear()
    }
    @Test
    fun `shareUriFor emits the configured authority for staged files`() {
        val staged = File(imagesDir, "abc123.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val uri = ImageShare.shareUriFor(app, imagesDir, staged)

        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.fileprovider", uri.authority)
        assertTrue("uri must stay under the images root: $uri", uri.path!!.contains("/images/"))
    }

    @Test
    fun `shareUriFor refuses files outside the image cache`() {
        val sibling = File(app.cacheDir, "evil.png").apply { writeBytes(byteArrayOf(1)) }

        try {
            ImageShare.shareUriFor(app, imagesDir, sibling)
            fail("a cache sibling must never be shareable")
        } catch (rejected: IllegalArgumentException) {
            assertTrue(rejected.message!!.contains("outside the image cache"))
        }
        try {
            ImageShare.shareUriFor(app, imagesDir, File("/tmp/evil.png"))
            fail("an absolute outsider must never be shareable")
        } catch (rejected: IllegalArgumentException) {
            assertTrue(rejected.message!!.contains("outside the image cache"))
        }
    }

    @Test
    fun `openWith returns false when no app handles the image`() {
        val staged = File(imagesDir, "abc123.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertFalse(ImageShare.openWith(app, imagesDir, staged, "image/png"))
    }

    @Test
    fun `openWith launches the chooser when a handler exists`() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val images = File(activity.cacheDir, "images").apply { mkdirs() }
        val staged = File(images, "abc123.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = ImageShare.shareUriFor(activity, images, staged)
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/png")
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
        }
        val info = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = app.packageName
                name = "Gallery"
            }
        }
        org.robolectric.Shadows.shadowOf(activity.packageManager).addResolveInfoForIntent(view, info)

        assertTrue(ImageShare.openWith(activity, images, staged, "image/png"))
        val launched = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, launched.action)
    }
    fun `downloadValues maps filename and mime with a pending row`() {
        val values = ImageShare.downloadValues("pic.png", "image/png")

        assertEquals("pic.png", values.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals("image/png", values.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals("Download/", values.getAsString(MediaStore.Downloads.RELATIVE_PATH))
        assertEquals(1, values.getAsInteger(MediaStore.Downloads.IS_PENDING))
    }

    @Test
    fun `downloadValues falls back to image wildcard mime`() {
        val values = ImageShare.downloadValues("pic", null)

        assertEquals("image/*", values.getAsString(MediaStore.Downloads.MIME_TYPE))
    }

    @Test
    fun `saveToDownloads returns the stored name and keeps the cache file`() {
        val staged = File(imagesDir, "abc123.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val stored = runBlocking { ImageShare.saveToDownloads(app, staged, "pic.png", "image/png") }

        assertEquals("pic.png", stored)
        assertTrue("save must never delete the cache file", staged.exists())
    }
}
