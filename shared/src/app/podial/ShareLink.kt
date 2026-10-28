package app.podial

import org.json.JSONObject

/** Lien d'un podcast à donner à quelqu'un : le lecteur web de Podial sait ouvrir un podcast par son identifiant iTunes (#p=). */
object ShareLink {
    private const val SITE = "https://mickaelb06.github.io/podial/"

    /** Lien immédiat : précis si l'identifiant iTunes est encore dans l'adresse du flux, sinon la page d'accueil du site. */
    fun link(item: Item): String =
        if (item.feed.startsWith(Api.PENDING)) SITE + "app/#p=" + item.feed.removePrefix(Api.PENDING) else SITE

    fun text(item: Item, link: String = link(item)): String = "${item.name} — ${item.artist}\n$link"

    /** Podcast déjà suivi : son identifiant iTunes n'est plus connu, on le retrouve par une recherche dont le flux correspond (appel bloquant). */
    fun find(item: Item): String? = runCatching {
        val q = java.net.URLEncoder.encode(item.name, "UTF-8")
        val c = java.net.URL("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=15&term=$q").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        val r = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("results")
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            if (o.optString("feedUrl") == item.feed) return@runCatching SITE + "app/#p=" + o.getLong("collectionId")
        }
        null
    }.getOrNull()

    /** À appeler hors du fil d'affichage. */
    fun resolve(item: Item): String =
        if (item.feed.startsWith(Api.PENDING)) link(item) else find(item) ?: link(item)
}
