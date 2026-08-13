package dev.cockpit.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier

/**
 * Pads by the larger of the IME and the navigation bar.
 *
 * Stacking imePadding() on navigationBarsPadding() — or on a Scaffold that
 * already consumed the nav bar — leaves a nav-bar-tall gap above the keyboard.
 */
fun Modifier.imeOrNavigationBarsPadding(): Modifier =
    windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
