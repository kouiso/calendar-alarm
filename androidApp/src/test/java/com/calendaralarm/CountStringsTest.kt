package com.calendaralarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数を含む文言の回帰テスト。
 * 英語で「In 1 minutes」「1 alarms」「Every 1 days」と出ていたため、数で形を選ぶ plurals にした。
 * 繰り返し間隔の既定値は 1 なので、単数形は普通に目に入る。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CountStringsTest {

    private val res get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun q(id: Int, n: Int) = res.getQuantityString(id, n, n)

    @Test
    @Config(qualifiers = "en")
    fun englishPicksSingularForOne() {
        assertEquals("In 1 minute", q(R.plurals.event_minutes_before, 1))
        assertEquals("In 5 minutes", q(R.plurals.event_minutes_before, 5))
        assertEquals("1 alarm could not ring, for example because the phone was off", q(R.plurals.missed_count, 1))
        assertEquals("3 alarms could not ring, for example because the phone was off", q(R.plurals.missed_count, 3))
        assertEquals("Every day", q(R.plurals.repeat_every_days, 1))
        assertEquals("Every 2 days", q(R.plurals.repeat_every_days, 2))
        assertEquals("Every week", q(R.plurals.repeat_every_weeks, 1))
        assertEquals("Every 2 weeks", q(R.plurals.repeat_every_weeks, 2))
        assertEquals("Every month", q(R.plurals.repeat_every_months, 1))
        assertEquals("Every 3 months", q(R.plurals.repeat_every_months, 3))
    }

    @Test
    @Config(qualifiers = "ja")
    fun japaneseKeepsTheNumber() {
        assertEquals("1分前です", q(R.plurals.event_minutes_before, 1))
        assertEquals("端末の停止等により 1件のアラームを鳴らせませんでした", q(R.plurals.missed_count, 1))
        assertEquals("1日ごと", q(R.plurals.repeat_every_days, 1))
        assertEquals("1週ごと", q(R.plurals.repeat_every_weeks, 1))
        assertEquals("1ヶ月ごと", q(R.plurals.repeat_every_months, 1))
    }
}
