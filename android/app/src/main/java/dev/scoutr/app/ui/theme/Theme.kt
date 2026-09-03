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
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scoutr.app.R
import dev.scoutr.app.ui.motion.LocalReduceMotion

/**
 * All three bundled faces are variable fonts, and every weight resolves to the
 * same file. Without an explicit `wght` axis value each registration renders at
 * the file's default instance — Space Grotesk defaults to 300, so an unadorned
 * `FontWeight.Bold` would silently draw Light. Pin the axis per weight.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** UI typography: Space Grotesk keeps operational surfaces compact but human. */
val ScoutrUiFont = FontFamily(
    variableFont(R.font.space_grotesk, FontWeight.Normal),
    variableFont(R.font.space_grotesk, FontWeight.Medium),
    variableFont(R.font.space_grotesk, FontWeight.SemiBold),
    variableFont(R.font.space_grotesk, FontWeight.Bold),
)

/** Machine facts only: paths, commands, hashes, code, and provider identifiers. */
val ScoutrMono = FontFamily(
    variableFont(R.font.martian_mono, FontWeight.Normal),
    variableFont(R.font.martian_mono, FontWeight.Medium),
    variableFont(R.font.martian_mono, FontWeight.SemiBold),
    variableFont(R.font.martian_mono, FontWeight.Bold),
)

/** Terminal-only mono: JetBrains Mono is narrower at dense grid sizes. */
val ScoutrTerminalMono = FontFamily(
    variableFont(R.font.jetbrains_mono, FontWeight.Normal),
    variableFont(R.font.jetbrains_mono, FontWeight.Medium),
    variableFont(R.font.jetbrains_mono, FontWeight.Bold),
)

private fun ui(
    size: Double,
    weight: FontWeight,
    lineHeight: Double,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = ScoutrUiFont,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
)

/**
 * The locked scale from `docs/design/Scoutr Design System.dc.html` §9a. Sizes are
 * declared here rather than inherited from Material so the whole app reads at one
 * compact scale; M3's defaults are a third larger and carry positive tracking the
 * system does not want.
 */
private val ScoutrTypography = Typography(
    // Brand wordmark — splash only.
    displayLarge = ui(30.0, FontWeight.Bold, 34.0, -1.2),
    displayMedium = ui(26.0, FontWeight.Bold, 30.0, -0.8),
    displaySmall = ui(24.0, FontWeight.Bold, 28.0, -0.6),
    headlineLarge = ui(21.0, FontWeight.Bold, 26.0, -0.5),
    headlineMedium = ui(19.0, FontWeight.Bold, 24.0, -0.4),
    headlineSmall = ui(17.0, FontWeight.SemiBold, 22.0, -0.2),
    // Screen title: "Board", "Sessions", "Usage", "Review".
    titleLarge = ui(21.0, FontWeight.Bold, 26.0, -0.5),
    // Agent/session tile title, and the chat header's session name.
    titleMedium = ui(15.0, FontWeight.SemiBold, 20.0),
    titleSmall = ui(13.5, FontWeight.SemiBold, 19.0),
    // Transcript prose.
    bodyLarge = ui(15.0, FontWeight.Normal, 23.0),
    bodyMedium = ui(13.5, FontWeight.Normal, 19.0),
    // Card subtitle and metadata.
    bodySmall = ui(12.5, FontWeight.Normal, 18.0),
    labelLarge = ui(13.0, FontWeight.SemiBold, 18.0),
    labelMedium = ui(11.5, FontWeight.SemiBold, 16.0),
    labelSmall = ui(10.0, FontWeight.SemiBold, 14.0),
)

/** Letter-spacing values, in sp, shared by display and machine-text slots. */
object ScoutrTracking {
    const val tight = -0.6
    const val section = 0.08
    const val caption = 0.02
}

/**
 * Mono slots that Material has no equivalent for. Martian Mono runs ~25% wide, so
 * the system caps it at 10sp and relies on shortened paths rather than shrinking
 * further.
 */
object ScoutrType {
    /** Mono-caps section header: `WORKING`, `TODAY`, `TOKENS THIS WEEK`. */
    val monoSection = TextStyle(
        fontFamily = ScoutrMono,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 14.sp,
        letterSpacing = ScoutrTracking.section.sp,
    )

    /** Machine metadata: `~/scoutr · gpt-5.2`, `142 msgs · 61k tok`. */
    val monoMeta = TextStyle(
        fontFamily = ScoutrMono,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
    )

    /** Compact machine caption for reset, burn-rate, stale, and offline facts. */
    val monoCaption = TextStyle(
        fontFamily = ScoutrMono,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 13.sp,
        letterSpacing = ScoutrTracking.caption.sp,
    )

    /** Large human-readable empty-state or detail-placeholder heading. */
    val displayEmpty = TextStyle(
        fontFamily = ScoutrUiFont,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 30.sp,
        letterSpacing = ScoutrTracking.tight.sp,
    )

    /** Adjustable machine text: review lines, Markdown code, and tool output. */
    fun monoCode(fontSizeSp: Float) = monoMeta.copy(
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp + 4f).sp,
    )

    /** An emphasised machine fact: relative time, diff counts, tool names. */
    val monoFact = TextStyle(
        fontFamily = ScoutrMono,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 15.sp,
    )

    /** Tool-call line on the chat spine — the one mono slot allowed at 10sp. */
    val monoTool = TextStyle(
        fontFamily = ScoutrMono,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 15.sp,
    )
}

private val ScoutrShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(ScoutrRadii.sm),
    medium = RoundedCornerShape(ScoutrRadii.md),
    large = RoundedCornerShape(ScoutrRadii.lg),
    extraLarge = RoundedCornerShape(ScoutrRadii.xl),
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
    primary = ScoutrSemantic.live.color,
    onPrimary = ScoutrSemantic.live.on,
    primaryContainer = ScoutrSemantic.live.container,
    onPrimaryContainer = ScoutrSemantic.live.onContainer,
    secondary = ScoutrSemantic.data.color,
    onSecondary = ScoutrSemantic.data.on,
    secondaryContainer = ScoutrSemantic.data.container,
    onSecondaryContainer = ScoutrSemantic.data.onContainer,
    tertiary = ScoutrSemantic.warning.color,
    onTertiary = ScoutrSemantic.warning.on,
    tertiaryContainer = ScoutrSemantic.warning.container,
    onTertiaryContainer = ScoutrSemantic.warning.onContainer,
    background = ScoutrPrimitive.neutral10,
    onBackground = Color(0xFFECEDF0),
    surface = ScoutrPrimitive.neutral20,
    onSurface = Color(0xFFECEDF0),
    surfaceVariant = Color(0xFF1B1D21),
    onSurfaceVariant = Color(0xFFA8AEB9),
    outline = Color(0xFF363B43),
    outlineVariant = Color(0xFF26292E),
    error = ScoutrSemantic.critical.color,
    onError = ScoutrSemantic.critical.on,
    errorContainer = ScoutrSemantic.critical.container,
    onErrorContainer = ScoutrSemantic.critical.onContainer,
    surfaceContainerLowest = ScoutrPrimitive.neutral0,
    surfaceContainerLow = Color(0xFF101114),
    surfaceContainer = ScoutrSemantic.surfaceCard,
    surfaceContainerHigh = ScoutrSemantic.surfaceSelected,
    surfaceContainerHighest = ScoutrSemantic.surfaceSwipeBar,
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
