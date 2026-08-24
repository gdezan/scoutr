package dev.scoutr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutrApiProtocolTest {

    @Test
    fun onlyAdvertisedProtocolsInTheSupportedRangeAreCompatible() {
        assertEquals(
            ScoutrApiCompatibility.Incompatible(bridgeProtocol = null),
            classifyScoutrApiCompatibility(null),
        )
        assertEquals(
            ScoutrApiCompatibility.Incompatible(bridgeProtocol = null),
            classifyScoutrApiCompatibility(ScoutrApiInfo(protocol = null)),
        )
        assertEquals(
            ScoutrApiCompatibility.Incompatible(bridgeProtocol = MIN_SCOUTR_API_PROTOCOL - 1),
            classifyScoutrApiCompatibility(ScoutrApiInfo(protocol = MIN_SCOUTR_API_PROTOCOL - 1)),
        )
        assertEquals(
            ScoutrApiCompatibility.Compatible,
            classifyScoutrApiCompatibility(
                ScoutrApiInfo(protocol = MIN_SCOUTR_API_PROTOCOL, features = REQUIRED_SCOUTR_API_FEATURES),
            ),
        )
        assertEquals(
            ScoutrApiCompatibility.Incompatible(bridgeProtocol = MAX_SCOUTR_API_PROTOCOL + 1),
            classifyScoutrApiCompatibility(ScoutrApiInfo(protocol = MAX_SCOUTR_API_PROTOCOL + 1)),
        )
    }

    @Test
    fun aBridgeInsideTheRangeStillNeedsTheRequiredFeatures() {
        // The host identity, push-generation, and command routes are required
        // capabilities: an older bridge still speaks protocol 2, so only the
        // feature list separates a bridge this app can drive from one it cannot.
        val incompatible = classifyScoutrApiCompatibility(
            ScoutrApiInfo(protocol = MIN_SCOUTR_API_PROTOCOL, features = listOf("terminal.v1")),
        )
        assertEquals(
            ScoutrApiCompatibility.Incompatible(
                bridgeProtocol = MIN_SCOUTR_API_PROTOCOL,
                missingFeatures = REQUIRED_SCOUTR_API_FEATURES,
            ),
            incompatible,
        )
        assertTrue(
            formatScoutrApiIncompatibility(incompatible as ScoutrApiCompatibility.Incompatible)
                .contains("commands.http.v1"),
        )
    }
}
