package app.podial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorldTest {
    private val countries = World.parse(File("../../shared/data/world.json").readText())

    @Test fun hitsKnownPlaces() {
        assertTrue(countries.size > 150)
        assertEquals("FR", World.hit(countries, 2.3f, 48.8f))      // Paris
        assertEquals("JP", World.hit(countries, 139.7f, 35.7f))    // Tokyo
        assertEquals("US", World.hit(countries, -100f, 40f))
        assertEquals("LS", World.hit(countries, 28f, -29.6f))      // le Lesotho est un trou dans l'Afrique du Sud
        assertEquals("ZA", World.hit(countries, 25f, -30f))
    }

    @Test fun seaIsNobody() { assertEquals(null, World.hit(countries, -30f, 30f)) }
}
