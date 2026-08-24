package dev.scoutr.app.ui.nav

import androidx.navigation.NavBackStackEntry
import dev.scoutr.app.AppContainer
import dev.scoutr.app.data.HostProfile
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.HostRegistryState
import dev.scoutr.app.net.GenerationGuardedScoutrApi
import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.ScoutrApi

/** Resolves a route only when its immutable profile key is still current. */
internal fun AppContainer.currentHostProfile(key: HostProfileKey?): HostProfile? {
    if (key == null) return null
    return hostRegistry.snapshot().profiles.firstOrNull {
        it.hostId == key.hostId && it.profileGeneration == key.profileGeneration
    }
}

internal fun HostRegistryState.connectionRevision(key: HostProfileKey?): Long? {
    if (key == null) return null
    return profiles.firstOrNull {
        it.hostId == key.hostId && it.profileGeneration == key.profileGeneration
    }?.connectionRevision
}
internal fun NavBackStackEntry.routeProfile(argument: String = DestinationArgs.HOST_PROFILE): HostProfileKey? =
    decodeRouteProfile(arguments?.getString(argument))

/**
 * Captures one connection revision so a route cannot dispatch across a credential
 * refresh or identity change. The generation guard remains the final stale-route check.
 */
internal fun AppContainer.routeBinding(
    key: HostProfileKey,
    expectedConnectionRevision: Long? = null,
): HostConnectionBinding? {
    if (currentHostProfile(key) == null) return null
    val binding = currentHostBinding(key.hostId) ?: return null
    if (expectedConnectionRevision != null && binding.connectionRevision != expectedConnectionRevision) return null
    return binding
}

internal fun AppContainer.routeApi(
    key: HostProfileKey,
    expectedConnectionRevision: Long? = null,
): ScoutrApi? {
    val binding = routeBinding(key, expectedConnectionRevision) ?: return null
    return GenerationGuardedScoutrApi(
        registry = hostRegistry,
        profile = key,
        delegate = hostClients.api(binding),
        connectionRevision = binding.connectionRevision,
    )
}
/** Shared display for a forgotten or not-yet-probed remote route. */
internal fun hostUnavailableReason(key: HostProfileKey?): String =
    if (key == null) "This destination needs a paired host."
    else "Host forgotten or no longer available."
