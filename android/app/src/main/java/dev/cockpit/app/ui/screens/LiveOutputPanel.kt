package dev.cockpit.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.cockpit.app.ui.motion.LocalReduceMotion
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cockpit.app.state.ChatUiState
import dev.cockpit.app.state.meaningfulLiveOutputLines

@Composable
internal fun LiveOutputDrawer(ui: ChatUiState) {
    val outputLines = meaningfulLiveOutputLines(ui.liveOutputText)
    AnimatedVisibility(
        visible = ui.liveOutputExpanded,
        enter = expandVertically(
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Bottom,
        ) + fadeIn(tween(120, delayMillis = 60)),
        exit = shrinkVertically(tween(150), shrinkTowards = Alignment.Bottom) + fadeOut(tween(100)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(MaterialTheme.colorScheme.surface)
                .clipToBounds()
                .testTag("live_output_drawer"),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LiveOutputHeader(ui)
            when {
                ui.liveOutputLoading && outputLines.isEmpty() -> LiveOutputMessage("Waiting for output…")
                outputLines.isEmpty() && ui.liveOutputError != null -> LiveOutputMessage(
                    "Output unavailable\n${ui.liveOutputError}",
                    MaterialTheme.colorScheme.error,
                )
                outputLines.isEmpty() -> LiveOutputMessage("No recent output")
                else -> LiveOutputBody(outputLines)
            }
        }
    }
}

@Composable
private fun LiveOutputHeader(ui: ChatUiState) {
    val stateLabel = when {
        ui.liveOutputError != null -> "STALE · RECONNECTING"
        ui.liveOutputTruncated -> "EARLIER OUTPUT TRIMMED"
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
                color = if (ui.liveOutputError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
internal fun LiveOutputStrip(
    ui: ChatUiState,
    onToggle: () -> Unit,
) {
    val summary = if (ui.liveOutputError != null && meaningfulLiveOutputLines(ui.liveOutputText).isEmpty()) {
        "Output unavailable"
    } else {
        ui.liveOutputSummary
    }
    val description = "Live output, ${if (ui.liveOutputExpanded) "expanded" else "collapsed"}. $summary"
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 14.dp)
                .testTag("live_output_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(liveStatusColor(ui.agentStatus)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (ui.liveOutputExpanded) "▾" else "▴",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LiveOutputMessage(message: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun liveStatusColor(status: String): Color = when (status) {
    "blocked" -> MaterialTheme.colorScheme.error
    "working" -> MaterialTheme.colorScheme.primary
    "done" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.outline
}
