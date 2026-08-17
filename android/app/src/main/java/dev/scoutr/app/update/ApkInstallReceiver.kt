package dev.scoutr.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Receives the install session's status callbacks.
 *
 * The first callback is almost always STATUS_PENDING_USER_ACTION: Android hands
 * back the confirmation screen it wants shown, and the install only proceeds
 * once the user accepts. Everything else is terminal and lands on
 * [ApkInstaller.outcome] for Settings to surface.
 */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationIntent(intent)
                if (confirm == null) {
                    ApkInstaller.report(ApkInstallOutcome.Failed("the installer sent no confirmation screen"))
                } else {
                    // Started from a receiver, so it needs its own task.
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstaller.report(ApkInstallOutcome.Success)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                ApkInstaller.report(ApkInstallOutcome.Failed("Install cancelled"))
            else -> ApkInstaller.report(ApkInstallOutcome.Failed(reason(status, intent)))
        }
    }

    /**
     * The system's own explanation when it has one; otherwise the raw status,
     * which is still more actionable than a bare "install failed".
     */
    private fun reason(status: Int, intent: Intent): String {
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }
        return message ?: "Install failed (status $status)"
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
