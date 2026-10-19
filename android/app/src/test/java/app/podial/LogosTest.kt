package app.podial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogosTest {
    @Test fun prefersLargestAppleTouchIcon() {
        val html = """<head><link rel="icon" href="/f.ico"><link rel="apple-touch-icon" sizes="57x57" href="/a57.png">
            <link rel="apple-touch-icon" sizes="180x180" href="/a180.png"></head>"""
        assertEquals("https://radio.example/a180.png", Logos.pick(html, "https://radio.example/accueil"))
    }

    @Test fun resolvesRelativeLinksAgainstFinalAddress() {
        assertEquals("https://www.radio.fr/img/icon.png", Logos.pick("""<LINK REL='shortcut icon' HREF='img/icon.png'>""", "https://www.radio.fr/"))
    }

    @Test fun ignoresSvgDataUrisAndPagesWithoutIcon() {
        assertNull(Logos.pick("""<link rel="icon" href="/logo.svg"><link rel="icon" href="data:image/png;base64,AAAA">""", "https://r.fr/"))
        assertNull(Logos.pick("<html><body>rien</body></html>", "https://r.fr/"))
    }

    @Test fun keepsNonSvgWhenSvgIsAlsoDeclared() {
        assertEquals("https://r.fr/i.png", Logos.pick("""<link rel="icon" href="/i.svg"><link rel="icon" href="/i.png">""", "https://r.fr/"))
    }
}
