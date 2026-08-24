package dev.scoutr.app.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The shared Board/Sessions host filter: `null` means All hosts.
 *
 * Deliberately process-local only — never written to SharedPreferences,
 * SavedStateHandle, or a route. Board and Sessions share one instance from
 * [dev.scoutr.app.AppContainer], so the selection survives tab switches but
 * resets to All on every cold process start.
 */
class HostFilterStore {
    private val _selectedHostId = MutableStateFlow<String?>(null)

    /** Currently selected host id, or null for All hosts. */
    val selectedHostId: StateFlow<String?> = _selectedHostId.asStateFlow()

    val selected: String? get() = _selectedHostId.value

    /** Selects one host, or All hosts when [hostId] is null. */
    fun select(hostId: String?) {
        _selectedHostId.value = hostId?.takeIf { it.isNotBlank() }
    }

    /**
     * Resets to All only when [hostId] is the current selection. Called when a
     * host is forgotten; an offline or incompatible host keeps the filter.
     */
    fun resetIfSelected(hostId: String) {
        if (_selectedHostId.value == hostId) select(null)
    }
}
