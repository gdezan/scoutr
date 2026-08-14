package dev.scoutr.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.scoutr.app.ui.theme.ScoutrType

/**
 * The one section header in the app: mono caps, muted, 9.5sp — the same mark the
 * board's `WORKING` and the session list's `TODAY` use, so a header reads as a
 * divider rather than as content (§9a). The accent stays reserved for AI-owned
 * states, never for a static label.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = ScoutrType.monoSection,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = modifier,
    )
}
