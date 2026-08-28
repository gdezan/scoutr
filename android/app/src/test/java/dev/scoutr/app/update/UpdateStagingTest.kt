package dev.scoutr.app.update

import dev.scoutr.app.data.ApkArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Staging is what lets a multi-minute download survive leaving the screen, the
 * app, or the process, so its reuse rules carry the whole feature's safety: a
 * file may only be resumed or installed when it provably describes the APK the
 * host is offering right now.
 */
class UpdateStagingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun staging(): UpdateStaging = UpdateStaging(File(temp.root, "update"))

    private val artifact = ApkArtifact(
        size = 12,
        sha256 = "abc123",
        commit = "deadbee",
        version = "0.4.0",
        versionCode = 4000,
    )

    /** As recorded before the download: real bytes, not yet checksummed. */
    private val identity = StagedIdentity(
        commit = artifact.commit,
        sha256 = artifact.sha256,
        size = artifact.size,
        version = artifact.version,
    )

    private val verified = identity.copy(verified = true)

    @Test
    fun `a recorded identity survives a round trip through the sidecar`() {
        val staging = staging()
        staging.record(identity)

        assertEquals(identity, UpdateStaging(File(temp.root, "update")).identity())
    }

    @Test
    fun `nothing staged reads as no identity and no bytes`() {
        val staging = staging()

        assertNull(staging.identity())
        assertEquals(0L, staging.partialBytes())
        assertNull(staging.completeFor(artifact))
    }

    @Test
    fun `partialBytes reports the length of a short file`() {
        val staging = staging()
        staging.apkFile().writeBytes(ByteArray(5))

        assertEquals(5L, staging.partialBytes())
    }

    @Test
    fun `a complete, verified file matching the artifact is offered for install`() {
        val staging = staging()
        staging.record(identity)
        staging.apkFile().writeBytes(ByteArray(artifact.size.toInt()))
        staging.markVerified()

        val staged = staging.completeFor(artifact)

        assertNotNull(staged)
        assertEquals(verified, staged!!.identity)
        assertEquals(artifact.size, staged.apk.length())
    }

    @Test
    fun `a full-length file whose checksum was never confirmed is not installable`() {
        val staging = staging()
        staging.record(identity)
        // Exactly the state a process killed between the last byte and the
        // sha256 check leaves behind. Length proves nothing about content, and
        // an unverified APK must never reach the installer.
        staging.apkFile().writeBytes(ByteArray(artifact.size.toInt()))

        assertNull(staging.complete())
        assertNull(staging.completeFor(artifact))
    }

    @Test
    fun `a file shorter than the artifact is not complete`() {
        val staging = staging()
        staging.record(verified)
        // The sidecar is written before the bytes, so its size claim alone
        // would happily describe a download that never finished.
        staging.apkFile().writeBytes(ByteArray(artifact.size.toInt() - 3))

        assertNull(staging.completeFor(artifact))
    }

    @Test
    fun `a staged file from another host commit is not offered`() {
        val staging = staging()
        staging.record(verified)
        staging.apkFile().writeBytes(ByteArray(artifact.size.toInt()))

        assertNull(staging.completeFor(artifact.copy(commit = "0ther17", sha256 = "999zzz")))
    }

    @Test
    fun `a corrupt sidecar reads as absent rather than throwing`() {
        val staging = staging()
        staging.record(verified)
        staging.apkFile().writeBytes(ByteArray(artifact.size.toInt()))
        File(temp.root, "update/staged.json").writeText("{ not json")

        assertNull(staging.identity())
        assertNull(staging.completeFor(artifact))
    }

    @Test
    fun `discard removes the bytes and the sidecar together`() {
        val staging = staging()
        staging.record(identity)
        staging.apkFile().writeBytes(ByteArray(artifact.size.toInt()))

        staging.discard()

        assertNull(staging.identity())
        assertEquals(0L, staging.partialBytes())
        assertFalse(File(temp.root, "update/staged.json").exists())
    }
}
