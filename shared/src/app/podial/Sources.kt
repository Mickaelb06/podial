package app.podial

import org.json.JSONObject
import java.util.Locale

/**
 * Données fixes du catalogue de podcasts (fichier shared/data/podcast_sources.json, copié dans desktop/ et docs/app/) :
 * tous les genres et sous-genres d'Apple avec leur nom dans plusieurs langues, les radios et éditeurs dont on liste les podcasts,
 * et les radios locales dont on garde les flashs d'info.
 */
class Genre(val id: Int, val parent: Int, private val names: Map<String, String>) {
    /** Nom dans la langue demandée, sinon en anglais. */
    fun name(lang: String = Locale.getDefault().language): String = names[lang] ?: names["en"] ?: id.toString()
}

/**
 * Une radio ou un éditeur de presse dont on liste tous les podcasts. Par identifiant d'éditeur iTunes quand Apple en donne un (jusqu'à 200 podcasts) ;
 * sinon artistId vaut 0 et on cherche le nom exact de l'éditeur (jusqu'à 100 podcasts).
 */
class Source(val name: String, val country: String, val artistId: Long = 0)

/** Flashs d'info locaux : podcasts du genre [genre] (Actus du jour) des radios locales listées dans [sources] (noms de sources). */
class Local(val sources: List<String>, val genre: Int)

class SourceCatalog(val genres: List<Genre>, val sources: List<Source>, val local: Local) {
    fun topLevel(): List<Genre> = genres.filter { it.parent == 0 }
    fun children(parent: Int): List<Genre> = genres.filter { it.parent == parent }
    fun genre(id: Int): Genre? = genres.firstOrNull { it.id == id }

    /** Sources du pays demandé d'abord, puis les autres pays par ordre alphabétique de leur nom. */
    fun byCountry(first: String): List<Pair<String, List<Source>>> =
        sources.groupBy { it.country }.toList().sortedWith(compareBy({ if (it.first == first) 0 else 1 }, { World.name(it.first) }))

    companion object {
        fun parse(json: String): SourceCatalog {
            val o = JSONObject(json)
            val genres = o.getJSONArray("genres").let { a ->
                (0 until a.length()).map { i ->
                    val g = a.getJSONObject(i)
                    Genre(g.getInt("id"), g.optInt("parent", 0), g.keys().asSequence().filter { it != "id" && it.length == 2 }.associateWith { g.getString(it) })
                }
            }
            val sources = o.getJSONArray("sources").let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { Source(it.getString("name"), it.getString("country"), it.optLong("artistId", 0)) } }
            }
            val l = o.getJSONObject("local")
            return SourceCatalog(genres, sources, Local(l.getJSONArray("sources").let { t -> (0 until t.length()).map { t.getString(it) } }, l.getInt("genre")))
        }
    }
}
