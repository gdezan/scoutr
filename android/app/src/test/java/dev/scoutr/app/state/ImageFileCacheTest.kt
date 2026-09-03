package dev.scoutr.app.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageFileCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `key is stable per host, path, and size and keeps the extension`() {
        val cache = ImageFileCache(temp.root)
        val first = cache.cacheFileFor("hostA", "/workspace/pic.png", 4L, "pic.png")
        val second = cache.cacheFileFor("hostA", "/workspace/pic.png", 4L, "pic.png")

        assertEquals(first, second)
        assertTrue(first.name.endsWith(".png"))
        assertEquals(temp.root, first.parentFile)
    }

    @Test
    fun `distinct hosts, paths, and sizes do not collide`() {
        val cache = ImageFileCache(temp.root)
        val a = cache.cacheFileFor("hostA", "/workspace/pic.png", 4L, "pic.png")
        val b = cache.cacheFileFor("hostB", "/workspace/pic.png", 4L, "pic.png")
        val c = cache.cacheFileFor("hostA", "/workspace/other.png", 4L, "other.png")
        val d = cache.cacheFileFor("hostA", "/workspace/pic.png", 5L, "pic.png")
        val e = cache.cacheFileFor("hostA", "/workspace/pic.png", null, "pic.png")

        assertNotEquals(a.nameWithoutExtension, b.nameWithoutExtension)
        assertNotEquals(a.nameWithoutExtension, c.nameWithoutExtension)
        assertNotEquals(a.nameWithoutExtension, d.nameWithoutExtension)
        assertNotEquals(a.nameWithoutExtension, e.nameWithoutExtension)
        assertEquals(e, cache.cacheFileFor("hostA", "/workspace/pic.png", null, "pic.png"))
    }

    @Test
    fun `eligibility covers raster images and excludes svg`() {
        assertTrue(ImageFileCache.isImagePreviewable(true, "image/png"))
        assertTrue(ImageFileCache.isImagePreviewable(true, "image/jpeg"))
        assertTrue(ImageFileCache.isImagePreviewable(true, "image/gif"))
        assertTrue(ImageFileCache.isImagePreviewable(true, "image/webp"))
        assertFalse(ImageFileCache.isImagePreviewable(true, "image/svg+xml"))
        assertFalse(ImageFileCache.isImagePreviewable(false, "image/png"))
        assertFalse(ImageFileCache.isImagePreviewable(true, "application/pdf"))
        assertFalse(ImageFileCache.isImagePreviewable(true, null))
    }

    @Test
    fun `trim deletes oldest first and keeps total under cap`() {
        val cache = ImageFileCache(temp.root)
        val now = System.currentTimeMillis()
        val files = (0..2).map { index ->
            val file = cache.cacheFileFor("hostA", "/workspace/$index.png", 100L, "$index.png")
            file.writeBytes(ByteArray(100) { index.toByte() })
            file.setLastModified(now + index * 1_000)
            file
        }

        cache.trimToMaxBytes(150)

        assertFalse("oldest must go first", files[0].exists())
        assertFalse("middle must go next", files[1].exists())
        assertTrue("newest must survive", files[2].exists())
    }

    @Test
    fun `trim keeps everything when under cap`() {
        val cache = ImageFileCache(temp.root)
        val file = cache.cacheFileFor("hostA", "/workspace/pic.png", 10L, "pic.png")
        file.writeBytes(ByteArray(10))

        cache.trimToMaxBytes(1_000)

        assertTrue(file.exists())
    }
}
