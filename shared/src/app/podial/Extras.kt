package app.podial

/**
 * Radios absentes de l'annuaire radio-browser.info, ajoutées à la main avec l'adresse de flux publiée par la station elle-même.
 * La même liste existe dans desktop/catalog.py (EXTRA_RADIOS) et docs/app/index.html (EXTRA_RADIOS) : à garder identique.
 */
object Extras {
    private data class Extra(val item: Item, val country: String)

    private val list = listOf(
        // Radio Monte Carlo 2 : flux lu sur https://www.rmc2.net/webradio/rmc2/
        Extra(Item("radio", "RMC 2", "Italia", "https://www.rmc2.net/wp-content/themes/rmc2-child/apple-icon-180x180.png",
            stream = "https://stream.rcs.revma.com/txdn8d324ucwv", homepage = "https://www.rmc2.net", quality = "MP3 · 128 kbps"), "IT"),
    )

    /** Radios fournies à ajouter : celles du pays affiché, et celles dont le nom correspond à la recherche (dans n'importe quel pays). */
    fun matching(country: String, name: String?): List<Item> {
        val q = name?.let(Api::stationKey).orEmpty()
        return list.filter { if (q.isEmpty()) it.country == country else Api.stationKey(it.item.name).contains(q) }.map { it.item }
    }
}
