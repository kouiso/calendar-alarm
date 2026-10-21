package com.calendaralarm

import com.calendaralarm.ui.AppPermission
import com.calendaralarm.ui.PermissionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPlanTest {

    private fun state(vararg granted: AppPermission) =
        AppPermission.entries.associateWith { it in granted }

    @Test
    fun `未許可を決まった順に1つずつ頼む`() {
        assertEquals(AppPermission.CALENDAR, PermissionPlan.next(state(), emptySet()))
        assertEquals(AppPermission.NOTIFY, PermissionPlan.next(state(AppPermission.CALENDAR), emptySet()))
        assertEquals(
            AppPermission.FULL_SCREEN,
            PermissionPlan.next(state(AppPermission.CALENDAR, AppPermission.NOTIFY, AppPermission.EXACT), emptySet()),
        )
    }

    @Test
    fun `断られた許可は同じ流れで飛ばし、全部そろえば頼まない`() {
        assertEquals(AppPermission.EXACT, PermissionPlan.next(state(AppPermission.CALENDAR), setOf(AppPermission.NOTIFY)))
        assertNull(PermissionPlan.next(state(*AppPermission.entries.toTypedArray()), emptySet()))
        assertTrue(PermissionPlan.allGranted(state(*AppPermission.entries.toTypedArray())))
        assertFalse(PermissionPlan.allGranted(state(AppPermission.CALENDAR)))
    }

    @Test
    fun `他のアプリの上に重ねて表示は求める対象に入れない`() {
        assertEquals(
            listOf("CALENDAR", "NOTIFY", "EXACT", "FULL_SCREEN"),
            AppPermission.entries.map { it.name },
        )
    }
}
