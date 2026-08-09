package dev.cockpit.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The QR pairing payload printed by `cockpit-bridge pair`.
 * Mirrors bridge/src/pairing.ts — keep the two in sync.
 */
data class PairingPayload(
    val host: String,
    val token: String,
    val ntfyUrl: String? = null,
    val ntfyTopic: String? = null,
)

object PairingPayloadParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null for anything that is not a v1 payload with host + token. */
    fun parse(raw: String): PairingPayload? {
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        if ((obj["v"] as? JsonPrimitive)?.contentOrNull != "1") return null
        val host = (obj["host"] as? JsonPrimitive)?.contentOrNull ?: return null
        val token = (obj["token"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (host.isEmpty() || token.isEmpty()) return null

        var ntfyUrl: String? = null
        var ntfyTopic: String? = null
        val ntfy = obj["ntfy"] as? JsonObject
        if (ntfy != null) {
            val url = (ntfy["url"] as? JsonPrimitive)?.contentOrNull
            val topic = (ntfy["topic"] as? JsonPrimitive)?.contentOrNull
            if (!url.isNullOrEmpty() && !topic.isNullOrEmpty()) {
                ntfyUrl = url
                ntfyTopic = topic
            }
        }
        return PairingPayload(host, token, ntfyUrl, ntfyTopic)
    }
}
