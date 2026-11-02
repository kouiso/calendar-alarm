package com.calendaralarm

import com.calendaralarm.ui.extraReminderMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtraReminderInputTest {
    @Test
    fun acceptsTheSameMaximumAsTheIosPicker() {
        assertEquals(30 * 1440 + 23 * 60 + 59, extraReminderMinutes(30, 23, 59))
        assertEquals(20, extraReminderMinutes(0, 0, 20))
    }

    @Test
    fun rejectsOutOfRangeValuesBeforeCalculatingTheFetchWindow() {
        assertNull(extraReminderMinutes(999, 0, 0))
        assertNull(extraReminderMinutes(31, 0, 0))
        assertNull(extraReminderMinutes(0, 24, 0))
        assertNull(extraReminderMinutes(0, 0, 60))
        assertNull(extraReminderMinutes(-1, 0, 0))
        assertNull(extraReminderMinutes(Int.MAX_VALUE, 0, 0))
        assertNull(extraReminderMinutes(0, 0, 0))
    }
}
