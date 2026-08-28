package dev.scoutr.app.update

import dev.scoutr.app.data.ApkArtifact
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Identity of the APK currently staged on disk, persisted in a sidecar next to
 * the bytes it describes.
 */
@Serializable
data class StagedIdentity(
    val commit: String,
    val sha256: String,
    val size: Long,
    val version: String,
    /**
     * The versionCode gradle stamped into the APK, straight from the host's
     * artifact descriptor.
     *
     * An APK older than what is already installed cannot be committed — the
     * system rejects the downgrade — so a staged build that is not newer than
     * the running app must never be offered for install. 0 means the sidecar
     * predates this field and the version is simply unknown; such a stage is
     * also not offered, because offering it is how a stale stage wedges the
     * whole update flow.
     */
    val versionCode: Int = 0,
    /**
     * True only once the downloaded bytes were hashed and matched [sha256].
     *
     * Length alone cannot stand in for this: a process killed between the last
     * byte and the checksum leaves a full-length file that was never verified,
     * and offering that to PackageInstaller is exactly the thing the update
     * flow must never do.
     */
    val verified: Boolean = false,
)

/** A fully downloaded and verified APK, ready for the installer. */
data class StagedUpdate(val apk: File, val identity: StagedIdentity)

/**
 * Owns the staging directory: the APK bytes, the sidecar identity, and the
 * rules for when a partial or complete file may be reused.
 *
 * Staging lives in filesDir rather than cacheDir because these bytes can cost
 * many minutes of host build plus download over a slow link, and the system is
 * free to evict a cache at any time.
 *
 * Deliberately free of Android types so the rules are unit-testable against a
 * TemporaryFolder.
 */
class UpdateStaging(private val dir: File) {

    /** The staged APK, which may be absent, partial, or complete. */
    fun apkFile(): File {
        dir.mkdirs()
        return File(dir, APK_NAME)
    }

    /**
     * The recorded identity, or null when it is absent or unreadable. A sidecar
     * that fails to parse is treated as absent rather than as an error: the
     * only cost is re-downloading, and a half-written sidecar must never be
     * mistaken for a description of the bytes on disk.
     */
    fun identity(): StagedIdentity? {
        val file = File(dir, SIDECAR_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(StagedIdentity.serializer(), file.readText()) }.getOrNull()
    }

    /**
     * Records what the staged bytes are meant to become. Written before the
     * first byte so an interrupted download is still identifiable, which is
     * what makes a resume decision possible at all.
     */
    fun record(identity: StagedIdentity) {
        dir.mkdirs()
        File(dir, SIDECAR_NAME).writeText(json.encodeToString(StagedIdentity.serializer(), identity))
    }

    /** Promotes the staged file to installable, after its hash was checked. */
    fun markVerified() {
        val identity = identity() ?: return
        record(identity.copy(verified = true))
    }

    /** Bytes already on disk; 0 when nothing is staged. */
    fun partialBytes(): Long {
        val file = File(dir, APK_NAME)
        return if (file.isFile) file.length() else 0L
    }

    /** Drops both the bytes and the sidecar. */
    fun discard() {
        File(dir, APK_NAME).delete()
        File(dir, SIDECAR_NAME).delete()
    }

    /**
     * The staged file when it is complete *and* was verified, otherwise null.
     *
     * The sidecar is never trusted alone — the on-disk length must match too,
     * because the sidecar is written *before* the bytes it describes, so its
     * size claim would otherwise happily describe a download that never
     * finished. [StagedIdentity.verified] is what separates "all the bytes
     * arrived" from "the bytes are the ones the host promised".
     */
    fun complete(): StagedUpdate? {
        val identity = identity() ?: return null
        if (!identity.verified) return null
        val apk = File(dir, APK_NAME)
        if (!apk.isFile || apk.length() != identity.size) return null
        return StagedUpdate(apk, identity)
    }

    /** [complete], narrowed to the APK the host is offering right now. */
    fun completeFor(artifact: ApkArtifact): StagedUpdate? {
        val staged = complete() ?: return null
        if (staged.identity.commit != artifact.commit) return null
        if (!staged.identity.sha256.equals(artifact.sha256, ignoreCase = true)) return null
        if (staged.identity.size != artifact.size) return null
        return staged
    }

    private companion object {
        const val APK_NAME = "staged.apk"
        const val SIDECAR_NAME = "staged.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
