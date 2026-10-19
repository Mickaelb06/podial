package app.podial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasTest {
    @Test fun listedInItsOwnCountry() = assertEquals(listOf("RMC 2"), Extras.matching("IT", null).map { it.name })
    @Test fun notListedInOtherCountries() = assertTrue(Extras.matching("FR", null).isEmpty())
    @Test fun foundBySearchInAnyCountry() {
        assertEquals(listOf("RMC 2"), Extras.matching("FR", "rmc").map { it.name })
        assertEquals(listOf("RMC 2"), Extras.matching("FR", "RMC 2").map { it.name })
        assertTrue(Extras.matching("FR", "france inter").isEmpty())
    }
    @Test fun streamIsSecure() = assertTrue(Extras.matching("IT", null).single().stream.startsWith("https://"))
}
