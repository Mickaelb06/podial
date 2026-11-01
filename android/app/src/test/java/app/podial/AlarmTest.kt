package app.podial

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class AlarmTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = Calendar.getInstance().apply { clear(); set(y, mo, d, h, mi, 0) }.timeInMillis
    private fun fmt(t: Long?) = t?.let { Calendar.getInstance().apply { timeInMillis = it }.let { c -> "%d-%02d-%02d %02d:%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) } }

    // lundi 2 novembre 2026
    private val monday9 = at(2026, Calendar.NOVEMBER, 2, 9, 0)

    @Test fun inactiveAlarmNeverFires() = assertNull(Alarm.nextTrigger(AlarmConfig(enabled = false), monday9))

    @Test fun onceFiresTodayIfStillAhead() = assertEquals("2026-11-02 10:30", fmt(Alarm.nextTrigger(AlarmConfig(true, 10, 30, 0), monday9)))

    @Test fun onceFiresTomorrowIfPassed() = assertEquals("2026-11-03 07:00", fmt(Alarm.nextTrigger(AlarmConfig(true, 7, 0, 0), monday9)))

    @Test fun exactNowGoesToNextDay() = assertEquals("2026-11-03 09:00", fmt(Alarm.nextTrigger(AlarmConfig(true, 9, 0, 0), monday9)))

    @Test fun weekdaysSkipWeekend() {
        val weekdays = 0b0011111
        val friday = at(2026, Calendar.NOVEMBER, 6, 8, 0)
        assertEquals("2026-11-09 07:00", fmt(Alarm.nextTrigger(AlarmConfig(true, 7, 0, weekdays), friday)))
    }

    @Test fun sundayOnly() = assertEquals("2026-11-08 07:00", fmt(Alarm.nextTrigger(AlarmConfig(true, 7, 0, 1 shl 6), monday9)))

    @Test fun sameWeekdayNextWeek() = assertEquals("2026-11-09 08:00", fmt(Alarm.nextTrigger(AlarmConfig(true, 8, 0, 1 shl 0), at(2026, Calendar.NOVEMBER, 2, 9, 0))))

    @Test fun configJsonRoundTrip() {
        val c = AlarmConfig(true, 6, 45, 0b0101010, Item("radio", "FIP", "Radio France", "", stream = "http://x/fip"))
        assertEquals(c, AlarmConfig.fromJson(org.json.JSONObject(c.toJson().toString())))
    }
}
