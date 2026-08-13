package dev.scoutr.app.ui.screens.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.scoutr.app.state.TerminalUiState
import dev.scoutr.app.ui.components.ConfirmDialog

/**
 * Slice 7 overlay states for the terminal area: connecting, failed (retry),
 * unsupported, empty (no panes), and the observer takeover confirmation.
 * Status follows the settled mapping: connecting -> onSurfaceVariant,
 * failed/unsupported -> error, observing -> secondary, control -> primary.
 */
@Composable
internal fun TerminalOverlay(
    state: TerminalUiState,
    paneCount: Int,
    onRetry: () -> Unit,
    onTakeover: () -> Unit,
    onDismissTakeover: () -> Unit,
) {
    when (val c = state.connection) {
        is dev.scoutr.app.state.TerminalConnectionState.Connecting,
        is dev.scoutr.app.state.TerminalConnectionState.Reconnecting,
        -> ConnectingOverlay(c is dev.scoutr.app.state.TerminalConnectionState.Reconnecting)
        is dev.scoutr.app.state.TerminalConnectionState.Failed -> FailedOverlay(
            message = c.message,
            retryable = c.retryable,
            onRetry = onRetry,
        )
        is dev.scoutr.app.state.TerminalConnectionState.Unsupported -> UnsupportedOverlay(c.explanation)
        dev.scoutr.app.state.TerminalConnectionState.Closed -> EmptyOverlay(paneCount, onRetry)
        is dev.scoutr.app.state.TerminalConnectionState.Idle,
        is dev.scoutr.app.state.TerminalConnectionState.Ready,
        -> Unit
    }
    if (state.canTakeover && state.paneName != null) {
        TakeoverDialog(
            paneName = state.paneName,
            onConfirm = onTakeover,
            onDismiss = onDismissTakeover,
        )
    }
}

@Composable
private fun ConnectingOverlay(reconnecting: Boolean) {
    BoxCenter {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.size(14.dp))
            Text(
                if (reconnecting) "Reconnecting…" else "Connecting…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedOverlay(message: String, retryable: Boolean, onRetry: () -> Unit) {
    BoxCenter {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Connection failed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (retryable) {
                Spacer(Modifier.size(14.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun UnsupportedOverlay(explanation: String) {
    BoxCenter {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Terminal unavailable",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EmptyOverlay(paneCount: Int, onRetry: () -> Unit) {
    BoxCenter {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (paneCount == 0) "No panes yet" else "Pane closed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                if (paneCount == 0)
                    "Create a pane in the hierarchy drawer to get started."
                else
                    "Open another pane from the hierarchy drawer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(14.dp))
            Button(onClick = onRetry) { Text("Open terminal") }
        }
    }
}

/** Centering helper for the overlay column. */
@Composable
private fun BoxCenter(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Observer takeover: an unowned pane opens writable; a pane owned elsewhere
 * opens as a phone-sized observer and this dialog offers control. Takeover
 * always requires a fresh confirmation naming the pane and warning that the
 * other viewer is displaced.
 */
@Composable
internal fun TakeoverDialog(
    paneName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = "Take control?",
        text = "\"$paneName\" is being viewed elsewhere. Taking control disconnects the other viewer and makes this pane writable.",
        confirmLabel = "Take control",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Pane-closed notice shown briefly when the active pane disappears. */
@Composable
internal fun PaneClosedNotice(paneName: String?, onOpenDrawer: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (paneName != null) "Pane \"$paneName\" closed" else "Pane closed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenDrawer) { Text("Choose another") }
        }
    }
}

/** Small status chip for the compact top bar. */
@Composable
internal fun TerminalStatusChip(state: TerminalUiState) {
    val (label, color) = when (val c = state.connection) {
        is dev.scoutr.app.state.TerminalConnectionState.Ready ->
            if (c.writable) "Control" to MaterialTheme.colorScheme.primary
            else "Observing" to MaterialTheme.colorScheme.secondary
        is dev.scoutr.app.state.TerminalConnectionState.Connecting,
        is dev.scoutr.app.state.TerminalConnectionState.Reconnecting,
        -> "Connecting" to MaterialTheme.colorScheme.onSurfaceVariant
        is dev.scoutr.app.state.TerminalConnectionState.Failed,
        is dev.scoutr.app.state.TerminalConnectionState.Unsupported,
        -> "Unavailable" to MaterialTheme.colorScheme.error
        is dev.scoutr.app.state.TerminalConnectionState.Closed -> "Closed" to MaterialTheme.colorScheme.onSurfaceVariant
        is dev.scoutr.app.state.TerminalConnectionState.Idle -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
