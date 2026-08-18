package dev.scoutr.app.update

import dev.scoutr.app.data.ApkBuild
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Drives one self-update end to end: ask the host to build an APK, wait for it,
 * download the bytes over the exposed bridge API, check them against the host's hash, and
 * hand them to the on-device installer.
 *
 * No adb anywhere — that is the point. The bridge cannot reach the phone at
 * all in this direction, so every step is a request the phone makes.
 *
 * Deliberately free of Android types (the installer is the [ApkInstall] seam)
 * so the whole sequence is unit-testable against FakeScoutrApi.
 */
class AppUpdater(
    private val api: ScoutrApi,
    private val installer: ApkInstall,
    /** Where the downloaded APK is staged; app-private cache is enough. */
    private val cacheDir: File,
    private val pollDelayMs: Long = 2_000,
    /** A wedged gradle must not leave the phone polling forever. */
    private val buildTimeoutMs: Long = 10 * 60 * 1_000,
) {
    suspend fun run(onProgress: (UpdateProgress) -> Unit) {
        onProgress(UpdateProgress.Building)
        api.updateBuild()
        val build = awaitBuild()
        val artifact = build.apk ?: throw IOException("the host finished building but reported no APK")

        val apk = stagingFile()
        onProgress(UpdateProgress.Downloading(0, artifact.size))
        api.downloadApk(apk) { written, total ->
            onProgress(UpdateProgress.Downloading(written, if (total > 0) total else artifact.size))
        }

        val digest = sha256(apk)
        if (!digest.equals(artifact.sha256, ignoreCase = true)) {
            apk.delete()
            throw IOException("the downloaded APK did not match the host's checksum")
        }

        onProgress(UpdateProgress.Installing)
        installer.install(apk)
        // The session has its own copy of the bytes by now, so the staged file
        // is dead weight — and it is tens of megabytes of it.
        apk.delete()
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

    private fun stagingFile(): File {
        val dir = File(cacheDir, "update")
        dir.mkdirs()
        return File(dir, "scoutr-update.apk")
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

/** Commits an APK to the device. [ApkInstaller.forContext] is the real one. */
fun interface ApkInstall {
    suspend fun install(apk: File)
}

/** What the Settings row shows while an update is in flight. */
sealed interface UpdateProgress {
    data object Building : UpdateProgress
    data class Downloading(val bytes: Long, val total: Long) : UpdateProgress
    data object Installing : UpdateProgress
}
