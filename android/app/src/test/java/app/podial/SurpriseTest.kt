package app.podial

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class SurpriseTest {
    private fun radio(name: String) = Item("radio", name, "", "", stream = "https://s/$name")

    @Test fun skipsCountriesWithoutRadios() = runBlocking {
        val r = Surprise.radio(listOf("MT", "FR", "VA"), Random(1)) { if (it == "FR") listOf(radio("FIP")) else emptyList() }
        assertEquals("FR", r?.first); assertEquals("FIP", r?.second?.name)
    }

    @Test fun ignoresStationsWithoutStream() = runBlocking {
        val r = Surprise.radio(listOf("FR"), Random(1)) { listOf(Item("radio", "Vide", "", ""), radio("Ok")) }
        assertEquals("Ok", r?.second?.name)
    }

    @Test fun survivesNetworkErrorsAndGivesUp() = runBlocking {
        assertNull(Surprise.radio(listOf("FR", "IT"), Random(1)) { throw java.io.IOException("réseau coupé") })
        assertNull(Surprise.radio(emptyList(), Random(1)) { listOf(radio("X")) })
    }

    @Test fun onlyTriesTheGivenNumberOfCountries() = runBlocking {
        var calls = 0
        Surprise.radio(List(20) { "C$it" }, Random(1), tries = 3) { calls++; emptyList() }
        assertEquals(3, calls)
    }
}
