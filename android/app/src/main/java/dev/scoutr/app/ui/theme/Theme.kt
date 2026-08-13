package dev.scoutr.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import dev.scoutr.app.ui.motion.LocalReduceMotion

/**
 * Scoutr visual world — direction contract (impeccable, user-pinned brief).
 *
 * THESIS: the phone is the pilot's seat for agents running on the host —
 * every surface answers what is running, what needs the user, what is safe.
 * It refuses the dashboard habit of card-soup with scattered accents; the
 * running agent's state is the visual anchor.
 * OWN-WORLD: near-black canvas, elevated charcoal surfaces, off-white type,
 * dim gray secondary, one electric-blue accent reserved for AI-owned states
 * (active run, "needs you", the composer's send). Mono only for paths,
 * commands, and tool output — never as a costume. 1px dividers, tonal
 * elevation, compact radii; calm cards, state is the color.
 * STORY: the user glances — board → status in one line; opens a session →
 * the stream is readable in dim light, the last message is already on
 * screen, tools are quiet chips until tapped.
 * FORM: Operate mode, Material 3 dark, always dark (physical scene: a phone
 * in a dark room, one hand). Established world refined toward the pinned
 * Cursor-iOS north star (docs/cursor-ios-design-brief.md).
 * FINISH: unreviewed and undocumented is unfinished; this build ends with
 * the finish review, the verdict, and DESIGN.md.
 */
private val DarkColors = darkColorScheme(
    // One electric-blue accent; AI-owned states only.
    primary = Color(0xFF5B8CFF),
    onPrimary = Color(0xFF0A1226),
    primaryContainer = Color(0xFF1B2B54),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Color(0xFF46A758),
    onSecondary = Color(0xFF06250F),
    tertiary = Color(0xFFF5A524),
    // Near-black canvas, elevated charcoal surfaces, off-white type.
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFECEDF0),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFECEDF0),
    surfaceVariant = Color(0xFF1B1D21),
    onSurfaceVariant = Color(0xFFA8AEB9),
    // Selected chips/filters reuse the primary container pair so "selected"
    // never leaks a second accent hue (the M3 lavender default).
    secondaryContainer = Color(0xFF1B2B54),
    onSecondaryContainer = Color(0xFFD8E4FF),
    outline = Color(0xFF363B43),
    outlineVariant = Color(0xFF26292E),
    error = Color(0xFFE5484D),
    onError = Color(0xFF3A0B0C),
    // Thinking blocks: dimmed warm-tinted surface, distinct from tool chips.
    surfaceContainerHigh = Color(0xFF16171B),
    surfaceContainerHighest = Color(0xFF1A1C20),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D5FB8),
    secondary = Color(0xFF2F9E44),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFE9EDF5),
    onSurfaceVariant = Color(0xFF4C5567),
)

@Composable
fun ScoutrTheme(
    darkTheme: Boolean = true,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}
