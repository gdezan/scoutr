package dev.scoutr.app.data

import java.nio.charset.StandardCharsets
import java.util.Base64

private const val SESSION_KEY_PREFIX = "sk1"
private const val HOST_SESSION_KEY_PREFIX = "hsk1"
private const val HOST_PROFILE_KEY_PREFIX = "hpk1"
private const val HOST_PANE_KEY_PREFIX = "hpn1"

/**
 * A session identity pinned to one bridge installation. Device-local metadata
 * (pins, archives) is stored under this identity so two bridges exposing the
 * same (agentKind, path) can never share entries.
 */
data class HostSessionKey(
    val hostId: String,
    val session: SessionKey,
)

/** A generation-qualified identity for a paired host profile. */
data class HostProfileKey(
    val hostId: String,
    val profileGeneration: Long,
)

/** A pane identity that cannot collide with another host or a re-paired profile. */
data class HostPaneKey(
    val profile: HostProfileKey,
    val paneId: String,
)

/** Versioned, path-safe encoding used by navigation and on-device preferences. */
fun SessionKey.encode(): String = listOf(
    SESSION_KEY_PREFIX,
    encodeSessionKeyPart(agentKind),
    encodeSessionKeyPart(path),
).joinToString(".")

/** Decode a canonical key; legacy raw paths and malformed values return null. */
fun decodeSessionKey(value: String): SessionKey? =
    decodeHostSessionKey(value)?.takeIf { it.hostId.isEmpty() }?.session

/** Host-qualified persisted spelling used by device-local session metadata. */
fun HostSessionKey.encode(): String = listOf(
    HOST_SESSION_KEY_PREFIX,
    encodeSessionKeyPart(hostId),
    encodeSessionKeyPart(session.agentKind),
    encodeSessionKeyPart(session.path),
).joinToString(".")

/** Generation-qualified spelling used by routes, intents, and transient state. */
fun HostProfileKey.encode(): String = listOf(
    HOST_PROFILE_KEY_PREFIX,
    encodeSessionKeyPart(hostId),
    profileGeneration.toString(),
).joinToString(".")

/** Generation-qualified pane spelling used by notifications and actions. */
fun HostPaneKey.encode(): String = listOf(
    HOST_PANE_KEY_PREFIX,
    encodeSessionKeyPart(profile.hostId),
    profile.profileGeneration.toString(),
    encodeSessionKeyPart(paneId),
).joinToString(".")

fun decodeHostProfileKey(value: String): HostProfileKey? {
    val parts = value.split('.')
    if (parts.size != 3 || parts[0] != HOST_PROFILE_KEY_PREFIX) return null
    return runCatching {
        val generation = parts[2].toLong()
        HostProfileKey(
            hostId = decodeSessionKeyPart(parts[1]),
            profileGeneration = generation,
        )
    }.getOrNull()?.takeIf { it.hostId.isNotBlank() && it.profileGeneration > 0 }
}

fun decodeHostPaneKey(value: String): HostPaneKey? {
    val parts = value.split('.')
    if (parts.size != 4 || parts[0] != HOST_PANE_KEY_PREFIX) return null
    return runCatching {
        val generation = parts[2].toLong()
        HostPaneKey(
            profile = HostProfileKey(
                hostId = decodeSessionKeyPart(parts[1]),
                profileGeneration = generation,
            ),
            paneId = decodeSessionKeyPart(parts[3]),
        )
    }.getOrNull()?.takeIf {
        it.profile.hostId.isNotBlank() && it.profile.profileGeneration > 0 && it.paneId.isNotBlank()
    }
}
/**
 * The persisted identity of a catalog entry: host-qualified when the device
 * knows its bridge, legacy sk1 otherwise. Decoding either spelling yields a
 * [HostSessionKey]; [hostId] is empty for sk1 entries.
 */
fun decodeHostSessionKey(value: String): HostSessionKey? {
    val parts = value.split('.')
    return when {
        parts.size == 4 && parts[0] == HOST_SESSION_KEY_PREFIX ->
            runCatching {
                HostSessionKey(
                    hostId = decodeSessionKeyPart(parts[1]),
                    session = SessionKey(
                        agentKind = decodeSessionKeyPart(parts[2]),
                        path = decodeSessionKeyPart(parts[3]),
                    ),
                )
            }.getOrNull()?.takeIf { it.hostId.isNotBlank() && validSession(it.session) }

        parts.size == 3 && parts[0] == SESSION_KEY_PREFIX ->
            runCatching {
                HostSessionKey(
                    hostId = "",
                    session = SessionKey(
                        agentKind = decodeSessionKeyPart(parts[1]),
                        path = decodeSessionKeyPart(parts[2]),
                    ),
                )
            }.getOrNull()?.takeIf { validSession(it.session) }

        else -> null
    }
}

private fun validSession(session: SessionKey) = session.agentKind.isNotBlank() && session.path.isNotBlank()

private fun encodeSessionKeyPart(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeSessionKeyPart(value: String): String =
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
