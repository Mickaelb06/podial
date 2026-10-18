package app.podial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunTest {
    // 20 mars 2026, 12:00 UTC : équinoxe, le Soleil est presque à la verticale du méridien de Greenwich
    private val noon = 1774008000000L

    @Test fun equinoxNoon() {
        val (dec, sub) = Sun.position(noon)
        assertEquals(0.0, dec, 1.0)
        assertEquals(0.0, sub, 4.0)
        assertTrue(Sun.altitude(0.0, 0.0, noon) > 80)
        assertTrue(Sun.altitude(0.0, 180.0, noon) < -80)
    }

    @Test fun nightBandsMatchAltitude() {
        for (ms in listOf(noon, noon + 5 * 3600000L, noon + 100 * 86400000L, noon + 200 * 86400000L)) {
            for (alt in listOf(0.0, -6.0, -12.0)) {
                val bands = Sun.bands(ms, alt, 1.0)
                for (lon in listOf(-170.0, -90.0, 0.0, 45.0, 120.0)) for (lat in listOf(-70.0, -30.0, 0.0, 25.0, 60.0, 80.0)) {
                    val dark = bands.any { lon + 0.5 >= it.lon0 && lon + 0.5 <= it.lon1 && lat >= it.lo && lat <= it.hi }
                    val expect = Sun.altitude(lat, lon + 0.5, ms) < alt
                    val margin = Math.abs(Sun.altitude(lat, lon + 0.5, ms) - alt)
                    if (margin > 1.5) assertEquals("ms=$ms alt=$alt lon=$lon lat=$lat", expect, dark)
                }
            }
        }
    }
}
