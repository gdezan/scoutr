package dev.scoutr.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scoutr.app.R
import dev.scoutr.app.ui.motion.LocalReduceMotion

/** UI typography: Space Grotesk keeps operational surfaces compact but human. */
val ScoutrUiFont = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold),
)

/** Machine facts only: paths, commands, hashes, code, and provider identifiers. */
val ScoutrMono = FontFamily(
    Font(R.font.martian_mono, FontWeight.Normal),
    Font(R.font.martian_mono, FontWeight.Medium),
    Font(R.font.martian_mono, FontWeight.SemiBold),
    Font(R.font.martian_mono, FontWeight.Bold),
)

/** Terminal-only mono: JetBrains Mono is narrower at dense grid sizes. */
val ScoutrTerminalMono = FontFamily(Font(R.font.jetbrains_mono))

private val ScoutrTypography = Typography(
    displayLarge = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = ScoutrUiFont, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = ScoutrUiFont, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = ScoutrUiFont, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = ScoutrUiFont, fontWeight = FontWeight.Medium),
)

private val ScoutrShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

/**
 * Scoutr visual world — always-dark Operate mode.
 *
 * Green means live or AI-owned, gray means settled, and red means the user is
 * needed. Machine facts use [ScoutrMono]; labels never use mono as decoration.
 * Surfaces are tonal rather than shadowed so the hierarchy survives dim-room
 * and OLED use without glow or gradients.
 */

/** Reserved ink for launcher, splash, empty-state, widget, and notification brand surfaces. */
val BrandInk = Color(0xFF141619)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DF08D),
    onPrimary = Color(0xFF04241A),
    primaryContainer = Color(0xFF12301A),
    onPrimaryContainer = Color(0xFFA6EFAD),
    secondary = Color(0xFF2C6F72),
    onSecondary = Color(0xFFE0F7F7),
    secondaryContainer = Color(0xFF17383A),
    onSecondaryContainer = Color(0xFFB9E8E9),
    tertiary = Color(0xFFE8B84B),
    onTertiary = Color(0xFF261A00),
    tertiaryContainer = Color(0xFF3B2900),
    onTertiaryContainer = Color(0xFFFFDEA1),
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFECEDF0),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFECEDF0),
    surfaceVariant = Color(0xFF1B1D21),
    onSurfaceVariant = Color(0xFFA8AEB9),
    outline = Color(0xFF363B43),
    outlineVariant = Color(0xFF26292E),
    error = Color(0xFFE5484D),
    onError = Color(0xFF3A0B0C),
    errorContainer = Color(0xFF3A1719),
    onErrorContainer = Color(0xFFFFDAD9),
    surfaceContainerLowest = Color(0xFF08090B),
    surfaceContainerLow = Color(0xFF101114),
    surfaceContainer = Color(0xFF16171B),
    surfaceContainerHigh = Color(0xFF16171B),
    surfaceContainerHighest = Color(0xFF1A1C20),
)


@Composable
fun ScoutrTheme(
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(
            colorScheme = DarkColors,
            typography = ScoutrTypography,
            shapes = ScoutrShapes,
            content = content,
        )
    }
}
