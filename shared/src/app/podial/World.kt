package app.podial

import org.json.JSONObject
import java.util.Locale

/** Contours des pays (Natural Earth 110m, domaine public) pour la carte du monde. Coordonnées : longitude et latitude en degrés. */
object World {
    const val LON_SPAN = 360f      // longitude -180..180
    const val LAT_TOP = 84f        // on coupe l'Antarctique : latitude 84..-58
    const val LAT_SPAN = 142f

    class Country(val code: String, val rings: List<FloatArray>) {
        val minX = rings.minOf { r -> (0 until r.size step 2).minOf { r[it] } }
        val maxX = rings.maxOf { r -> (0 until r.size step 2).maxOf { r[it] } }
        val minY = rings.minOf { r -> (1 until r.size step 2).minOf { r[it] } }
        val maxY = rings.maxOf { r -> (1 until r.size step 2).maxOf { r[it] } }

        /** Règle pair-impair sur tous les anneaux : les trous (ex. le Lesotho dans l'Afrique du Sud) sont gérés. */
        fun contains(lon: Float, lat: Float): Boolean {
            if (lon < minX || lon > maxX || lat < minY || lat > maxY) return false
            var inside = false
            for (r in rings) {
                var j = r.size / 2 - 1
                for (i in 0 until r.size / 2) {
                    val xi = r[2 * i]; val yi = r[2 * i + 1]; val xj = r[2 * j]; val yj = r[2 * j + 1]
                    if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
            }
            return inside
        }
    }

    fun parse(json: String): List<Country> {
        val o = JSONObject(json)
        return o.keys().asSequence().map { code ->
            val a = o.getJSONArray(code)
            Country(code, (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                FloatArray(r.length()) { k -> r.getInt(k) / 10f }
            })
        }.toList()
    }

    fun hit(countries: List<Country>, lon: Float, lat: Float): String? = countries.firstOrNull { it.contains(lon, lat) }?.code

    /** Nom du pays dans la langue du système. */
    fun name(code: String): String = Locale("", code).displayCountry.ifBlank { code }

    /** Drapeau en émoji (deux lettres régionales). */
    fun flag(code: String): String = code.uppercase().map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("")
}
