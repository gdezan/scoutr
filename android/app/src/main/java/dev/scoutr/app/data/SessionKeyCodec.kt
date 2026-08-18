package dev.scoutr.app.data

import java.nio.charset.StandardCharsets
import java.util.Base64

private const val SESSION_KEY_PREFIX = "sk1"

/** Versioned, path-safe encoding used by navigation and on-device preferences. */
fun SessionKey.encode(): String = listOf(
    SESSION_KEY_PREFIX,
    encodeSessionKeyPart(agentKind),
    encodeSessionKeyPart(path),
).joinToString(".")

/** Decode a canonical key; legacy raw paths and malformed values return null. */
fun decodeSessionKey(value: String): SessionKey? {
    val parts = value.split('.')
    if (parts.size != 3 || parts[0] != SESSION_KEY_PREFIX) return null
    return runCatching {
        SessionKey(
            agentKind = decodeSessionKeyPart(parts[1]),
            path = decodeSessionKeyPart(parts[2]),
        ).takeIf { it.agentKind.isNotBlank() && it.path.isNotBlank() }
    }.getOrNull()
}

private fun encodeSessionKeyPart(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeSessionKeyPart(value: String): String =
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
