package dev.cockpit.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.termux.terminal.TerminalColors
import java.util.Properties
import kotlin.math.roundToInt

/**
 * Terminal palette, mirroring the gdezan-material terminal family
 * (src/palette.json `terminal` + targets/terminals in ~/Dev/gdezan-material):
 * the editor background/foreground pair, the 16 ANSI colors (bright
 * red..cyan repeat the normal hues, per the Material Darker High Contrast
 * source), and the yellow block cursor.
 *
 * The vendored termux emulator draws its scheme from
 * [TerminalColors.COLOR_SCHEME]; [install] pushes these tokens through that
 * scheme's own Properties seam, so no vendored file is modified. Emulators
 * copy the scheme when created, so one install at process start covers every
 * session and generation reset.
 *
 * The vendored renderer paints block-cursor text inverted and selections in
 * reverse video, which realizes the theme's cursor-text (#1A1A1F) and
 * selection pair (#303034 background / #C7D6D6 foreground) without explicit
 * tokens.
 */
object TerminalPalette {

    val Background = Color(0xFF1A1A1F)
    val Foreground = Color(0xFFC7D6D6)
    val Cursor = Color(0xFFFFCC00)

    val Black = Color(0xFF000000)
    val Red = Color(0xFFF07178)
    val Green = Color(0xFFC3E88D)
    val Yellow = Color(0xFFFFCB6B)
    val Blue = Color(0xFF82AAFF)
    val Magenta = Color(0xFFC792EA)
    val Cyan = Color(0xFF89DDFF)
    val White = Color(0xFFFFFFFF)
    val BrightBlack = Color(0xFF4A4A4A)

    /** ANSI color0..color15 in terminal order (9..14 repeat 1..6). */
    private val ansi = listOf(
        Black, Red, Green, Yellow, Blue, Magenta, Cyan, White,
        BrightBlack, Red, Green, Yellow, Blue, Magenta, Cyan, White,
    )

    /**
     * Install this palette as the emulator's default scheme. Call once at
     * process start, before any terminal session exists.
     */
    fun install() {
        val props = Properties().apply {
            for ((index, color) in ansi.withIndex()) setProperty("color$index", color.toHex())
            setProperty("foreground", Foreground.toHex())
            setProperty("background", Background.toHex())
            setProperty("cursor", Cursor.toHex())
        }
        TerminalColors.COLOR_SCHEME.updateWith(props)
    }

    // Compose packs its color value in the high bits, so read channels instead
    // of the raw value when emitting hex.
    private fun Color.toHex(): String = "#%02x%02x%02x".format(
        (red * 255f).roundToInt(),
        (green * 255f).roundToInt(),
        (blue * 255f).roundToInt(),
    )
}
