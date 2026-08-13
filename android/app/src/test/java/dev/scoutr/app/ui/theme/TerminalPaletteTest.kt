package dev.scoutr.app.ui.theme

import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies [TerminalPalette.install] lands in the vendored emulator's default scheme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalPaletteTest {

    @Test
    fun installWritesGdezanMaterialScheme() {
        TerminalPalette.install()

        val scheme = TerminalColors.COLOR_SCHEME
        assertEquals(0xFF1A1A1F.toInt(), scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(0xFFC7D6D6.toInt(), scheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND])
        assertEquals(0xFFFFCC00.toInt(), scheme.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR])

        // ANSI color0..15: bright red..cyan repeat the normal hues.
        val ansi = intArrayOf(
            0xFF000000.toInt(), 0xFFF07178.toInt(), 0xFFC3E88D.toInt(), 0xFFFFCB6B.toInt(),
            0xFF82AAFF.toInt(), 0xFFC792EA.toInt(), 0xFF89DDFF.toInt(), 0xFFFFFFFF.toInt(),
            0xFF4A4A4A.toInt(), 0xFFF07178.toInt(), 0xFFC3E88D.toInt(), 0xFFFFCB6B.toInt(),
            0xFF82AAFF.toInt(), 0xFFC792EA.toInt(), 0xFF89DDFF.toInt(), 0xFFFFFFFF.toInt(),
        )
        ansi.forEachIndexed { index, expected ->
            assertEquals("color$index", expected, scheme.mDefaultColors[index])
        }
    }
}
