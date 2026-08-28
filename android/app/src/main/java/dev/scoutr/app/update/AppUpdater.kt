package dev.scoutr.app.update

import dev.scoutr.app.data.ApkArtifact
import dev.scoutr.app.data.ApkBuild
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Produces one verified, staged APK: ask the host to build, wait for it,
 * download the bytes over the exposed bridge API, and check them against the
 * host's hash.
 *
 * No adb anywhere — that is the point. The bridge cannot reach the phone at
 * all in this direction, so every step is a request the phone makes.
 *
 * Committing the result is deliberately *not* here. The install prompt is a
 * system Activity that Android suppresses unless the app is foreground, so who
 * commits and when is a lifecycle decision owned by [AppUpdateController]; this
 * class only has to produce trustworthy bytes.
 *
 * Deliberately free of Android types so the whole sequence is unit-testable
 * against FakeScoutrApi.
 */
class AppUpdater(
    private val api: ScoutrApi,
    private val staging: UpdateStaging,
    private val pollDelayMs: Long = 2_000,
    /** A wedged gradle must not leave the phone polling forever. */
    private val buildTimeoutMs: Long = 10 * 60 * 1_000,
) {
    /** Build, poll, download, verify. Never installs. */
    suspend fun stage(onProgress: (UpdateProgress) -> Unit): StagedUpdate {
        onProgress(UpdateProgress.Building)
        api.updateBuild()
        val build = awaitBuild()
        val artifact = build.apk ?: throw IOException("the host finished building but reported no APK")

        val apk = staging.apkFile()
        val resumeFrom = resumableBytes(artifact)
        if (resumeFrom == 0L) staging.discard()
        // Recorded before the first byte, so a download interrupted anywhere —
        // including by process death — still says what it was trying to be.
        staging.record(
            StagedIdentity(
                commit = artifact.commit,
                sha256 = artifact.sha256,
                size = artifact.size,
                version = artifact.version,
            ),
        )

        onProgress(UpdateProgress.Downloading(resumeFrom, artifact.size))
        api.downloadApk(apk, resumeFrom) { written, total ->
            onProgress(UpdateProgress.Downloading(written, if (total > 0) total else artifact.size))
        }

        val digest = sha256(apk)
        if (!digest.equals(artifact.sha256, ignoreCase = true)) {
            // Discarded rather than kept: a corrupt staged file must never be
            // resumable, or every later retry would inherit the same bad bytes.
            staging.discard()
            throw IOException("the downloaded APK did not match the host's checksum")
        }
        // Only now may the file be called installable. Until this line a later
        // process must treat a full-length staged file as unproven.
        staging.markVerified()

        return staging.completeFor(artifact)
            ?: throw IOException("the downloaded APK did not match the host's build")
    }

    /**
     * How many staged bytes may be appended to, or 0 to start over.
     *
     * Validity is decided by comparing the recorded hash against the artifact
     * the host is offering right now, rather than by an If-Range/ETag exchange:
     * there is exactly one client and it already polls this descriptor, so the
     * rule stays testable and the bridge keeps a single response path.
     */
    private fun resumableBytes(artifact: ApkArtifact): Long {
        val recorded = staging.identity() ?: return 0L
        if (!recorded.sha256.equals(artifact.sha256, ignoreCase = true)) return 0L
        val partial = staging.partialBytes()
        return if (partial in 1 until artifact.size) partial else 0L
    }

    /** Polls until the started build leaves "building", or the timeout expires. */
    private suspend fun awaitBuild(): ApkBuild {
        var waited = 0L
        while (true) {
            val build = api.updateApkStatus().build
            when (build.state) {
                "ready" -> return build
                "failed" -> throw IOException(build.error ?: "the host build failed")
                // "idle" after a start means the build vanished without a
                // result, which only happens if the bridge restarted mid-build.
                "idle" -> throw IOException("the host build was interrupted")
            }
            if (waited >= buildTimeoutMs) {
                throw IOException("the host build did not finish within ${buildTimeoutMs / 60_000} minutes")
            }
            delay(pollDelayMs)
            waited += pollDelayMs
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** What the update surfaces show while the pipeline is in flight. */
sealed interface UpdateProgress {
    data object Building : UpdateProgress
    data class Downloading(val bytes: Long, val total: Long) : UpdateProgress
}
