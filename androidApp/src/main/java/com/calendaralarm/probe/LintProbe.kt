package com.calendaralarm.probe

import android.app.AlarmManager

// 使い捨て: lint が新しい指摘で CI を落とすことを確かめるだけ（#300・#253）。取り込まない。
internal fun lintProbe(am: AlarmManager): Boolean = am.canScheduleExactAlarms()
