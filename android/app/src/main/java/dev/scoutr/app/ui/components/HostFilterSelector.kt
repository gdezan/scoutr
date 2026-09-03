package dev.scoutr.app.ui.components

import dev.scoutr.app.ui.theme.ScoutrSpace
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scoutr.app.state.HostAvailability

/** One option in the shared Board/Sessions host filter. */
data class HostFilterOption(
    /** null is the All-hosts scope; otherwise a registered host id. */
    val hostId: String?,
    val label: String,
    val availability: HostAvailability?,
)

/**
 * The compact shared host-scope selector used by Board and Sessions. One
 * instance of the store feeds both screens, so the selected scope survives tab
 * switches and resets to All only when the selected host is forgotten.
 *
 * Availability rides the same node as the label so accessibility services read
 * one coherent state per chip ("Work, offline"), and selection is exposed via
 * the standard `selected` semantics.
 */
@Composable
fun HostFilterSelector(
    options: List<HostFilterOption>,
    selectedHostId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ScoutrSpace.sm),
    ) {
        options.forEach { option ->
            val selected = option.hostId == selectedHostId ||
                (option.hostId == null && selectedHostId == null)
            FilterChip(
                selected = selected,
                onClick = { onSelect(option.hostId) },
                label = {
                    Column {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.secondaryContainer,
                    enabled = true,
                    selected = selected,
                ),
                modifier = Modifier.semantics {
                    this.selected = selected
                    contentDescription = buildString {
                        append(option.label)
                        append(", ")
                        when (option.availability) {
                            null -> append("all hosts")
                            is HostAvailability.Online -> append("online")
                            is HostAvailability.Offline -> append("offline")
                            is HostAvailability.Incompatible -> append("incompatible")
                            is HostAvailability.IdentityChanged -> append("identity changed")
                            HostAvailability.Unknown -> append("checking")
                        }
                    }
                },
            )
        }
    }
}
