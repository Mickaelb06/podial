package app.podial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedResolveTest {
    @Test fun readsFeedUrlFromApplePage() {
        val html = """<script>{"id":"1","attributes":{"name":"Journal","feedUrl":"https://radiofrance-podcast.net/podcast09/abc/podcast_def.xml","x":1}}</script>"""
        assertEquals("https://radiofrance-podcast.net/podcast09/abc/podcast_def.xml", Api.feedUrlIn(html))
    }

    @Test fun unescapesJsonSlashesAndAmpersands() =
        assertEquals("https://a.fr/f?x=1&y=2", Api.feedUrlIn("""{"feedUrl":"https:\/\/a.fr\/f?x=1&y=2"}"""))

    @Test fun missingOrBrokenFeedGivesNull() {
        assertNull(Api.feedUrlIn("<html>rien</html>"))
        assertNull(Api.feedUrlIn("""{"feedUrl":"ftp://bizarre"}"""))
        assertNull(Api.feedUrlIn("""{"feedUrl":"https://coupé"""))
    }
}
