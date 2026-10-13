package com.calendaralarm

import com.calendaralarm.ui.ringSetupTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class RingSetupTitleTest {
    @Test
    fun `リード分があれば実際の分数を出す`() {
        assertEquals("会議の10分前に、きちんと鳴らすために", ringSetupTitle("会議", 10))
        assertEquals("会議の45分前に、きちんと鳴らすために", ringSetupTitle("会議", 45))
    }

    @Test
    fun `リード分が0以下やnullなら分数を出さない`() {
        assertEquals("朝のアラームを、きちんと鳴らすために", ringSetupTitle("朝のアラーム", null))
        assertEquals("朝のアラームを、きちんと鳴らすために", ringSetupTitle("朝のアラーム", 0))
        assertEquals("朝のアラームを、きちんと鳴らすために", ringSetupTitle("朝のアラーム", -5))
    }

    @Test
    fun `件名が無い時の既定文言`() {
        assertEquals("予定の15分前に、きちんと鳴らすために", ringSetupTitle(null, 15))
        assertEquals("アラームを、きちんと鳴らすために", ringSetupTitle(null, null))
    }
}
