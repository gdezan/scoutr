package dev.cockpit.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.cockpit.app.state.LiveOutputUiState
import dev.cockpit.app.state.LiveOutputViewModel
import dev.cockpit.app.ui.motion.LocalReduceMotion

/**
 * The raw pane tail, full screen and on demand.
 *
 * The chat screen carries a working indicator, not output, so this is the one
 * place `/api/agents/{id}/read` is consumed — and the poll lives exactly as
 * long as the screen is visible (`LifecycleStartEffect`), never in the
 * background.
 */
@Composable
fun LiveOutputScreen(
    viewModel: LiveOutputViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()

    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    Column(modifier.fillMaxSize().testTag("live_output_screen")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("live_output_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Live output",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    viewModel.paneId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        LiveOutputHeader(ui)
        val outputLines = ui.lines
        when {
            ui.loading && outputLines.isEmpty() -> LiveOutputMessage("Waiting for output…")
            outputLines.isEmpty() && ui.error != null -> LiveOutputMessage(
                "Output unavailable\n${ui.error}",
                MaterialTheme.colorScheme.error,
            )
            outputLines.isEmpty() -> LiveOutputMessage("No recent output")
            else -> LiveOutputBody(outputLines)
        }
    }
}

@Composable
private fun LiveOutputHeader(ui: LiveOutputUiState) {
    val stateLabel = when {
        ui.error != null -> "STALE · RECONNECTING"
        ui.truncated -> "EARLIER OUTPUT TRIMMED"
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "LIVE OUTPUT",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        stateLabel?.let {
            Text(
                it,
                color = if (ui.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ColumnScope.LiveOutputBody(lines: List<String>) {
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val density = LocalDensity.current
        val maxLines = with(density) {
            ((maxHeight - 16.dp).toPx() / 16.sp.toPx()).toInt().coerceAtLeast(1)
        }
        val visibleLines = lines.takeLast(maxLines)
        // Short, non-replaying pulse when the newest line changes: new output
        // arrives already composed, so a quick fade-up marks the change without
        // faking typing. Skipped under reduce motion.
        val reduceMotion = LocalReduceMotion.current
        val pulse = remember { Animatable(1f) }
        LaunchedEffect(visibleLines.lastOrNull()) {
            if (!reduceMotion) {
                pulse.snapTo(0f)
                pulse.animateTo(1f, animationSpec = tween(180, easing = FastOutSlowInEasing))
            }
        }
        val rise = with(density) { 4.dp.toPx() }
        Box(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                visibleLines.joinToString("\n"),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = maxLines,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.graphicsLayer {
                    alpha = pulse.value
                    translationY = (1f - pulse.value) * rise
                },
            )
        }
    }
}

@Composable
private fun ColumnScope.LiveOutputMessage(message: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
