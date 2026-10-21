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
        {"id":"jazz","en":"Jazz","tags":["jazz"]}],
      "brands":[{"name":"NRJ","country":"FR","q":"NRJ"},{"name":"Rire et Chansons","country":"FR","q":"Rire et Chansons|Rire & Chansons"}]}"""

    @Test fun readsGenresAndSubGenres() {
        val c = RadioCatalog.parse(json)
        assertEquals(listOf("pop", "jazz"), c.genres.map { it.id })
        assertEquals(listOf("top40"), c.genre("pop")!!.children.map { it.id })
        assertEquals(listOf("top 40", "hits"), c.genre("top40")!!.tags)   // un sous-genre se trouve aussi par son identifiant
        assertEquals("Top 40", c.genre("top40")!!.name("fr"))
        assertEquals("Jazz", c.genre("jazz")!!.name("de"))   // pas de traduction : repli sur l'anglais
        assertEquals("pop", c.parentOf("top40")!!.id)
        assertNull(c.genre("inconnu"))
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
        for (copy in listOf("../../docs/app/radio_catalog.json", "../../desktop/radio_catalog.json"))
            assertEquals("$copy diffère de shared/data", shared.readText(), File(copy).readText())
    }
}
