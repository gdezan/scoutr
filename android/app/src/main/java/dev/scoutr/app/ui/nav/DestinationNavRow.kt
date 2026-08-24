package dev.scoutr.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.rememberHaptic

/**
 * The four destinations as one row of selectable items. Both chrome shapes
 * embed it — the compact bottom bar and the wide window's session panel,
 * at its foot — so anatomy, badge rule and selection coloring cannot
 * drift between them. Selection is derived from the current route; on Chat no
 * item is selected, exactly as the old wide bottom bar behaved.
 */
@Composable
internal fun DestinationNavRow(
    currentRoute: String?,
    needsYouCount: Int,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Destination.entries.forEach { destination ->
            val selected = currentRoute == destination.route || currentRoute == destination.pattern
            val badge = if (destination == Destination.Board && needsYouCount > 0) needsYouCount else 0
            ScoutrNavItem(
                destination = destination,
                selected = selected,
                badge = badge,
                onClick = { onSelect(destination.route) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ScoutrNavItem(
    destination: Destination,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptic()
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                haptic(HapticEvent.Select)
                onClick()
            }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            if (badge > 0) {
                Box(
                    // Offset (not padding) so the badge can straddle the icon's
                    // corner: Compose padding rejects negative values and would
                    // crash the app at startup whenever the badge renders.
                    Modifier
                        .offset(x = 6.dp, y = (-6).dp)
                        .size(16.dp)
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (badge > 9) "9+" else badge.toString(),
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.size(2.dp))
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
