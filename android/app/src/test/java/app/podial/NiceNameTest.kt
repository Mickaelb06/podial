package app.podial

import org.junit.Assert.assertEquals
import org.junit.Test

class NiceNameTest {
    @Test fun franceBleuIsShownAsIci() {
        assertEquals("ici Provence", Api.niceName("France Bleu Provence"))
        assertEquals("ici Azur", Api.niceName("france bleu Azur "))
        assertEquals("ici 100% chanson française", Api.niceName("France Bleu 100% chanson française"))
    }

    @Test fun otherNamesAreKept() {
        assertEquals("ici Elsass", Api.niceName("ici Elsass"))
        assertEquals("FIP", Api.niceName("FIP"))
        assertEquals("France Bleue", Api.niceName("France Bleue"))   // pas le préfixe « France Bleu » suivi d'un espace
    }

    @Test fun oldAndNewNamesAreTheSameStation() {
        assertEquals(Api.stationKey(Api.niceName("France Bleu Provence")), Api.stationKey("ICI Provence"))
    }
}
