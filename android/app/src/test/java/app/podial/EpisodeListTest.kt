package app.podial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeListTest {
    private fun ep(n: Int, date: String) = Episode("ep$n", "https://e/$n.mp3", date, "")
    private val eps = listOf(ep(1, "Mon, 05 Oct 2026"), ep(2, "Tue, 20 Oct 2026"), ep(3, "Fri, 9 Oct 2026"), ep(4, "Sun, 18 Oct 2026"))
    private fun titles(l: List<Episode>) = l.map { it.title }

    @Test fun sortsByDateNotByFeedOrder() {
        assertEquals(listOf("ep2", "ep4", "ep3", "ep1"), titles(EpisodeList.apply(eps, EpSort.NEWEST, EpFilter.ALL, emptySet(), emptySet())))
        assertEquals(listOf("ep1", "ep3", "ep4", "ep2"), titles(EpisodeList.apply(eps, EpSort.OLDEST, EpFilter.ALL, emptySet(), emptySet())))
    }

    @Test fun keepsFeedOrderWhenADateIsUnreadable() {
        val l = listOf(ep(1, "demain"), ep(2, "Tue, 20 Oct 2026"), ep(3, ""))
        assertEquals(listOf("ep1", "ep2", "ep3"), titles(EpisodeList.apply(l, EpSort.NEWEST, EpFilter.ALL, emptySet(), emptySet())))
        assertEquals(listOf("ep3", "ep2", "ep1"), titles(EpisodeList.apply(l, EpSort.OLDEST, EpFilter.ALL, emptySet(), emptySet())))
    }

    @Test fun filtersDownloadedAndUnfinished() {
        val down = setOf("https://e/1.mp3", "https://e/3.mp3")
        val played = setOf("https://e/2.mp3", "https://e/3.mp3")
        assertEquals(listOf("ep3", "ep1"), titles(EpisodeList.apply(eps, EpSort.NEWEST, EpFilter.DOWNLOADED, down, played)))
        assertEquals(listOf("ep4", "ep1"), titles(EpisodeList.apply(eps, EpSort.NEWEST, EpFilter.UNFINISHED, down, played)))
        assertEquals(listOf("ep1", "ep4"), titles(EpisodeList.apply(eps, EpSort.OLDEST, EpFilter.UNFINISHED, down, played)))
        assertEquals(emptyList<String>(), titles(EpisodeList.apply(eps, EpSort.NEWEST, EpFilter.DOWNLOADED, emptySet(), emptySet())))
    }

    @Test fun sameDayKeepsFeedOrder() {
        val l = listOf(ep(1, "Tue, 20 Oct 2026"), ep(2, "Tue, 20 Oct 2026"))
        assertEquals(listOf("ep1", "ep2"), titles(EpisodeList.apply(l, EpSort.NEWEST, EpFilter.ALL, emptySet(), emptySet())))
    }

    @Test fun parsesDates() {
        assertEquals(EpisodeList.day("Tue, 20 Oct 2026"), EpisodeList.day("20 october 2026 08:00") ?.let { it })
        assertNull(EpisodeList.day("2026-10-20"))
        assertNull(EpisodeList.day("31 Foo 2026"))
    }
}
