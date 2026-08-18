package dev.scoutr.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The QR pairing payload printed by `scoutr-bridge pair`.
 * Mirrors bridge/src/pairing.ts — keep the two in sync.
 *
 * v1 is the Tailscale contract already in the field; v2 carries an explicit
 * exposure kind for the providers that did not exist when v1 shipped. Both
 * versions produce the same connection: [exposure] is metadata, never a
 * transport switch.
 */
data class PairingPayload(
    val host: String,
    val token: String,
    val exposure: ExposureKind,
    val ntfyUrl: String? = null,
    val ntfyTopic: String? = null,
)

object PairingPayloadParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns null for anything that is not a v1 or v2 payload with host +
     * token — and, for v2, a known exposure kind. A payload is accepted whole
     * or rejected whole; a half-understood pairing would connect somewhere the
     * user did not intend.
     */
    fun parse(raw: String): PairingPayload? {
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        val version = (obj["v"] as? JsonPrimitive)?.contentOrNull
        if (version != "1" && version != "2") return null
        val host = (obj["host"] as? JsonPrimitive)?.contentOrNull ?: return null
        val token = (obj["token"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (host.isEmpty() || token.isEmpty()) return null
        val exposure = if (version == "1") ExposureKind.Tailscale else parseV2Exposure(obj) ?: return null

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
        return PairingPayload(withScheme(host), token, exposure, ntfyUrl, ntfyTopic)
    }

    /**
     * v2 exists for the providers that pair without Tailscale discovery, so
     * only those kinds are accepted — the bridge never emits a v2 Tailscale
     * payload, and accepting one here would legitimize a shape it cannot
     * produce.
     */
    private fun parseV2Exposure(obj: JsonObject): ExposureKind? {
        val exposure = obj["exposure"] as? JsonObject ?: return null
        return when (ExposureKind.fromWire((exposure["kind"] as? JsonPrimitive)?.contentOrNull)) {
            ExposureKind.Cloudflare -> ExposureKind.Cloudflare
            ExposureKind.Custom -> ExposureKind.Custom
            else -> null
        }
    }

    /** The payload always carries a full URL; tolerate scheme-less hosts. */
    private fun withScheme(host: String): String =
        if (host.startsWith("http://") || host.startsWith("https://")) host else "https://$host"
}
