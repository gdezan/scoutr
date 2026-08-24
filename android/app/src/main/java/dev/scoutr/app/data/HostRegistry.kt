package dev.scoutr.app.data

import kotlinx.serialization.Serializable

/** Public host metadata. Credentials deliberately do not appear in this type. */
@Serializable
data class HostProfile(
    val hostId: String,
    val alias: String,
    val baseUrl: String,
    val exposure: ExposureKind,
    val profileGeneration: Long,
    val connectionRevision: Long,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
)

/** The registry's durable, non-secret state. */
@Serializable
data class HostRegistryState(
    val profiles: List<HostProfile> = emptyList(),
    val defaultHostId: String? = null,
    val updateHostId: String? = null,
    val inAppUpdatesEnabled: Boolean = false,
    val pendingLegacyConnection: Boolean = false,
    val pendingLegacyMetadataHostId: String? = null,
    val pendingCleanupHostIds: Set<String> = emptySet(),
    val nextProfileGeneration: Long = 1L,
    val nextConnectionRevision: Long = 1L,
    val legacyLinkGeneration: Long? = null,
)

/** Credentials are returned only by a host-bound lookup, never in registry state. */
data class HostCredentials(
    val baseUrl: String,
    val token: String,
    val exposure: ExposureKind,
) {
    /** Compatibility spelling used by the old singleton connection reader. */
    val host: String get() = baseUrl
}

/** Result of an authenticated probe before it is added to the registry. */
data class ProbedHost(
    val hostId: String,
    val baseUrl: String,
    val exposure: ExposureKind = ExposureKind.Custom,
)

/** Explicit choice required when removing or replacing the update host. */
sealed interface UpdateHostDisposition {
    /** Explicitly trust the replacement bridge's APK signing key and use it for updates. */
    data object TrustReplacementSigningKey : UpdateHostDisposition

    /** Keep updates enabled, but move them to another already paired profile. */
    data class UseExisting(val hostId: String) : UpdateHostDisposition

    /** Disable in-app updates. */
    data object Disable : UpdateHostDisposition
}

/** The identities involved in a successful replacement. */
data class HostIdentityReplacement(
    val previous: HostProfile,
    val replacement: HostProfile,
)
