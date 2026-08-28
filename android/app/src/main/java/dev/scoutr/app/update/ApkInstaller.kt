package dev.scoutr.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Installs a downloaded APK on-device through Android's [PackageInstaller].
 *
 * This is the half of the update flow that replaces `adb install -r`: the
 * bridge only ever hands over bytes (see [AppUpdater]), and the phone commits
 * them itself, so nothing has to be paired over adb. Android always shows its
 * own confirmation sheet before a self-update lands — silent install needs
 * device-owner privileges the app does not have — which is why the flow ends
 * in a system prompt rather than finishing on its own.
 *
 * The downloaded APK must carry the same signature as the installed app (the
 * host's debug keystore). Building on a different machine yields a different
 * debug key and the commit fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
 */
object ApkInstaller {
    private const val SESSION_WRITE_NAME = "scoutr-update"

    private val _outcome = MutableStateFlow<ApkInstallOutcome?>(null)

    /**
     * The last result reported by [ApkInstallReceiver]. A successful self-update
     * usually never reaches a collector — Android tears the old process down as
     * it swaps the package — so treat a missing Success as normal and only act
     * on [ApkInstallOutcome.Failed].
     */
    val outcome: StateFlow<ApkInstallOutcome?> = _outcome.asStateFlow()

    /** False until the user grants "install unknown apps" for Scoutr. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The per-app "install unknown apps" screen, for when [canInstall] is false. */
    fun unknownSourcesSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** An [ApkInstall] bound to [context], for [AppUpdateController] to commit through. */
    fun forContext(context: Context): ApkInstall {
        val appContext = context.applicationContext
        return ApkInstall { apk -> commit(appContext, apk) }
    }

    fun report(outcome: ApkInstallOutcome) {
        _outcome.value = outcome
    }

    fun clearOutcome() {
        _outcome.value = null
    }

    private suspend fun commit(context: Context, apk: File): Unit = withContext(Dispatchers.IO) {
        if (!canInstall(context)) throw IOException("Scoutr is not allowed to install apps yet")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            // The length must be declared up front or the session stays open
            // waiting for bytes that never come.
            session.openWrite(SESSION_WRITE_NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            session.commit(statusIntent(context, sessionId).intentSender)
        }
    }

    /**
     * Mutable on 31+: the system fills the confirmation screen into
     * `Intent.EXTRA_INTENT` on the pending intent it sends back.
     */
    private fun statusIntent(context: Context, sessionId: Int): PendingIntent {
        val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, ApkInstallReceiver::class.java).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
    }
}

/** What the system reported once the install session finished. */
sealed interface ApkInstallOutcome {
    data object Success : ApkInstallOutcome
    data class Failed(val message: String) : ApkInstallOutcome
}
