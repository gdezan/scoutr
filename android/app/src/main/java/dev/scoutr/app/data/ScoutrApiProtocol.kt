package dev.scoutr.app.data

/**
 * Oldest Scoutr API protocol this Android build can use. Additive optional
 * fields do not move this range; required shape or semantic breaks do.
 */
const val MIN_SCOUTR_API_PROTOCOL = 1

/** Newest Scoutr API protocol this Android build can use. */
const val MAX_SCOUTR_API_PROTOCOL = 1

/** Result of classifying a bridge health response against this app's supported range. */
sealed interface ScoutrApiCompatibility {
    data object Compatible : ScoutrApiCompatibility
    data class Incompatible(val bridgeProtocol: Int?) : ScoutrApiCompatibility
}

/** Classifies missing and out-of-range bridge API protocols as incompatible. */
fun classifyScoutrApiCompatibility(info: ScoutrApiInfo?): ScoutrApiCompatibility {
    val protocol = info?.protocol
    return if (protocol != null && protocol in MIN_SCOUTR_API_PROTOCOL..MAX_SCOUTR_API_PROTOCOL) {
        ScoutrApiCompatibility.Compatible
    } else {
        ScoutrApiCompatibility.Incompatible(protocol)
    }
}

/** Builds actionable copy for a bridge outside this app's Scoutr API range. */
fun formatScoutrApiIncompatibility(incompatible: ScoutrApiCompatibility.Incompatible): String {
    val supported = if (MIN_SCOUTR_API_PROTOCOL == MAX_SCOUTR_API_PROTOCOL) {
        "protocol $MIN_SCOUTR_API_PROTOCOL"
    } else {
        "protocols $MIN_SCOUTR_API_PROTOCOL–$MAX_SCOUTR_API_PROTOCOL"
    }
    return incompatible.bridgeProtocol?.let { protocol ->
        "Scoutr API mismatch: bridge protocol $protocol; app supports $supported. " +
            "Update the app or deploy a matching bridge."
    } ?: "This bridge does not advertise a Scoutr API protocol. " +
        "Update or deploy the bridge; this app supports $supported."
}
