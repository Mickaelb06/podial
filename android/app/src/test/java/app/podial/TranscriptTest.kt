package app.podial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    @Test fun json() {
        val c = Transcripts.parse("""{"version":"1.0.0","segments":[{"startTime":1.5,"endTime":3,"body":"שלום עולם"},{"startTime":4,"body":"你好"}]}""", "application/json")
        assertEquals(listOf(Cue(1500, "שלום עולם"), Cue(4000, "你好")), c)
    }

    @Test fun vtt() {
        val c = Transcripts.parse("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n<v Anna>Bonjour</v>\n\n01:02.500 --> 01:05.000\nمرحبا\nبكم\n", "text/vtt")
        assertEquals(listOf(Cue(1000, "Bonjour"), Cue(62500, "مرحبا بكم")), c)
    }

    @Test fun srt() {
        val c = Transcripts.parse("1\n00:00:02,000 --> 00:00:04,000\nこんにちは\n\n2\n01:00:00,250 --> 01:00:02,000\nline\n", "application/x-subrip")
        assertEquals(listOf(Cue(2000, "こんにちは"), Cue(3600250, "line")), c)
    }

    @Test fun html() {
        val c = Transcripts.parse("<html><style>p{}</style><body><p>Hello &amp; welcome</p><p>Second</p></body></html>", "text/html")
        assertEquals(listOf(Cue(-1, "Hello & welcome"), Cue(-1, "Second")), c)
    }

    @Test fun indexAt() {
        val c = listOf(Cue(0, "a"), Cue(5000, "b"), Cue(9000, "c"))
        assertEquals(1, Transcripts.indexAt(c, 6000)); assertEquals(2, Transcripts.indexAt(c, 99999)); assertEquals(-1, Transcripts.indexAt(listOf(Cue(-1, "x")), 5))
    }

    @Test fun feedTranscriptPrefersJson() {
        val xml = """<rss xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>T</title><item><title>E1</title>
            <enclosure url="https://x/a.mp3"/><podcast:transcript url="https://x/a.html" type="text/html"/>
            <podcast:transcript url="https://x/a.json" type="application/json"/><podcast:transcript url="https://x/a.srt" type="application/x-subrip"/></item></channel></rss>"""
        val ep = Api.parseFeed(xml.byteInputStream()).second.single()
        assertEquals("https://x/a.json", ep.transcript); assertTrue(ep.transcriptType.contains("json"))
    }

    @Test fun quoteRoundTrip() {
        val q = Quote("id", "t", 1234, "u", "e", "p", "f", "a", "n", 5)
        assertEquals(q, Quote.fromJson(q.toJson()))
    }
}
