package dev.scoutr.app.update

import dev.scoutr.app.data.ApkArtifact
import dev.scoutr.app.data.ApkBuild
import dev.scoutr.app.data.UpdateApkStatusResponse
import dev.scoutr.app.data.UpdateBuildResponse
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The adb-free update sequence: build on the host, poll, download, verify,
 * install. Every failure mode has to stop before the installer is handed a
 * file, because a bad APK reaches the user as a system install prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdaterTest {

    @get:Rule
    val cache = TemporaryFolder()

    private val bytes = "pretend this is an APK".toByteArray()

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun readyStatus(sha: String = sha256(bytes)) = UpdateApkStatusResponse(
        build = ApkBuild(
            state = "ready",
            buildId = 1,
            apk = ApkArtifact(size = bytes.size.toLong(), sha256 = sha, commit = "abc1234", version = "0.4.0"),
        ),
    )

    /** Records the file the updater would have committed to the device. */
    private class RecordingInstaller : ApkInstall {
        var installed: File? = null
        override suspend fun install(apk: File) {
            installed = apk
        }
    }

    private fun updater(api: FakeScoutrApi, installer: ApkInstall) = AppUpdater(
        api = api,
        installer = installer,
        cacheDir = cache.root,
        pollDelayMs = 0,
    )

    @Test
    fun `a good build downloads, verifies, and installs`() = runTest {
        val api = FakeScoutrApi()
        api.apkBytes = bytes
        api.updateApkStatusResult = Result.success(readyStatus())
        val installer = RecordingInstaller()
        val stages = mutableListOf<UpdateProgress>()

        updater(api, installer).run { stages += it }

        val installed = installer.installed
        assertTrue("the installer must receive the downloaded APK", installed != null)
        assertArrayEqualsBytes(bytes, installed!!.readBytes())
        assertEquals(listOf("updateBuild", "updateApkStatus", "downloadApk"), api.calls.map { it.name })
        assertTrue("must report a building stage", stages.any { it is UpdateProgress.Building })
        assertTrue("must report an installing stage", stages.last() is UpdateProgress.Installing)
    }

    @Test
    fun `a checksum mismatch aborts before the installer sees the file`() = runTest {
        val api = FakeScoutrApi()
        api.apkBytes = bytes
        api.updateApkStatusResult = Result.success(readyStatus(sha = sha256("different bytes".toByteArray())))
        val installer = RecordingInstaller()

        val failure = runCatching { updater(api, installer).run {} }.exceptionOrNull()

        assertTrue("must fail with an IOException", failure is IOException)
        assertTrue("must name the checksum", failure!!.message!!.contains("checksum"))
        assertNull("nothing may be installed", installer.installed)
        assertTrue("the bad download must be deleted", File(cache.root, "update/scoutr-update.apk").exists().not())
    }

    @Test
    fun `a failed host build surfaces gradle's reason`() = runTest {
        val api = FakeScoutrApi()
        api.updateApkStatusResult = Result.success(
            UpdateApkStatusResponse(build = ApkBuild(state = "failed", buildId = 1, error = "compileDebugKotlin FAILED")),
        )
        val installer = RecordingInstaller()

        val failure = runCatching { updater(api, installer).run {} }.exceptionOrNull()

        assertEquals("compileDebugKotlin FAILED", failure?.message)
        assertNull(installer.installed)
    }

    @Test
    fun `polling continues while the host is still building`() = runTest {
        val api = FakeScoutrApi()
        api.apkBytes = bytes
        var polls = 0
        api.onCall = { name, _ ->
            if (name == "updateApkStatus") {
                polls += 1
                if (polls < 3) {
                    Result.success(UpdateApkStatusResponse(build = ApkBuild(state = "building", buildId = 1)))
                } else {
                    Result.success(readyStatus())
                }
            } else {
                null
            }
        }
        api.updateBuildResult = Result.success(UpdateBuildResponse(build = ApkBuild(state = "building", buildId = 1)))
        val installer = RecordingInstaller()

        updater(api, installer).run {}

        assertEquals("must poll until the build is ready", 3, polls)
        assertTrue(installer.installed != null)
    }

    @Test
    fun `a build that never finishes gives up instead of polling forever`() = runTest {
        val api = FakeScoutrApi()
        api.updateApkStatusResult =
            Result.success(UpdateApkStatusResponse(build = ApkBuild(state = "building", buildId = 1)))
        val installer = RecordingInstaller()
        val bounded = AppUpdater(
            api = api,
            installer = installer,
            cacheDir = cache.root,
            pollDelayMs = 1,
            buildTimeoutMs = 3,
        )

        val failure = runCatching { bounded.run {} }.exceptionOrNull()

        assertTrue("must fail with an IOException", failure is IOException)
        assertTrue("must mention the timeout", failure!!.message!!.contains("did not finish"))
        assertNull(installer.installed)
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
