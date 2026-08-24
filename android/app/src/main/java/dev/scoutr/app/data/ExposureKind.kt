package dev.scoutr.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which provider fronts the bridge's public URL. Pairing records it and
 * [ConnectionStore] persists it; nothing in the transport reads it. Every
 * network client still derives its URLs and headers from the saved base URL
 * and token alone, so an exposure kind never changes what goes on the wire.
 *
 * [wire] is the stable persisted/QR spelling — keep it in sync with
 * bridge/src/exposure.ts and bridge/src/pairing.ts.
 */
@Serializable
enum class ExposureKind(val wire: String) {
    @SerialName("tailscale")
    Tailscale("tailscale"),

    @SerialName("cloudflare")
    Cloudflare("cloudflare"),

    @SerialName("custom")
    Custom("custom");

    companion object {
        /** Returns null for an unknown or absent spelling; callers decide. */
        fun fromWire(value: String?): ExposureKind? = entries.firstOrNull { it.wire == value }
    }
}
