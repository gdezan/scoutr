package dev.scoutr.app.data

/**
 * Oldest Scoutr API protocol this Android build can use. Additive optional
 * fields do not move this range; required shape or semantic breaks do.
 */
const val MIN_SCOUTR_API_PROTOCOL = 2

/** Newest Scoutr API protocol this Android build can use. */
const val MAX_SCOUTR_API_PROTOCOL = 2

/**
 * Advertised capabilities this build cannot work without. `commands.http.v1`
 * is required rather than probed: one-shot session commands are HTTP now, and
 * silently dropping back to the bridge's legacy WebSocket command frames would
 * hide a half-finished deployment instead of naming it.
 */
val REQUIRED_SCOUTR_API_FEATURES = listOf("commands.http.v1")

/** Result of classifying a bridge health response against this app's supported range. */
sealed interface ScoutrApiCompatibility {
    data object Compatible : ScoutrApiCompatibility
    data class Incompatible(
        val bridgeProtocol: Int?,
        /** Required features this bridge does not advertise; empty for a protocol mismatch. */
        val missingFeatures: List<String> = emptyList(),
    ) : ScoutrApiCompatibility
}

/**
 * Classifies missing and out-of-range bridge API protocols as incompatible,
 * then the same for a bridge inside the range that is missing a required
 * capability.
 */
fun classifyScoutrApiCompatibility(info: ScoutrApiInfo?): ScoutrApiCompatibility {
    val protocol = info?.protocol
    if (protocol == null || protocol !in MIN_SCOUTR_API_PROTOCOL..MAX_SCOUTR_API_PROTOCOL) {
        return ScoutrApiCompatibility.Incompatible(protocol)
    }
    val missing = REQUIRED_SCOUTR_API_FEATURES.filterNot { it in info.features }
    return if (missing.isEmpty()) {
        ScoutrApiCompatibility.Compatible
    } else {
        ScoutrApiCompatibility.Incompatible(protocol, missing)
    }
}

/** Builds actionable copy for a bridge outside this app's Scoutr API range. */
fun formatScoutrApiIncompatibility(incompatible: ScoutrApiCompatibility.Incompatible): String {
    val supported = if (MIN_SCOUTR_API_PROTOCOL == MAX_SCOUTR_API_PROTOCOL) {
        "protocol $MIN_SCOUTR_API_PROTOCOL"
    } else {
        "protocols $MIN_SCOUTR_API_PROTOCOL–$MAX_SCOUTR_API_PROTOCOL"
    }
    if (incompatible.missingFeatures.isNotEmpty()) {
        return "This bridge is missing ${incompatible.missingFeatures.joinToString(", ")}. " +
            "Deploy a newer bridge; this app does not fall back to the old WebSocket command path."
    }
    return incompatible.bridgeProtocol?.let { protocol ->
        "Scoutr API mismatch: bridge protocol $protocol; app supports $supported. " +
            "Update the app or deploy a matching bridge."
    } ?: "This bridge does not advertise a Scoutr API protocol. " +
        "Update or deploy the bridge; this app supports $supported."
}
