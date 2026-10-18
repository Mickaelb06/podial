package app.podial

import java.util.Locale

/** Textes de l'interface (anglais par défaut, français si la langue du système est le français). */
object T {
    private val fr = Locale.getDefault().language == "fr"
    private val en = mapOf(
        "podcasts" to "Podcasts", "radios" to "Radios", "library" to "Library", "downloads" to "Downloads",
        "search_podcasts" to "Search podcasts…", "search_radios" to "Search radio stations…", "all" to "All",
        "top10" to "Top 10", "live" to "LIVE", "subscribe" to "Subscribe", "subscribed" to "Subscribed",
        "add_favorite" to "Add to favorites", "remove_favorite" to "Remove from favorites", "no_results" to "No results",
        "load_failed" to "Couldn't load. Check your connection.",
        "library_empty" to "Your library is empty. Subscribe to a podcast or add a station to your favorites.",
        "followed" to "Followed podcasts", "favorite_radios" to "Favorite radio stations",
        "no_downloads" to "No downloaded episodes. Press the download button next to an episode to listen offline.",
        "download" to "Download", "cancel_download" to "Cancel download", "remove_download" to "Remove download",
        "downloaded" to "Downloaded", "sleep" to "Sleep timer", "minutes" to "%d minutes", "one_hour" to "1 hour",
        "two_hours" to "2 hours", "end_episode" to "End of episode", "cancel_timer" to "Cancel timer",
        "play" to "Play", "pause" to "Pause", "back" to "Back", "nothing" to "Nothing playing",
        "stream_error" to "This stream can't be played here.",
        "new_badge" to "%d new", "new_chip" to "NEW", "mark_played" to "Mark as played", "mark_unplayed" to "Mark as unplayed",
    )
    private val frMap = mapOf(
        "library" to "Bibliothèque", "downloads" to "Téléchargements", "search_podcasts" to "Rechercher un podcast…",
        "search_radios" to "Rechercher une radio…", "all" to "Tous", "live" to "DIRECT", "subscribe" to "S'abonner",
        "subscribed" to "Abonné", "add_favorite" to "Ajouter aux favoris", "remove_favorite" to "Retirer des favoris",
        "no_results" to "Aucun résultat", "load_failed" to "Chargement impossible. Vérifie ta connexion.",
        "library_empty" to "Ta bibliothèque est vide. Abonne-toi à un podcast ou ajoute une radio aux favoris.",
        "followed" to "Podcasts suivis", "favorite_radios" to "Radios favorites",
        "no_downloads" to "Aucun épisode téléchargé. Appuie sur le bouton de téléchargement à côté d'un épisode pour l'écouter hors ligne.",
        "download" to "Télécharger", "cancel_download" to "Annuler le téléchargement", "remove_download" to "Supprimer le téléchargement",
        "downloaded" to "Téléchargé", "sleep" to "Minuteur de sommeil", "one_hour" to "1 heure", "two_hours" to "2 heures",
        "end_episode" to "Fin de l'épisode", "cancel_timer" to "Annuler le minuteur", "play" to "Lecture", "back" to "Retour",
        "nothing" to "Rien en lecture", "stream_error" to "Ce flux ne peut pas être lu ici.",
        "new_badge" to "%d nouveau(x)", "new_chip" to "NOUVEAU", "mark_played" to "Marquer comme écouté",
        "mark_unplayed" to "Marquer comme non écouté",
    )
    operator fun get(k: String): String = (if (fr) frMap[k] else null) ?: en[k] ?: k
}
