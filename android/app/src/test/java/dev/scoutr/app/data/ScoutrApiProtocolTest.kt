package dev.scoutr.app.data

import org.junit.Assert.assertEquals
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
            classifyScoutrApiCompatibility(ScoutrApiInfo(protocol = MIN_SCOUTR_API_PROTOCOL)),
        )
        assertEquals(
            ScoutrApiCompatibility.Incompatible(bridgeProtocol = MAX_SCOUTR_API_PROTOCOL + 1),
            classifyScoutrApiCompatibility(ScoutrApiInfo(protocol = MAX_SCOUTR_API_PROTOCOL + 1)),
        )
    }
}
