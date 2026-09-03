package dev.scoutr.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenContrastTest {
    @Test
    fun criticalCaptionIsAaOnCardAndSelectedCard() {
        val caption = ScoutrComponentTokens.criticalCaption

        assertTrue(contrastRatio(caption, ScoutrSemantic.surfaceCard) >= 4.5)
        assertTrue(contrastRatio(caption, ScoutrSemantic.surfaceSelected) >= 4.5)
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val high = maxOf(relativeLuminance(first), relativeLuminance(second))
        val low = minOf(relativeLuminance(first), relativeLuminance(second))
        return (high + 0.05) / (low + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        val argb = color.toArgb()
        val red = ((argb shr 16) and 0xFF) / 255.0
        val green = ((argb shr 8) and 0xFF) / 255.0
        val blue = (argb and 0xFF) / 255.0
        return 0.2126 * linearize(red) + 0.7152 * linearize(green) + 0.0722 * linearize(blue)
    }

    private fun linearize(channel: Double): Double =
        if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
}
