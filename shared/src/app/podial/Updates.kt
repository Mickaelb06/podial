package app.podial

/** Logique commune « nouveaux épisodes ». `known` vaut null tant que le podcast n'a jamais été vu : rien n'est alors « nouveau ». */
object Updates {
    fun fresh(episodes: List<Episode>, known: Set<String>?, played: Set<String>): List<Episode> =
        if (known == null) emptyList() else episodes.filter { it.url !in known && it.url !in played }

    /** Un épisode écouté à plus de 92 % est considéré comme terminé. */
    fun isFinished(positionMs: Long, durationMs: Long) = durationMs > 0 && positionMs.toDouble() / durationMs > 0.92
}
