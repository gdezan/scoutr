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
        // The command routes are the required capability: an older bridge
        // still speaks protocol 2, so only the feature list separates a bridge
        // this app can drive from one it cannot. Falling back to the legacy WS
        // commands would hide exactly that.
        val incompatible = classifyScoutrApiCompatibility(
            ScoutrApiInfo(protocol = MIN_SCOUTR_API_PROTOCOL, features = listOf("terminal.v1")),
        )
        assertEquals(
            ScoutrApiCompatibility.Incompatible(
                bridgeProtocol = MIN_SCOUTR_API_PROTOCOL,
                missingFeatures = listOf("commands.http.v1"),
            ),
            incompatible,
        )
        assertTrue(
            formatScoutrApiIncompatibility(incompatible as ScoutrApiCompatibility.Incompatible)
                .contains("commands.http.v1"),
        )
    }
}
