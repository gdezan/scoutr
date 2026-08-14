package dev.scoutr.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Macrobenchmark smoke tests for cold startup and the primary app surfaces. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = APP_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait() },
    )

    @Test
    fun boardOpening() = benchmarkRule.measureRepeated(
        packageName = APP_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            seedConnection()
        },
        measureBlock = {
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.text("Board")), UI_TIMEOUT_MS))
            device.findObject(By.text("Sessions")).click()
            check(device.wait(Until.hasObject(By.text("Sessions")), UI_TIMEOUT_MS))
            device.findObject(By.text("Board")).click()
            check(device.wait(Until.hasObject(By.text("Board")), UI_TIMEOUT_MS))
        },
    )

    @Test
    fun chatOpening() = benchmarkRule.measureRepeated(
        packageName = APP_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            seedConnection()
        },
        measureBlock = {
            startActivityAndWait()
            device.executeShellCommand(
                "am start -W -a android.intent.action.VIEW -d scoutr://chat/benchmark-pane $APP_PACKAGE",
            )
            check(device.wait(Until.hasObject(By.text("benchmark-pane")), UI_TIMEOUT_MS))
        },
    )

    private companion object {
        const val APP_PACKAGE = "dev.scoutr.app"
        const val ITERATIONS = 5
        const val UI_TIMEOUT_MS = 5_000L
        const val SETUP_COMPONENT =
            "dev.scoutr.app/dev.scoutr.app.benchmark.BenchmarkSetupActivity"

        private fun MacrobenchmarkScope.seedConnection() {
            val result = device.executeShellCommand("am start -W -n $SETUP_COMPONENT")
            check("Status: ok" in result) { "benchmark setup failed: $result" }
        }
    }
}
