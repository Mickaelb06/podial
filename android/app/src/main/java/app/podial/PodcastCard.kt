package app.podial

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Partage d'un podcast par la feuille de partage d'Android (le lien lui-même vient de [ShareLink], commun aux versions bureau). */
object PodcastCard {
    suspend fun shareLink(ctx: Context, item: Item) {
        val text = ShareLink.text(item, withContext(Dispatchers.IO) { ShareLink.resolve(item) })
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }
}
