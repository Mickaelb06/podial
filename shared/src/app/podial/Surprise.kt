package app.podial

import kotlin.random.Random

/** « Surprends-moi » : une radio au hasard dans un pays au hasard. */
object Surprise {
    /**
     * Tire des pays jusqu'à en trouver un qui a des radios (beaucoup de petits pays n'en ont aucune), puis une radio dans sa liste.
     * Rend le code du pays et la radio, ou null si rien n'a été trouvé (réseau coupé, par exemple).
     */
    suspend fun radio(
        codes: List<String>, random: Random = Random, tries: Int = 8,
        fetch: suspend (String) -> List<Item> = { Api.radios(it, limit = 30, withLogos = false) },
    ): Pair<String, Item>? {
        for (code in codes.shuffled(random).take(tries)) {
            val found = runCatching { fetch(code) }.getOrDefault(emptyList()).filter { it.stream.isNotEmpty() }
            if (found.isNotEmpty()) {
                val pick = found.random(random)
                return code to Logos.fill(listOf(pick)).first()
            }
        }
        return null
    }

    /** Un podcast au hasard : un genre au hasard (ou le classement général), puis un podcast de son classement dans le pays choisi. */
    suspend fun podcast(
        genres: List<Int>, random: Random = Random, tries: Int = 4,
        fetch: suspend (Int?) -> List<Item> = { Api.topPodcasts(it, limit = 40) },
    ): Item? {
        for (genre in (genres + listOf(null)).shuffled(random).take(tries)) {
            val found = runCatching { fetch(genre) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found.random(random)
        }
        return null
    }
}
