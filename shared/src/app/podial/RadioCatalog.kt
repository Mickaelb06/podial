package app.podial

import org.json.JSONObject
import java.util.Locale

/**
 * Données fixes des radios (fichier shared/data/radio_catalog.json, copié dans desktop/ et docs/app/) :
 * genres et sous-genres (chacun est une ou plusieurs étiquettes de l'annuaire radio-browser) et marques de radios
 * dont on regroupe les webradios (« NRJ » → NRJ, NRJ Hits, NRJ Dance…).
 */
class RadioGenre(val id: String, private val names: Map<String, String>, val tags: List<String>, val children: List<RadioGenre>) {
    /** Nom dans la langue demandée, sinon en anglais. */
    fun name(lang: String = Locale.getDefault().language): String = names[lang] ?: names["en"] ?: id
}

/** Une marque : le [name] affiché et les morceaux de nom [queries] cherchés dans l'annuaire du pays [country] (une station y figure si son nom en contient un). */
class RadioBrand(val name: String, val country: String, val queries: List<String>, val logo: String = "")

class RadioCatalog(val genres: List<RadioGenre>, val brands: List<RadioBrand>) {
    fun genre(id: String?): RadioGenre? = genres.firstOrNull { it.id == id } ?: genres.firstNotNullOfOrNull { p -> p.children.firstOrNull { it.id == id } }
    /** Genre de premier niveau qui contient [id] (ou [id] lui-même). */
    fun parentOf(id: String?): RadioGenre? = genres.firstOrNull { p -> p.id == id || p.children.any { it.id == id } }
    fun brands(country: String): List<RadioBrand> = brands.filter { it.country == country }

    /** Marques de tous les pays : celui demandé d'abord, puis les autres par ordre alphabétique de leur nom. */
    fun byCountry(first: String): List<Pair<String, List<RadioBrand>>> =
        brands.groupBy { it.country }.toList().sortedWith(compareBy({ if (it.first == first) 0 else 1 }, { World.name(it.first) }))

    companion object {
        private fun genre(o: JSONObject): RadioGenre = RadioGenre(
            o.getString("id"),
            o.keys().asSequence().filter { it != "id" && it.length == 2 }.associateWith { o.getString(it) },
            o.getJSONArray("tags").let { t -> (0 until t.length()).map { t.getString(it) } },
            o.optJSONArray("children")?.let { c -> (0 until c.length()).map { genre(c.getJSONObject(it)) } } ?: emptyList())

        fun parse(json: String): RadioCatalog {
            val o = JSONObject(json)
            val genres = o.getJSONArray("genres").let { a -> (0 until a.length()).map { genre(a.getJSONObject(it)) } }
            val brands = o.getJSONArray("brands").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { b -> RadioBrand(b.getString("name"), b.getString("country"), b.getString("q").split("|"), b.optString("logo")) } }
            }
            return RadioCatalog(genres, brands)
        }
    }
}
