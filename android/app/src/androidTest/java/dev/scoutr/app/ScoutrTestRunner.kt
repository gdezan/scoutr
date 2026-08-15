package dev.scoutr.app

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.runner.AndroidJUnitRunner

/**
 * Instrumentation runner that temporarily zeroes the three animation scales
 * before tests. Espresso refuses to act on a view while animations or
 * transitions are live ("Animations or transitions are enabled on the target
 * device"), which made the managed-device suite fail on freshly booted devices.
 *
 * The original scales are restored when instrumentation finishes so a test run
 * cannot leave the user's emulator or device in reduced-motion mode.
 * Test-harness only: it does not alter product code.
 */
class ScoutrTestRunner : AndroidJUnitRunner() {

    private val animationScaleKeys = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    )
    private val originalAnimationScales = mutableMapOf<String, String>()

    override fun onStart() {
        originalAnimationScales.clear()
        animationScaleKeys.forEach { key ->
            originalAnimationScales[key] = shellOutput("settings get global $key")
        }
        animationScaleKeys.forEach { key -> shell("settings put global $key 0.0") }
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        restoreAnimationScales()
        super.finish(resultCode, results)
    }

    override fun onDestroy() {
        restoreAnimationScales()
        super.onDestroy()
    }

    private fun restoreAnimationScales() {
        originalAnimationScales.forEach { (key, value) ->
            if (value == "null" || value.isBlank()) {
                shell("settings delete global $key")
            } else {
                shell("settings put global $key $value")
            }
        }
        originalAnimationScales.clear()
    }

    /** Runs as the shell uid, so WRITE_SECURE_SETTINGS is granted. */
    private fun shell(command: String) {
        shellOutput(command)
    }

    /** Runs a shell command and drains its output before the next command. */
    private fun shellOutput(command: String): String =
        uiAutomation.executeShellCommand(command).use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use {
                it.readBytes().toString(Charsets.UTF_8).trim()
            }
        }
}
