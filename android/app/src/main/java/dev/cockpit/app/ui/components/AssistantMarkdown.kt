package dev.cockpit.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

/**
 * Markdown renderer for assistant message text, themed to the Cockpit
 * always-dark M3 language. Headings, bold/italic, inline code, fenced code
 * blocks (monospace), lists, blockquotes and links all render; links open
 * via the platform URI handler.
 *
 * Deliberately only used for assistant text blocks — user bubbles and tool
 * chips render as plain text (see ChatScreen).
 */
@Composable
fun AssistantMarkdown(
    content: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    // Mono is one size everywhere — inline and fenced code are the same content class.
    val mono = type.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Markdown(
        content = content,
        modifier = modifier.fillMaxWidth(),
        colors = markdownColor(
            text = scheme.onSurface,
            // Dimmer than the user bubble surface so code never outranks speech.
            codeBackground = scheme.surfaceContainerHigh,
            inlineCodeBackground = scheme.surfaceContainerHigh,
            dividerColor = scheme.outlineVariant,
        ),
        typography = markdownTypography(
            // Calm, chat-sized headings — a transcript must never shout.
            h1 = type.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            h2 = type.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            h3 = type.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            h4 = type.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            h5 = type.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            h6 = type.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            text = type.bodyMedium,
            paragraph = type.bodyMedium,
            ordered = type.bodyMedium,
            bullet = type.bodyMedium,
            list = type.bodyMedium,
            code = mono,
            inlineCode = mono,
            // The single accent hue is reserved for AI-owned states; links in
            // assistant text are exactly that.
            textLink = TextLinkStyles(
                style = SpanStyle(
                    color = scheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
        ),
        padding = markdownPadding(
            block = 8.dp,
            listItemTop = 2.dp,
            listItemBottom = 2.dp,
            listIndent = 12.dp,
        ),
    )
}
