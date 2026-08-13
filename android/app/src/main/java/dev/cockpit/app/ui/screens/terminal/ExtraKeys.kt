package dev.cockpit.app.ui.screens.terminal

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.view.KeyEvent
import com.termux.view.TerminalView

/**
 * Slice 7 "Extra keys": two fixed compact pages over the writable terminal —
 * page 1 Esc/Ctrl/Alt/Tab/arrows, page 2 Home/End/PgUp/PgDn/Ins/Del plus
 * common shell symbols. Modifier buttons cycle Off -> One-shot -> Off on tap
 * and lock on long press (see [ExtraKeyModifierState]); every other key sends
 * through the vendored view with the current modifier flags so Ctrl+arrow,
 * Alt+. etc. work exactly like a hardware keyboard.
 */
@Composable
internal fun ExtraKeysRow(
    view: TerminalView?,
    state: ExtraKeyModifierState,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
) {
    if (!visible) {
        // Collapsed: a slim bar that re-expands on tap, so the row can be
        // brought back without hunting for the top-bar toggle.
        Surface(
            onClick = onToggleVisibility,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Show extra keys",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        return
    }

    var page by remember { mutableStateOf(0) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (page == 0) {
                ExtraKey("Esc") { sendKey(view, KeyEvent.KEYCODE_ESCAPE, state) }
                ModifierKeyButton(state, ModifierKey.CTRL)
                ModifierKeyButton(state, ModifierKey.ALT)
                ExtraKey("Tab") { sendKey(view, KeyEvent.KEYCODE_TAB, state) }
                ExtraKey("↑") { sendKey(view, KeyEvent.KEYCODE_DPAD_UP, state) }
                ExtraKey("↓") { sendKey(view, KeyEvent.KEYCODE_DPAD_DOWN, state) }
                ExtraKey("←") { sendKey(view, KeyEvent.KEYCODE_DPAD_LEFT, state) }
                ExtraKey("→") { sendKey(view, KeyEvent.KEYCODE_DPAD_RIGHT, state) }
            } else {
                ExtraKey("Home") { sendKey(view, KeyEvent.KEYCODE_MOVE_HOME, state) }
                ExtraKey("End") { sendKey(view, KeyEvent.KEYCODE_MOVE_END, state) }
                ExtraKey("PgUp") { sendKey(view, KeyEvent.KEYCODE_PAGE_UP, state) }
                ExtraKey("PgDn") { sendKey(view, KeyEvent.KEYCODE_PAGE_DOWN, state) }
                ExtraKey("Ins") { sendKey(view, KeyEvent.KEYCODE_INSERT, state) }
                ExtraKey("Del") { sendKey(view, KeyEvent.KEYCODE_FORWARD_DEL, state) }
                ExtraKey("|") { sendSymbol(view, '|'.code, state) }
                ExtraKey("~") { sendSymbol(view, '~'.code, state) }
                ExtraKey("&") { sendSymbol(view, '&'.code, state) }
                ExtraKey(";") { sendSymbol(view, ';'.code, state) }
                ExtraKey("<") { sendSymbol(view, '<'.code, state) }
                ExtraKey(">") { sendSymbol(view, '>'.code, state) }
            }
            Box(Modifier.weight(1f))
            Surface(
                onClick = { page = 1 - page },
                color = Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(
                    if (page == 0) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = "Extra keys page ${page + 1} of 2",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp).size(18.dp),
                )
            }
        }
    }
}

private fun sendKey(view: TerminalView?, keyCode: Int, state: ExtraKeyModifierState) {
    if (view == null) return
    view.handleKeyCode(keyCode, state.toKeyMod())
    state.consumeOneShots()
}

private fun sendSymbol(view: TerminalView?, codePoint: Int, state: ExtraKeyModifierState) {
    if (view == null) return
    // inputCodePoint consults readControlKey/readAltKey internally, so the
    // modifier flags are passed false and the extra-key state applies.
    view.inputCodePoint(0, codePoint, false, false)
    state.consumeOneShots()
}

@Composable
private fun ModifierKeyButton(state: ExtraKeyModifierState, key: ModifierKey) {
    val mode = state.mode(key)
    val (container, content) = when (mode) {
        ModifierMode.OFF -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
        ModifierMode.ONE_SHOT -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ModifierMode.LOCKED -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    }
    Surface(
        modifier = Modifier.combinedClickable(
            onClick = { state.tap(key) },
            onLongClick = { state.longPress(key) },
            indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        ),
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            key.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ExtraKey(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
