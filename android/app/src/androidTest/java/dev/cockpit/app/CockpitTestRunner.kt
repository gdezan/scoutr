package dev.cockpit.app

import android.os.ParcelFileDescriptor
import androidx.test.runner.AndroidJUnitRunner

/**
 * Instrumentation runner that zeroes the three animation scales before any test
 * runs. Espresso refuses to act on a view while animations or transitions are
 * live ("Animations or transitions are enabled on the target device"), which
 * made the managed-device suite fail on freshly booted Gradle Managed Devices —
 * they come up with the platform defaults, unlike a hand-tuned local emulator.
 *
 * Test-harness only: no product code reads these settings.
 */
class CockpitTestRunner : AndroidJUnitRunner() {

    override fun onStart() {
        listOf(
            "window_animation_scale",
            "transition_animation_scale",
            "animator_duration_scale",
        ).forEach { key -> shell("settings put global $key 0.0") }
        super.onStart()
    }

    /** Runs as the shell uid, so WRITE_SECURE_SETTINGS is granted. Drains the
     * output so the command has finished before the next one starts. */
    private fun shell(command: String) {
        uiAutomation.executeShellCommand(command).use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }
}
