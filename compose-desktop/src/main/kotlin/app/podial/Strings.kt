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
        "about" to "About", "version" to "Version %s", "about_text" to "Podcasts and live radio in one free app. No account, no tracking.",
        "about_sources" to "Podcast data: public iTunes Search API and Apple RSS charts. Radio stations: radio-browser.info. Podial is not affiliated with Apple or YouTube.",
        "report_bug" to "Report a bug", "check_updates" to "Check for updates", "up_to_date" to "You're up to date (version %s).",
        "update_available" to "Version %s is available.", "update_failed" to "Couldn't check for updates.", "close" to "Close",
        "settings" to "Settings", "launch_login" to "Launch at login",
        "launch_login_desc" to "Starts Podial minimized when you log in.",
        "launch_login_unavailable" to "Available in the installed app (not when run from source).",
        "my_quotes" to "My quotes", "quote" to "Quote", "transcript" to "Transcript", "follow" to "Follow the audio",
        "transcript_hint" to "Click a line to listen from there. Click the quote icon to highlight it.",
        "no_transcript" to "This episode has no published transcript. Many shows do not provide one.",
        "no_quotes" to "No quotes yet. While listening, press Quote, or open the transcript and highlight a sentence.",
        "play_here" to "Play from here", "note" to "Note", "copy" to "Copy to share", "delete" to "Delete", "save" to "Save", "cancel" to "Cancel",
        "quote_saved" to "Quote saved at %s",
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
        "about" to "À propos", "version" to "Version %s", "about_text" to "Podcasts et radios en direct dans une seule app gratuite. Sans compte, sans pistage.",
        "about_sources" to "Données des podcasts : API publique iTunes Search et classements RSS d'Apple. Radios : radio-browser.info. Podial n'est affilié ni à Apple ni à YouTube.",
        "report_bug" to "Signaler un bug", "check_updates" to "Vérifier les mises à jour", "up_to_date" to "Podial est à jour (version %s).",
        "update_available" to "La version %s est disponible.", "update_failed" to "Vérification impossible.", "close" to "Fermer",
        "settings" to "Réglages", "launch_login" to "Lancer à l'ouverture de session",
        "launch_login_desc" to "Démarre Podial réduit à l'ouverture de session.",
        "launch_login_unavailable" to "Disponible dans l'application installée (pas depuis les sources).",
        "my_quotes" to "Mes citations", "quote" to "Citation", "transcript" to "Transcription", "follow" to "Suivre l'audio",
        "transcript_hint" to "Clique sur une ligne pour écouter à partir de là. Clique sur l'icône de citation pour la surligner.",
        "no_transcript" to "Cet épisode n'a pas de transcription publiée. Beaucoup d'émissions n'en proposent pas.",
        "no_quotes" to "Aucune citation. Pendant l'écoute, appuie sur Citation, ou ouvre la transcription et surligne une phrase.",
        "play_here" to "Écouter à partir d'ici", "note" to "Note", "copy" to "Copier pour partager", "delete" to "Supprimer", "save" to "Enregistrer", "cancel" to "Annuler",
        "quote_saved" to "Citation enregistrée à %s",
    )
    operator fun get(k: String): String = (if (fr) frMap[k] else null) ?: en[k] ?: k
}
