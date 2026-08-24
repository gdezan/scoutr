package dev.scoutr.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64
import java.util.Locale

/**
 * The durable host registry. Metadata and encrypted credential records are
 * committed together, while the bearer token is only returned by a
 * host-qualified credentials lookup.
 */
class HostRegistryStore(
    context: Context,
    private val cipher: ConnectionCipher = AndroidKeystoreConnectionCipher(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val mutableState = MutableStateFlow(snapshot())
    /** Emits after every committed invariant-preserving registry mutation. */
    val states: StateFlow<HostRegistryState> = mutableState.asStateFlow()

    /** A read-only snapshot; callers cannot replace the profile list. */
    val state: HostRegistryState
        get() = snapshot()

    @Synchronized
    fun snapshot(): HostRegistryState {
        val stored = readSnapshot()
        return stored.state.copy(pendingLegacyConnection = stored.pending != null)
    }

    /** Returns credentials only for an existing, non-retiring profile. */
    @Synchronized
    fun credentials(hostId: String): HostCredentials? {
        val id = cleanHostIdOrNull(hostId) ?: return null
        val stored = readSnapshot()
        val profile = stored.state.profiles.firstOrNull { it.hostId == id } ?: return null
        return stored.credentials[id]?.toCredentials(profile.baseUrl, profile.exposure, cipher)
    }

    /** Runs a local-state write atomically with respect to profile retirement. */
    @Synchronized
    fun writeIfRegistered(hostId: String, write: () -> Unit): Boolean {
        val id = cleanHostIdOrNull(hostId) ?: return false
        if (readSnapshot().state.profiles.none { it.hostId == id }) return false
        write()
        return true
    }
    /** Returns the durable pending singleton credentials during migration. */
    @Synchronized
    fun pendingCredentials(): HostCredentials? = readSnapshot().pending?.toCredentials()

    /** Convenience overload for the result of an authenticated health probe. */
    @Synchronized
    fun addOrRefresh(probe: ProbedHost, token: String, nowMs: Long = clock()): HostProfile =
        addOrRefresh(probe.hostId, probe.baseUrl, token, probe.exposure, nowMs)

    /** Refresh an existing profile without changing its alias or generation. */
    @Synchronized
    fun refreshCredentials(
        hostId: String,
        baseUrl: String,
        token: String,
        exposure: ExposureKind = ExposureKind.Custom,
        nowMs: Long = clock(),
    ): HostProfile {
        val id = requireHostId(hostId)
        val stored = readSnapshot()
        ensureNotPendingCleanup(stored.state, id)
        require(stored.state.profiles.any { it.hostId == id }) { "Unknown host: $id" }
        val encrypted = encryptTokenOrThrow(id, token)
        return refreshEncrypted(stored, id, normalizeBaseUrl(baseUrl), exposure, encrypted)
    }

    /** Add a new identity or refresh an existing one. */
    @Synchronized
    fun add(
        hostId: String,
        baseUrl: String,
        token: String,
        exposure: ExposureKind = ExposureKind.Custom,
        nowMs: Long = clock(),
    ): HostProfile = addOrRefresh(hostId, baseUrl, token, exposure, nowMs)

    /** Token-bearing add overload used by pairing code. */
    @Synchronized
    fun addOrRefresh(
        hostId: String,
        baseUrl: String,
        token: String,
        exposure: ExposureKind = ExposureKind.Custom,
        nowMs: Long = clock(),
    ): HostProfile {
        val id = requireHostId(hostId)
        val stored = readSnapshot()
        ensureNotPendingCleanup(stored.state, id)
        require(stored.pending == null) { "A legacy migration is still pending" }
        val encrypted = encryptTokenOrThrow(id, token)
        return addOrRefreshEncrypted(stored, id, normalizeBaseUrl(baseUrl), exposure, encrypted, nowMs)
    }

    /** Rename a profile and retain that alias after a later forget/re-pair. */
    @Synchronized
    fun rename(hostId: String, alias: String): HostProfile {
        val id = requireHostId(hostId)
        val cleanAlias = requireAlias(alias)
        val stored = readSnapshot()
        val current = stored.state.profiles.firstOrNull { it.hostId == id }
            ?: error("Unknown host: $id")
        val updated = current.copy(alias = cleanAlias)
        val profiles = stored.state.profiles.map { if (it.hostId == id) updated else it }
        commit(
            stored.copy(
                state = stored.state.copy(profiles = profiles),
                aliases = stored.aliases + (id to cleanAlias),
            ),
        )
        return updated
    }

    fun rememberAlias(hostId: String, alias: String): HostProfile = rename(hostId, alias)

    /** Marks explicit user targeting; background probes and polling do not call this. */
    @Synchronized
    fun markUsed(hostId: String, nowMs: Long = clock()): HostProfile {
        val id = requireHostId(hostId)
        val stored = readSnapshot()
        val current = stored.state.profiles.firstOrNull { it.hostId == id }
            ?: error("Unknown host: $id")
        val updated = current.copy(lastUsedAtMs = nowMs)
        commit(stored.copy(state = stored.state.copy(profiles = replace(stored.state.profiles, updated))))
        return updated
    }

    /** Setting the default is explicit targeting, so it also updates recency. */
    @Synchronized
    fun setDefaultHost(hostId: String, nowMs: Long = clock()): HostProfile {
        val id = requireHostId(hostId)
        val stored = readSnapshot()
        val current = stored.state.profiles.firstOrNull { it.hostId == id }
            ?: error("Unknown host: $id")
        val updated = current.copy(lastUsedAtMs = nowMs)
        commit(
            stored.copy(
                state = stored.state.copy(
                    profiles = replace(stored.state.profiles, updated),
                    defaultHostId = id,
                ),
            ),
        )
        return updated
    }

    /**
     * Changes the update source only after the signing-key warning was shown
     * and confirmed by the caller.
     */
    @Synchronized
    fun setUpdateHost(hostId: String, signingKeyWarningConfirmed: Boolean = false): HostProfile {
        require(signingKeyWarningConfirmed) { "Changing update host requires signing-key confirmation" }
        val id = requireHostId(hostId)
        val stored = readSnapshot()
        val profile = stored.state.profiles.firstOrNull { it.hostId == id }
            ?: error("Unknown host: $id")
        commit(
            stored.copy(
                state = stored.state.copy(updateHostId = id, inAppUpdatesEnabled = true),
            ),
        )
        return profile
    }

    fun confirmUpdateHost(hostId: String): HostProfile = setUpdateHost(hostId, true)

    @Synchronized
    fun disableUpdates() {
        val stored = readSnapshot()
        if (!stored.state.inAppUpdatesEnabled && stored.state.updateHostId == null) return
        commit(stored.copy(state = stored.state.copy(updateHostId = null, inAppUpdatesEnabled = false)))
    }

    /** Stage the old singleton before its legacy preference fields are removed. */
    @Synchronized
    fun stageLegacyConnection(
        baseUrl: String,
        token: String,
        exposure: ExposureKind = ExposureKind.Custom,
        createdAtMs: Long = clock(),
    ) {
        val stored = readSnapshot()
        require(stored.state.profiles.isEmpty()) { "Cannot stage legacy credentials with profiles" }
        val pending = encryptPendingOrThrow(normalizeBaseUrl(baseUrl), token, exposure, createdAtMs)
        commit(stored.copy(pending = pending))
    }

    /**
     * Promotes a pending legacy credential after a compatible authenticated
     * health response supplies the stable bridge identity.
     */
    @Synchronized
    fun promotePending(reportedHostId: String, nowMs: Long = clock()): HostProfile {
        val id = requireHostId(reportedHostId)
        val stored = readSnapshot()
        val pending = stored.pending ?: error("No pending legacy connection")
        require(stored.state.profiles.isEmpty()) { "A pending connection cannot coexist with profiles" }
        ensureNotPendingCleanup(stored.state, id)
        require(stored.state.profiles.none { it.hostId == id }) { "Duplicate host identity: $id" }
        val allocation = allocateProfileEpochs(stored.state)
        val alias = stored.aliases[id]?.takeIf { it.isNotBlank() }
            ?: deriveHostAlias(pending.baseUrl)
        val profile = HostProfile(
            hostId = id,
            alias = alias,
            baseUrl = pending.baseUrl,
            exposure = pending.exposure,
            profileGeneration = allocation.profileGeneration,
            connectionRevision = allocation.connectionRevision,
            createdAtMs = pending.createdAtMs,
            lastUsedAtMs = nowMs,
        )
        val nextState = allocation.state.copy(
            profiles = listOf(profile),
            defaultHostId = id,
            updateHostId = id,
            inAppUpdatesEnabled = true,
            pendingLegacyMetadataHostId = id,
            legacyLinkGeneration = profile.profileGeneration,
        )
        val credentials = stored.credentials + (id to pending.encryptedToken)
        commit(
            stored.copy(
                state = nextState,
                credentials = credentials,
                pending = null,
                aliases = stored.aliases + (id to alias),
            ),
        )
        return profile
    }

    /** Imports a legacy singleton with an identity, or stages it for probing. */
    @Synchronized
    fun importLegacyConnection(saved: ConnectionStore.Saved, nowMs: Long = clock()): HostProfile? {
        val stored = readSnapshot()
        require(stored.state.profiles.isEmpty() && stored.pending == null) {
            "Legacy import is only valid for an empty registry"
        }
        val id = saved.hostId?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null) {
            stageLegacyConnection(saved.host, saved.token, saved.exposure, nowMs)
            return null
        }
        val encrypted = encryptTokenOrThrow(id, saved.token)
        return createInitialProfile(
            stored = stored,
            hostId = id,
            baseUrl = normalizeBaseUrl(saved.host),
            exposure = saved.exposure,
            encrypted = encrypted,
            nowMs = nowMs,
            createdAtMs = nowMs,
        )
    }

    /** Completes the synchronous metadata adoption marker after a catalog commit. */
    @Synchronized
    fun clearPendingLegacyMetadata(hostId: String): Boolean {
        val id = requireHostId(hostId)
        val stored = readSnapshot()
        if (stored.state.pendingLegacyMetadataHostId != id) return false
        commit(stored.copy(state = stored.state.copy(pendingLegacyMetadataHostId = null)))
        return true
    }

    fun markLegacyMetadataAdopted(hostId: String): Boolean = clearPendingLegacyMetadata(hostId)

    /**
     * Removes a profile and its credentials, while retaining aliases and
     * host-qualified pin/archive data. Local cleanup is represented by a
     * tombstone until the coordinator confirms it completed.
     */
    @Synchronized
    fun validateForget(hostId: String, updateHostDisposition: UpdateHostDisposition? = null) {
        val id = requireHostId(hostId)
        val state = readSnapshot().state
        check(state.profiles.any { it.hostId == id }) { "Unknown host: $id" }
        updateAfterForget(
            state = state,
            removedId = id,
            remaining = state.profiles.filterNot { it.hostId == id },
            disposition = updateHostDisposition,
        )
    }

    @Synchronized
    fun forget(
        hostId: String,
        updateHostDisposition: UpdateHostDisposition? = null,
    ): HostProfile {
        val id = requireHostId(hostId)
        val stored = readSnapshot()
        val removed = stored.state.profiles.firstOrNull { it.hostId == id }
            ?: error("Unknown host: $id")
        val remaining = stored.state.profiles.filterNot { it.hostId == id }
        val (updateHostId, updatesEnabled) = updateAfterForget(
            state = stored.state,
            removedId = id,
            remaining = remaining,
            disposition = updateHostDisposition,
        )
        val defaultHostId = if (stored.state.defaultHostId != id) {
            stored.state.defaultHostId
        } else {
            remaining.maxWithOrNull(
                compareBy<HostProfile> { it.lastUsedAtMs }.thenBy { it.hostId },
            )?.hostId
        }
        val nextState = stored.state.copy(
            profiles = remaining,
            defaultHostId = defaultHostId,
            updateHostId = updateHostId,
            inAppUpdatesEnabled = updatesEnabled,
            pendingCleanupHostIds = stored.state.pendingCleanupHostIds + id,
            pendingLegacyMetadataHostId = stored.state.pendingLegacyMetadataHostId
                ?.takeUnless { it == id },
            legacyLinkGeneration = stored.state.legacyLinkGeneration
                ?.takeUnless { it == removed.profileGeneration },
        )
        commit(stored.copy(state = nextState, credentials = stored.credentials - id))
        return removed
    }

    fun forgetWithReplacement(hostId: String, replacementHostId: String): HostProfile =
        forget(hostId, UpdateHostDisposition.UseExisting(replacementHostId))

    fun forgetWithUpdatesDisabled(hostId: String): HostProfile =
        forget(hostId, UpdateHostDisposition.Disable)

    /** Marks the local cleanup tombstone complete; re-pair is then allowed. */
    @Synchronized
    fun completePendingCleanup(hostId: String): Boolean {
        val id = requireHostId(hostId)
        val stored = readSnapshot()
        if (id !in stored.state.pendingCleanupHostIds) return false
        commit(
            stored.copy(
                state = stored.state.copy(
                    pendingCleanupHostIds = stored.state.pendingCleanupHostIds - id,
                ),
            ),
        )
        return true
    }

    fun clearPendingCleanup(hostId: String): Boolean = completePendingCleanup(hostId)

    /**
     * Validates identity replacement before transport retirement.
     */
    @Synchronized
    fun validateIdentityReplacement(
        previousHostId: String,
        reportedHostId: String,
        updateHostDisposition: UpdateHostDisposition? = null,
    ) {
        val oldId = requireHostId(previousHostId)
        val newId = requireHostId(reportedHostId)
        require(oldId != newId) { "Replacement identity must differ from the old identity" }
        val state = readSnapshot().state
        check(state.profiles.any { it.hostId == oldId }) { "Unknown host: $oldId" }
        require(state.profiles.none { it.hostId == newId }) { "Duplicate host identity: $newId" }
        ensureNotPendingCleanup(state, newId)
        updateDispositionForReplacement(state, oldId, newId, updateHostDisposition)
    }

    /** Replaces an authenticated old identity atomically after preflight validation. */
    @Synchronized
    fun replaceIdentity(
        previousHostId: String,
        reportedHostId: String,
        baseUrl: String,
        token: String,
        exposure: ExposureKind = ExposureKind.Custom,
        updateHostDisposition: UpdateHostDisposition? = null,
        nowMs: Long = clock(),
    ): HostIdentityReplacement {
        val oldId = requireHostId(previousHostId)
        val newId = requireHostId(reportedHostId)
        require(oldId != newId) { "Replacement identity must differ from the old identity" }
        val stored = readSnapshot()
        val old = stored.state.profiles.firstOrNull { it.hostId == oldId }
            ?: error("Unknown host: $oldId")
        require(stored.state.profiles.none { it.hostId == newId }) {
            "Duplicate host identity: $newId"
        }
        ensureNotPendingCleanup(stored.state, newId)
        val encrypted = encryptTokenOrThrow(newId, token)
        val disposition = updateDispositionForReplacement(
            state = stored.state,
            oldId = oldId,
            newId = newId,
            disposition = updateHostDisposition,
        )
        val allocation = allocateProfileEpochs(stored.state)
        val alias = old.alias
        val replacement = HostProfile(
            hostId = newId,
            alias = alias,
            baseUrl = normalizeBaseUrl(baseUrl),
            exposure = exposure,
            profileGeneration = allocation.profileGeneration,
            connectionRevision = allocation.connectionRevision,
            createdAtMs = nowMs,
            lastUsedAtMs = old.lastUsedAtMs,
        )
        val profiles = stored.state.profiles.map { if (it.hostId == oldId) replacement else it }
        val nextState = allocation.state.copy(
            profiles = profiles,
            defaultHostId = if (stored.state.defaultHostId == oldId) newId else stored.state.defaultHostId,
            updateHostId = disposition.hostId,
            inAppUpdatesEnabled = disposition.enabled,
            pendingCleanupHostIds = stored.state.pendingCleanupHostIds + oldId,
            pendingLegacyMetadataHostId = stored.state.pendingLegacyMetadataHostId
                ?.takeUnless { it == oldId },
            legacyLinkGeneration = stored.state.legacyLinkGeneration
                ?.takeUnless { it == old.profileGeneration },
        )
        commit(
            stored.copy(
                state = nextState,
                credentials = (stored.credentials - oldId) + (newId to encrypted),
                aliases = stored.aliases + (oldId to alias) + (newId to alias),
            ),
        )
        return HostIdentityReplacement(old, replacement)
    }

    /** Clears the Keystore key only after the final credential copy is gone. */
    @Synchronized
    fun clearCredentialsIfUnused() {
        val stored = readSnapshot()
        if (stored.state.profiles.isEmpty() && stored.pending == null) cipher.clearKey()
    }

    private fun addOrRefreshEncrypted(
        stored: StoredSnapshot,
        id: String,
        normalizedUrl: String,
        exposure: ExposureKind,
        encrypted: StoredEncryptedToken,
        nowMs: Long,
    ): HostProfile {
        val current = stored.state.profiles.firstOrNull { it.hostId == id }
        return if (current == null) {
            createProfile(
                stored = stored,
                hostId = id,
                baseUrl = normalizedUrl,
                exposure = exposure,
                encrypted = encrypted,
                nowMs = nowMs,
                createdAtMs = nowMs,
            )
        } else {
            refreshEncrypted(stored, id, normalizedUrl, exposure, encrypted)
        }
    }

    private fun refreshEncrypted(
        stored: StoredSnapshot,
        id: String,
        normalizedUrl: String,
        exposure: ExposureKind,
        encrypted: StoredEncryptedToken,
    ): HostProfile {
        val current = stored.state.profiles.first { it.hostId == id }
        val revision = allocateConnectionRevision(stored.state)
        val updated = current.copy(
            baseUrl = normalizedUrl,
            exposure = exposure,
            connectionRevision = revision.value,
        )
        commit(
            stored.copy(
                state = revision.state.copy(profiles = replace(stored.state.profiles, updated)),
                credentials = stored.credentials + (id to encrypted),
            ),
        )
        return updated
    }

    private fun createInitialProfile(
        stored: StoredSnapshot,
        hostId: String,
        baseUrl: String,
        exposure: ExposureKind,
        encrypted: StoredEncryptedToken,
        nowMs: Long,
        createdAtMs: Long,
    ): HostProfile {
        require(stored.state.profiles.isEmpty()) { "Initial profile already exists" }
        ensureNotPendingCleanup(stored.state, hostId)
        val allocation = allocateProfileEpochs(stored.state)
        val alias = stored.aliases[hostId]?.takeIf { it.isNotBlank() } ?: deriveHostAlias(baseUrl)
        val profile = HostProfile(
            hostId = hostId,
            alias = alias,
            baseUrl = baseUrl,
            exposure = exposure,
            profileGeneration = allocation.profileGeneration,
            connectionRevision = allocation.connectionRevision,
            createdAtMs = createdAtMs,
            lastUsedAtMs = nowMs,
        )
        commit(
            stored.copy(
                state = allocation.state.copy(
                    profiles = listOf(profile),
                    defaultHostId = hostId,
                    updateHostId = hostId,
                    inAppUpdatesEnabled = true,
                    pendingLegacyMetadataHostId = hostId,
                    legacyLinkGeneration = profile.profileGeneration,
                ),
                credentials = stored.credentials + (hostId to encrypted),
                aliases = stored.aliases + (hostId to alias),
            ),
        )
        return profile
    }

    private fun createProfile(
        stored: StoredSnapshot,
        hostId: String,
        baseUrl: String,
        exposure: ExposureKind,
        encrypted: StoredEncryptedToken,
        nowMs: Long,
        createdAtMs: Long,
    ): HostProfile {
        val allocation = allocateProfileEpochs(stored.state)
        val first = stored.state.profiles.isEmpty()
        val alias = stored.aliases[hostId]?.takeIf { it.isNotBlank() } ?: deriveHostAlias(baseUrl)
        val profile = HostProfile(
            hostId = hostId,
            alias = alias,
            baseUrl = baseUrl,
            exposure = exposure,
            profileGeneration = allocation.profileGeneration,
            connectionRevision = allocation.connectionRevision,
            createdAtMs = createdAtMs,
            lastUsedAtMs = nowMs,
        )
        val nextState = allocation.state.copy(
            profiles = stored.state.profiles + profile,
            defaultHostId = if (first) hostId else stored.state.defaultHostId,
            updateHostId = if (first) hostId else stored.state.updateHostId,
            inAppUpdatesEnabled = if (first) true else stored.state.inAppUpdatesEnabled,
        )
        commit(
            stored.copy(
                state = nextState,
                credentials = stored.credentials + (hostId to encrypted),
                aliases = stored.aliases + (hostId to alias),
            ),
        )
        return profile
    }

    private fun updateAfterForget(
        state: HostRegistryState,
        removedId: String,
        remaining: List<HostProfile>,
        disposition: UpdateHostDisposition?,
    ): Pair<String?, Boolean> {
        if (state.updateHostId != removedId) {
            return state.updateHostId to state.inAppUpdatesEnabled
        }
        if (remaining.isEmpty()) return null to false
        return when (disposition) {
            is UpdateHostDisposition.UseExisting -> {
                require(remaining.any { it.hostId == disposition.hostId }) {
                    "Replacement update host is not paired"
                }
                disposition.hostId to true
            }
            UpdateHostDisposition.Disable -> null to false
            UpdateHostDisposition.TrustReplacementSigningKey ->
                error("A removed host cannot be its own update replacement")
            null -> error("Removing the update host requires a replacement or disablement")
        }
    }

    private data class UpdateResolution(val hostId: String?, val enabled: Boolean)

    private fun updateDispositionForReplacement(
        state: HostRegistryState,
        oldId: String,
        newId: String,
        disposition: UpdateHostDisposition?,
    ): UpdateResolution {
        if (state.updateHostId != oldId) return UpdateResolution(state.updateHostId, state.inAppUpdatesEnabled)
        val chosen = disposition ?: error("Replacing the update host requires an explicit disposition")
        return when (chosen) {
            UpdateHostDisposition.TrustReplacementSigningKey -> UpdateResolution(newId, true)
            is UpdateHostDisposition.UseExisting -> {
                require(chosen.hostId != oldId && state.profiles.any { it.hostId == chosen.hostId }) {
                    "Replacement update host is not paired"
                }
                UpdateResolution(chosen.hostId, true)
            }
            UpdateHostDisposition.Disable -> UpdateResolution(null, false)
        }
    }

    private data class EpochAllocation(
        val profileGeneration: Long,
        val connectionRevision: Long,
        val state: HostRegistryState,
    )

    private data class EpochValue(val value: Long, val state: HostRegistryState)

    private fun allocateProfileEpochs(state: HostRegistryState): EpochAllocation {
        val profile = allocateProfileGeneration(state)
        val connection = allocateConnectionRevision(profile.state)
        return EpochAllocation(profile.value, connection.value, connection.state)
    }

    private fun allocateProfileGeneration(state: HostRegistryState): EpochValue {
        val floor = (state.profiles.maxOfOrNull { it.profileGeneration } ?: 0L) + 1L
        val value = maxOf(state.nextProfileGeneration, floor)
        require(value > 0 && value < Long.MAX_VALUE) { "Profile generation exhausted" }
        return EpochValue(value, state.copy(nextProfileGeneration = value + 1L))
    }

    private fun allocateConnectionRevision(state: HostRegistryState): EpochValue {
        val floor = (state.profiles.maxOfOrNull { it.connectionRevision } ?: 0L) + 1L
        val value = maxOf(state.nextConnectionRevision, floor)
        require(value > 0 && value < Long.MAX_VALUE) { "Connection revision exhausted" }
        return EpochValue(value, state.copy(nextConnectionRevision = value + 1L))
    }

    private fun commit(stored: StoredSnapshot) {
        val state = stored.state.copy(pendingLegacyConnection = stored.pending != null)
        val edit = prefs.edit()
            .putString(KEY_STATE, json.encodeToString(state))
            .putString(KEY_CREDENTIALS, json.encodeToString(stored.credentials))
            .putString(KEY_ALIASES, json.encodeToString(stored.aliases))
        if (stored.pending == null) {
            edit.remove(KEY_PENDING)
        } else {
            edit.putString(KEY_PENDING, json.encodeToString(stored.pending))
        }
        check(edit.commit()) { "Could not commit host registry" }
        mutableState.value = state
        if (state.profiles.isEmpty() && stored.pending == null) {
            // Deliberately after the preference commit: a failed write never
            // destroys the only decryptable credential copy.
            runCatching { cipher.clearKey() }
        }
    }

    private data class StoredSnapshot(
        val state: HostRegistryState,
        val credentials: Map<String, StoredEncryptedToken>,
        val aliases: Map<String, String>,
        val pending: PendingLegacyRecord?,
    )

    @Serializable
    private data class StoredEncryptedToken(
        val ciphertext: String,
        val iv: String,
    )

    @Serializable
    private data class PendingLegacyRecord(
        val baseUrl: String,
        val exposure: ExposureKind,
        val encryptedToken: StoredEncryptedToken,
        val createdAtMs: Long,
    )

    private fun readSnapshot(): StoredSnapshot {
        val state = readState()
        val credentials = prefs.getString(KEY_CREDENTIALS, null)?.let {
            runCatching { json.decodeFromString<Map<String, StoredEncryptedToken>>(it) }.getOrNull()
        }.orEmpty()
        val aliases = prefs.getString(KEY_ALIASES, null)?.let {
            runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull()
        }.orEmpty()
        val pending = prefs.getString(KEY_PENDING, null)?.let {
            runCatching { json.decodeFromString<PendingLegacyRecord>(it) }.getOrNull()
        }
        return StoredSnapshot(state, credentials, aliases, pending)
    }

    private fun readState(): HostRegistryState {
        val parsed = prefs.getString(KEY_STATE, null)?.let {
            runCatching { json.decodeFromString<HostRegistryState>(it) }.getOrNull()
        } ?: return EMPTY_STATE
        val ids = parsed.profiles.map { it.hostId }
        if (ids.any { it.isBlank() } || ids.toSet().size != ids.size) return EMPTY_STATE
        val nextProfile = maxOf(
            parsed.nextProfileGeneration,
            (parsed.profiles.maxOfOrNull { it.profileGeneration } ?: 0L) + 1L,
            1L,
        )
        val nextRevision = maxOf(
            parsed.nextConnectionRevision,
            (parsed.profiles.maxOfOrNull { it.connectionRevision } ?: 0L) + 1L,
            1L,
        )
        return parsed.copy(nextProfileGeneration = nextProfile, nextConnectionRevision = nextRevision)
    }

    private fun encryptTokenOrThrow(hostLabel: String, token: String?): StoredEncryptedToken {
        val clean = token?.trim() ?: error("Missing token for $hostLabel")
        require(clean.isNotBlank()) { "Token must not be blank" }
        val encrypted = runCatching { cipher.encrypt(clean.toByteArray(Charsets.UTF_8)) }
            .getOrElse { throw IllegalStateException("Could not encrypt credentials for $hostLabel", it) }
        return encrypted.toStored()
    }

    private fun encryptPendingOrThrow(
        baseUrl: String,
        token: String,
        exposure: ExposureKind,
        createdAtMs: Long,
    ): PendingLegacyRecord = PendingLegacyRecord(
        baseUrl = baseUrl,
        exposure = exposure,
        encryptedToken = encryptTokenOrThrow("legacy", token),
        createdAtMs = createdAtMs,
    )

    private fun EncryptedValue.toStored(): StoredEncryptedToken = StoredEncryptedToken(
        ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        iv = Base64.getEncoder().encodeToString(iv),
    )

    private fun StoredEncryptedToken.toCredentials(
        baseUrl: String,
        exposure: ExposureKind,
        cipher: ConnectionCipher,
    ): HostCredentials? = runCatching {
        val value = EncryptedValue(
            ciphertext = Base64.getDecoder().decode(ciphertext),
            iv = Base64.getDecoder().decode(iv),
        )
        val token = String(cipher.decrypt(value), Charsets.UTF_8)
        token.takeIf { it.isNotBlank() }?.let { HostCredentials(baseUrl, it, exposure) }
    }.getOrNull()

    private fun PendingLegacyRecord.toCredentials(): HostCredentials? =
        encryptedToken.toCredentials(baseUrl, exposure, cipher)

    private fun requireHostId(value: String): String =
        cleanHostIdOrNull(value) ?: error("Host id must be nonblank")

    private fun cleanHostIdOrNull(value: String): String? = value.trim().takeIf { it.isNotEmpty() }

    private fun ensureNotPendingCleanup(state: HostRegistryState, hostId: String) {
        require(hostId !in state.pendingCleanupHostIds) {
            "Host cleanup is still pending: $hostId"
        }
    }

    private fun requireAlias(value: String): String = value.trim().takeIf { it.isNotEmpty() }
        ?: error("Alias must be nonblank")

    private fun replace(profiles: List<HostProfile>, updated: HostProfile): List<HostProfile> =
        profiles.map { if (it.hostId == updated.hostId) updated else it }

    companion object {
        const val FILE = "scoutr_host_registry"
        const val KEY_STATE = "state"
        const val KEY_CREDENTIALS = "credentials"
        const val KEY_ALIASES = "aliases"
        const val KEY_PENDING = "pendingLegacyRecord"

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        private val EMPTY_STATE = HostRegistryState()
    }
}

/**
 * Canonicalizes a base URL before it is persisted. Only HTTP(S) origins plus
 * an optional path are accepted; default ports and trailing slashes are
 * removed, while non-default ports remain visible to the user.
 */
fun normalizeBaseUrl(raw: String): String {
    val uri = runCatching { URI(raw.trim()) }.getOrElse {
        throw IllegalArgumentException("Invalid bridge URL", it)
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https") { "Bridge URL must use http or https" }
    require(uri.rawQuery == null && uri.rawFragment == null) { "Bridge URL must not contain query or fragment" }
    require(uri.userInfo == null) { "Bridge URL must not contain user information" }
    val host = uri.host?.lowercase(Locale.ROOT)
        ?: throw IllegalArgumentException("Bridge URL must contain a host")
    val port = uri.port
    require(port == -1 || port in 1..65535) { "Bridge URL port is invalid" }
    val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
    val authorityHost = if (host.contains(':')) "[$host]" else host
    val authority = authorityHost + if (port != -1 && !defaultPort) ":$port" else ""
    val path = uri.rawPath.orEmpty().trimEnd('/')
    return "$scheme://$authority$path"
}

/** Alias used for a newly seen identity unless a remembered alias exists. */
fun deriveHostAlias(baseUrl: String): String {
    val uri = URI(normalizeBaseUrl(baseUrl))
    val host = uri.host ?: error("Bridge URL must contain a host")
    val port = uri.port
    val defaultPort = (uri.scheme == "http" && port == 80) || (uri.scheme == "https" && port == 443)
    return host + if (port != -1 && !defaultPort) ":$port" else ""
}
