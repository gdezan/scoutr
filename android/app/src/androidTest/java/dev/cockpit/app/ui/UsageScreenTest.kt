package dev.cockpit.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.cockpit.app.data.UsageSnapshot
import dev.cockpit.app.data.UsageWindow
import dev.cockpit.app.state.FailureKind
import dev.cockpit.app.state.Loadable
import dev.cockpit.app.state.UsageUiState
import dev.cockpit.app.ui.screens.UsageContent
import dev.cockpit.app.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.FileOutputStream
class UsageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String) {
        val file = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null)!!.resolve("$name.png")
        FileOutputStream(file).use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        println("USAGE_SCREENSHOT=${file.absolutePath}")
    }

    @Test
    fun showsQuotaBarsAndBalanceWithoutInventingABalanceLimit() {
        compose.setContent {
            CockpitTheme {
                UsageContent(
                    ui = UsageUiState(
                        providers = Loadable.Ready(
                            listOf(
                                UsageSnapshot(
                                provider = "openai-codex",
                                label = "Codex",
                                windows = listOf(
                                    UsageWindow(label = "5h", usedPercent = 82.0, amount = 42.0, limitAmount = 100.0, currency = "USD"),
                                    UsageWindow(label = "7d", usedPercent = 34.0, limitAmount = 200.0, currency = "USD"),
                                ),
                            ),
                            UsageSnapshot(
                                provider = "deepseek",
                                label = "DeepSeek",
                                windows = listOf(UsageWindow(label = "USD", amount = -0.01, currency = "USD")),
                                error = "Balance delayed",
                            ),
                            UsageSnapshot(
                                provider = "anthropic",
                                label = "Anthropic",
                                windows = listOf(UsageWindow(label = "USD", amount = 0.0, currency = "USD")),
                            ),
                            UsageSnapshot(
                                provider = "google",
                                label = "Google",
                                windows = listOf(UsageWindow(label = "USD", amount = 12.5, currency = "USD")),
                            ),
                        ),
                    ),
                ),
                onRefresh = {},
                nowMillis = 0,
            )
            }
        }

        compose.onNodeWithTag("usage_bar_openai-codex_5h").assertIsDisplayed()
        compose.onNodeWithText("5-hour limit").assertIsDisplayed()
        compose.onNodeWithText("82% used").assertIsDisplayed()
        compose.onNodeWithContentDescription("5-hour limit, 82% used, $42.00 of $100.00").assertIsDisplayed()
        compose.onNodeWithContentDescription("7-day limit, 34% used, Limit $200.00").assertIsDisplayed()
        compose.onNodeWithText("Showing last known usage. Balance delayed").assertIsDisplayed()
        compose.onNodeWithTag("usage_deepseek").assertIsDisplayed()
        compose.onNodeWithText("Balance below zero").assertIsDisplayed()
        compose.onNodeWithText("Add credit before starting more work.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Balance below zero, USD, negative $0.01").assertIsDisplayed()
        compose.onNodeWithTag("usage_anthropic").assertIsDisplayed()
        compose.onNodeWithTag("usage_google").assertIsDisplayed()
        compose.onNodeWithTag("usage_anthropic").assertIsDisplayed()
        capture("usage-balances")
    }

    @Test
    fun exposesStableLoadingAndActionableErrorStates() {
        var retries = 0
        var ui by mutableStateOf(UsageUiState(providers = Loadable.Loading))
        compose.setContent {
            CockpitTheme {
                UsageContent(
                    ui = ui,
                    onRefresh = { retries += 1 },
                    nowMillis = 0,
                )
            }
        }

        compose.onNodeWithTag("usage_loading").assertIsDisplayed()
        compose.runOnIdle { ui = UsageUiState(providers = Loadable.Failed("Usage service is offline", FailureKind.Server)) }
        compose.onNodeWithTag("usage_error").assertIsDisplayed()
        compose.onNodeWithText("Usage service is offline").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            ui = UsageUiState()
        }
        compose.onNodeWithTag("usage_empty").assertIsDisplayed()
        compose.onNodeWithText("Refresh").performClick()
        compose.runOnIdle { assertEquals(2, retries) }
    }

    @Test
    fun updatesCountdownAndKeepsCachedDataAlongsideErrors() {
        val resetAt = 1_000_120L
        var nowMillis by mutableStateOf(1_000_000_000L)
        var ui by mutableStateOf(
            UsageUiState(
                providers = Loadable.Ready(
                    listOf(
                        UsageSnapshot(
                        provider = "codex",
                        label = "Codex",
                        windows = listOf(UsageWindow(label = "5h", usedPercent = 40.0, resetAt = resetAt)),
                    ),
                    ),
                ),
            ),
        )
        compose.setContent {
            CockpitTheme {
                UsageContent(ui = ui, onRefresh = {}, nowMillis = nowMillis)
            }
        }

        compose.onNodeWithText("Resets in 2m").assertIsDisplayed()
        compose.runOnIdle { nowMillis += 90_000 }
        compose.onNodeWithText("Resets now").assertIsDisplayed()
        compose.runOnIdle { ui = ui.copy(error = "Showing cached usage") }
        compose.onNodeWithTag("usage_codex").assertIsDisplayed()
        compose.onNodeWithText("Showing cached usage").assertIsDisplayed()
    }
}
