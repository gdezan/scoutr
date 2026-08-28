package dev.scoutr.app.update

import dev.scoutr.app.data.ApkArtifact
import dev.scoutr.app.data.ApkBuild
import dev.scoutr.app.data.UpdateApkStatusResponse
import dev.scoutr.app.data.UpdateBuildResponse
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The adb-free update sequence: build on the host, poll, download, verify.
 * Every failure mode has to stop before a staged file is declared installable,
 * because a bad APK reaches the user as a system install prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdaterTest {

    @get:Rule
    val files = TemporaryFolder()

    private val bytes = "pretend this is an APK".toByteArray()

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun artifact(sha: String = sha256(bytes)) =
        ApkArtifact(size = bytes.size.toLong(), sha256 = sha, commit = "abc1234", version = "0.4.0")

    private fun readyStatus(sha: String = sha256(bytes)) = UpdateApkStatusResponse(
        build = ApkBuild(state = "ready", buildId = 1, apk = artifact(sha)),
    )

    private val staging: UpdateStaging by lazy { UpdateStaging(File(files.root, "update")) }

    private fun updater(api: FakeScoutrApi) = AppUpdater(api = api, staging = staging, pollDelayMs = 0)

    /** The `resumeFrom` the updater asked the transport for. */
    private fun requestedResumeFrom(api: FakeScoutrApi): Long =
        api.calls.first { it.name == "downloadApk" }.args["resumeFrom"] as Long

    @Test
    fun `a good build downloads, verifies, and leaves a staged APK behind`() = runTest {
        val api = FakeScoutrApi()
        api.apkBytes = bytes
        api.updateApkStatusResult = Result.success(readyStatus())
        val stages = mutableListOf<UpdateProgress>()

        val staged = updater(api).stage { stages += it }

        assertEquals(bytes.toList(), staged.apk.readBytes().toList())
        assertEquals("abc1234", staged.identity.commit)
        assertEquals(listOf("updateBuild", "updateApkStatus", "downloadApk"), api.calls.map { it.name })
        assertTrue("must report a building stage", stages.any { it is UpdateProgress.Building })
        assertTrue("must report a downloading stage", stages.any { it is UpdateProgress.Downloading })
        // The sidecar outlives the pipeline so a later launch can offer the
        // install without rebuilding or re-downloading.
        assertNotNull(staging.identity())
        assertEquals(bytes.size.toLong(), staging.partialBytes())
    }

    @Test
    fun `a partial file for the same artifact resumes instead of restarting`() = runTest {
        val api = FakeScoutrApi()
        api.apkBytes = bytes
        api.updateApkStatusResult = Result.success(readyStatus())
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.4.0"),
        )
        staging.apkFile().writeBytes(bytes.copyOfRange(0, 6))

        val staged = updater(api).stage {}

        assertEquals(6L, requestedResumeFrom(api))
        assertEquals(bytes.toList(), staged.apk.readBytes().toList())
    }

    @Test
    fun `a partial file from a superseded build is discarded and downloaded fresh`() = runTest {
        val api = FakeScoutrApi()
        api.apkBytes = bytes
        api.updateApkStatusResult = Result.success(readyStatus())
        // The host rebuilt while the phone was offline: the staged prefix
        // belongs to bytes nobody is serving any more.
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256("older".toByteArray()), size = 99, version = "0.3.0"),
        )
        staging.apkFile().writeBytes("older".toByteArray())

        val staged = updater(api).stage {}

        assertEquals(0L, requestedResumeFrom(api))
        assertEquals(bytes.toList(), staged.apk.readBytes().toList())
    }

    @Test
    fun `a checksum mismatch discards the staged file instead of leaving it resumable`() = runTest {
        val api = FakeScoutrApi()
        api.apkBytes = bytes
        api.updateApkStatusResult = Result.success(readyStatus(sha = sha256("different bytes".toByteArray())))

        val failure = runCatching { updater(api).stage {} }.exceptionOrNull()

        assertTrue("must fail with an IOException", failure is IOException)
        assertTrue("must name the checksum", failure!!.message!!.contains("checksum"))
        assertFalse("the bad download must not survive", File(files.root, "update/staged.apk").exists())
        assertNull("and must not be describable as a resumable partial", staging.identity())
    }

    @Test
    fun `a failed host build surfaces gradle's reason`() = runTest {
        val api = FakeScoutrApi()
        api.updateApkStatusResult = Result.success(
            UpdateApkStatusResponse(build = ApkBuild(state = "failed", buildId = 1, error = "compileDebugKotlin FAILED")),
        )

        val failure = runCatching { updater(api).stage {} }.exceptionOrNull()

        assertEquals("compileDebugKotlin FAILED", failure?.message)
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

        val staged = updater(api).stage {}

        assertEquals("must poll until the build is ready", 3, polls)
        assertEquals(bytes.toList(), staged.apk.readBytes().toList())
    }

    @Test
    fun `a build that never finishes gives up instead of polling forever`() = runTest {
        val api = FakeScoutrApi()
        api.updateApkStatusResult =
            Result.success(UpdateApkStatusResponse(build = ApkBuild(state = "building", buildId = 1)))
        val bounded = AppUpdater(api = api, staging = staging, pollDelayMs = 1, buildTimeoutMs = 3)

        val failure = runCatching { bounded.stage {} }.exceptionOrNull()

        assertTrue("must fail with an IOException", failure is IOException)
        assertTrue("must mention the timeout", failure!!.message!!.contains("did not finish"))
    }
}
