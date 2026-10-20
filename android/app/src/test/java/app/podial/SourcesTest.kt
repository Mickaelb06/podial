package app.podial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourcesTest {
    private val json = """{"genres":[
        {"id":1489,"parent":0,"en":"News","fr":"Actualités"},
        {"id":1526,"parent":1489,"en":"Daily News","fr":"Actus du jour"},
        {"id":1527,"parent":1489,"en":"Politics"},
        {"id":1301,"parent":0,"en":"Arts"}],
      "sources":[{"name":"BBC Radio 4","country":"GB","artistId":2},{"name":"RTL","country":"FR","artistId":1},{"name":"France Inter","country":"FR","artistId":3},{"name":"Sud Radio","country":"FR"}],
      "local":{"sources":["Sud Radio"],"genre":1526}}"""

    @Test fun readsGenresWithTheirTranslations() {
        val c = SourceCatalog.parse(json)
        assertEquals(listOf(1489, 1301), c.topLevel().map { it.id })
        assertEquals(listOf(1526, 1527), c.children(1489).map { it.id })
        assertEquals("Actus du jour", c.genre(1526)!!.name("fr"))
        assertEquals("Politics", c.genre(1527)!!.name("fr"))   // pas de traduction : repli sur l'anglais
        assertNull(c.genre(9999))
    }

    @Test fun userCountryComesFirst() {
        val c = SourceCatalog.parse(json)
        assertEquals(listOf("FR", "GB"), c.byCountry("FR").map { it.first })
        assertEquals(listOf("GB", "FR"), c.byCountry("GB").map { it.first })
        assertEquals(3, c.byCountry("FR").first().second.size)
        assertEquals(0L, c.sources.first { it.name == "Sud Radio" }.artistId)   // pas d'identifiant : cherché par son nom
        assertEquals(1526, c.local.genre)
        assertEquals(listOf("Sud Radio"), c.local.sources)
    }

    /** Le fichier livré avec les applications doit être lisible, et sa copie du site identique. */
    @Test fun shippedFileIsValidAndCopiesAreIdentical() {
        val shared = File("../../shared/data/podcast_sources.json")
        if (!shared.exists()) return   // lancé hors du dépôt
        val c = SourceCatalog.parse(shared.readText())
        assertTrue(c.genres.size >= 100)
        assertTrue(c.sources.isNotEmpty())
        assertTrue(c.sources.all { it.artistId >= 0 && it.country.length == 2 && it.name.isNotBlank() })
        assertTrue("des éditeurs sans identifiant doivent être cherchés par nom", c.sources.any { it.artistId == 0L })
        assertNotNull(c.genre(c.local.genre))
        assertTrue("radios locales inconnues", c.local.sources.all { n -> c.sources.any { it.name == n } })
        assertTrue("sources en double", c.sources.map { it.country to it.name.lowercase() }.toSet().size == c.sources.size)
        assertTrue("identifiants en double", c.sources.filter { it.artistId > 0 }.map { it.artistId }.let { ids -> ids.toSet().size == ids.size })
        for (copy in listOf("../../docs/app/podcast_sources.json", "../../desktop/podcast_sources.json"))
            assertEquals("$copy diffère de shared/data", shared.readText(), File(copy).readText())
    }
}
