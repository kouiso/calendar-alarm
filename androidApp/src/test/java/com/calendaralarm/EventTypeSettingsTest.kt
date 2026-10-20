package com.calendaralarm

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.shared.model.EventTypeFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/** 種類ごとの鳴動設定の保存値の読み方 (#79)。前から使っている人の設定を勝手に変えないこと。 */
class EventTypeSettingsTest {

    @Test
    fun 一度も触っていない人は新しい既定値になる() {
        assertEquals(EventTypeFilter(), SettingsRepository.eventTypeFilterOf(mutablePreferencesOf()))
    }

    @Test
    fun 旧版で全部オンのままの人は全部鳴りサイレントモードも予定と同じく鳴る() {
        val p = mutablePreferencesOf(SettingsRepository.KEY_EVENT_TYPE_MASK to 31)
        assertEquals(
            EventTypeFilter(
                birthday = true, absence = true, workplace = true, focusTime = true,
                task = true, event = true, free = true,
            ),
            SettingsRepository.eventTypeFilterOf(p),
        )
    }

    @Test
    fun 旧版で選んでいた種類はそのまま保たれる() {
        // 誕生日と予定だけオン (1 + 16)
        val f = SettingsRepository.eventTypeFilterOf(mutablePreferencesOf(SettingsRepository.KEY_EVENT_TYPE_MASK to 17))
        assertEquals(true, f.birthday)
        assertEquals(false, f.absence)
        assertEquals(false, f.workplace)
        assertEquals(false, f.task)
        assertEquals(true, f.event)
        assertEquals(true, f.focusTime)
        assertEquals(true, f.free)
    }

    @Test
    fun 新しいキーはそれぞれ単独で効く() {
        val p = mutablePreferencesOf(
            SettingsRepository.KEY_EVENT_TYPE_MASK to 31,
            SettingsRepository.KEY_EVENT_TYPE_FOCUS to false,
            SettingsRepository.KEY_RING_FREE to false,
        )
        val f = SettingsRepository.eventTypeFilterOf(p)
        assertEquals(false, f.focusTime)
        assertEquals(false, f.free)
        assertEquals(true, f.event)
    }
}
