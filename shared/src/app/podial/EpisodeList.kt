package app.podial

enum class EpSort { NEWEST, OLDEST }
enum class EpFilter { ALL, DOWNLOADED, UNFINISHED }

/** Tri et filtres de la liste d'épisodes d'un podcast. */
object EpisodeList {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Date d'un flux RSS (« Tue, 20 Oct 2026 ») en nombre de jours, ou null si on ne la comprend pas. */
    internal fun day(date: String): Long? {
        val m = Regex("(\\d{1,2})\\s+([A-Za-z]{3})[a-z]*\\s+(\\d{4})").find(date) ?: return null
        val month = months.indexOf(m.groupValues[2].lowercase()).takeIf { it >= 0 } ?: return null
        return runCatching { java.time.LocalDate.of(m.groupValues[3].toInt(), month + 1, m.groupValues[1].toInt()).toEpochDay() }.getOrNull()   // 31 février : illisible
    }

    /**
     * [filter] : tous, seulement ceux téléchargés (adresses dans [downloaded]), ou ceux pas encore terminés (adresses absentes de [played]).
     * [sort] : par date de publication ; si une date est illisible on garde l'ordre du flux (récents d'abord, comme la plupart des flux).
     */
    fun apply(eps: List<Episode>, sort: EpSort, filter: EpFilter, downloaded: Set<String>, played: Set<String>): List<Episode> {
        val kept = when (filter) {
            EpFilter.ALL -> eps
            EpFilter.DOWNLOADED -> eps.filter { it.url in downloaded }
            EpFilter.UNFINISHED -> eps.filter { it.url !in played }
        }
        val days = kept.map { day(it.date) }
        val newestFirst = if (days.all { it != null }) kept.withIndex().sortedWith(compareByDescending<IndexedValue<Episode>> { days[it.index]!! }.thenBy { it.index }).map { it.value } else kept
        return if (sort == EpSort.NEWEST) newestFirst else newestFirst.reversed()
    }
}
