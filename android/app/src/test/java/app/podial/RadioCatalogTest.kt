package app.podial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RadioCatalogTest {
    private val json = """{"genres":[
        {"id":"pop","en":"Pop","fr":"Pop","tags":["pop"],"children":[{"id":"top40","en":"Top 40","fr":"Top 40","tags":["top 40","hits"]}]},
        {"id":"jazz","en":"Jazz","tags":["jazz"]},
        {"id":"xmas","en":"Christmas","tags":["christmas"],"names":["Christmas","Xmas"],"world":true}],
      "brands":[{"name":"NRJ","country":"FR","q":"NRJ"},{"name":"Rire et Chansons","country":"FR","q":"Rire et Chansons|Rire & Chansons"}]}"""

    @Test fun readsGenresAndSubGenres() {
        val c = RadioCatalog.parse(json)
        assertEquals(listOf("pop", "jazz", "xmas"), c.genres.map { it.id })
        assertEquals(listOf("top40"), c.genre("pop")!!.children.map { it.id })
        assertEquals(listOf("top 40", "hits"), c.genre("top40")!!.tags)   // un sous-genre se trouve aussi par son identifiant
        assertEquals("Top 40", c.genre("top40")!!.name("fr"))
        assertEquals("Jazz", c.genre("jazz")!!.name("de"))   // pas de traduction : repli sur l'anglais
        assertEquals("pop", c.parentOf("top40")!!.id)
        assertNull(c.genre("inconnu"))
    }

    @Test fun worldThemesSearchEverywhereAndCanListStationNames() {
        val c = RadioCatalog.parse(json)
        assertEquals(true, c.genre("xmas")!!.world)
        assertEquals(listOf("Christmas", "Xmas"), c.genre("xmas")!!.names)
        assertEquals(false, c.genre("jazz")!!.world)   // un genre normal reste dans le pays choisi
        assertEquals(emptyList<String>(), c.genre("jazz")!!.names)
    }

    @Test fun brandsAreFilteredByCountryAndSplitIntoNames() {
        val c = RadioCatalog.parse(json)
        assertEquals(listOf("NRJ", "Rire et Chansons"), c.brands("FR").map { it.name })
        assertEquals(emptyList<RadioBrand>(), c.brands("DE"))
        assertEquals(listOf("Rire et Chansons", "Rire & Chansons"), c.brands("FR")[1].queries)
    }

    /** Le fichier livré doit être lisible, et ses copies (site, Linux, ~/podial) identiques. */
    @Test fun shippedFileIsValidAndCopiesAreIdentical() {
        val shared = File("../../shared/data/radio_catalog.json")
        if (!shared.exists()) return   // lancé hors du dépôt
        val c = RadioCatalog.parse(shared.readText())
        assertTrue(c.genres.size >= 10)
        assertTrue(c.genres.all { it.tags.isNotEmpty() || it.children.isNotEmpty() })
        assertTrue("des marques par pays", c.brands.map { it.country }.toSet().size >= 10)
        assertTrue("marques sans nom de recherche", c.brands.all { it.queries.all { q -> q.isNotBlank() } })
        assertNotNull(c.genre("jazz"))
        assertTrue("judaïsme dans les radios religieuses", c.genre("jewish")?.let { it.world && it.tags.isNotEmpty() } == true)
        assertTrue("thèmes Noël, Halloween, Hanouka", listOf("christmas", "halloween", "hanukkah").all { c.genre(it)?.world == true })
        assertTrue("RMC 2 doit être sous Radio Monte Carlo (Italie), pas sous RMC (France)",
            c.brands("IT").any { b -> b.queries.any { "RMC" in it } } && c.brands("FR").none { b -> b.queries.any { "Monte Carlo" in it } })
        for (copy in listOf("../../docs/app/radio_catalog.json", "../../desktop/radio_catalog.json"))
            assertEquals("$copy diffère de shared/data", shared.readText(), File(copy).readText())
    }
}
