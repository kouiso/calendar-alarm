package com.calendaralarm.data.calendar

import com.calendaralarm.data.db.CalendarPrefEntity

/** カレンダーを端末をまたいで見分ける材料 (#268)。id は端末の行番号 (Calendars._ID) */
data class CalendarIdentity(
    val id: String,
    val accountType: String,
    val accountName: String,
    val syncId: String?,
    val name: String?,
    val displayName: String?,
)

/**
 * カレンダーごとの設定の行を、今の端末のカレンダーへ結び付ける (#268)。
 * 設定の鍵は端末の行番号ではなく「アカウントの種類・アカウント名・カレンダーの識別子」。
 * Android の型を使わない純粋な Kotlin。DB には触らず、書き直す分を [Result.rewrites] で返す。
 */
object CalendarPrefBinding {
    /** 鍵の区切り (U+001F)。アカウント名や表示名に現れない */
    const val SEP = "\u001F"

    /** "<アカウントの種類>SEP<アカウント名>SEP<S:同期用の ID | N:内部名 | D:表示名>" */
    fun keyOf(c: CalendarIdentity): String {
        val ident = when {
            !c.syncId.isNullOrEmpty() -> "S:${c.syncId}"
            !c.name.isNullOrEmpty() -> "N:${c.name}"
            else -> "D:${c.displayName.orEmpty()}"
        }
        return c.accountType + SEP + c.accountName + SEP + ident
    }

    /** 鍵の識別子の種類 ('S'・'N'・'D')。旧形式 (id:) や読めない鍵は null */
    internal fun kindOf(key: String): Char? {
        if (key.startsWith(CalendarPrefEntity.LEGACY_PREFIX)) return null
        val parts = key.split(SEP, limit = 3)
        if (parts.size < 3) return null
        val kind = parts[2].firstOrNull() ?: return null
        return if (kind == 'S' || kind == 'N' || kind == 'D') kind else null
    }

    /** [row] は結び付けた後の行 (calendarId は今の行番号)。[stored] は DB にある行。違えば書き直しが要る */
    data class Bound(val row: CalendarPrefEntity, val stored: CalendarPrefEntity)

    data class Result(val bound: Map<String, Bound>) {
        /** 今の行番号 → 設定の行 */
        val byCalendarId: Map<String, CalendarPrefEntity> get() = bound.mapValues { it.value.row }

        /** DB へ書き直す分 */
        val rewrites: List<Bound> get() = bound.values.filter { it.row != it.stored }
    }

    /** 控えの行番号のカレンダーへ、鍵を書き直して引き継いでよいか */
    private fun mayTakeOver(row: CalendarPrefEntity, c: CalendarIdentity): Boolean {
        val rowKind = kindOf(row.calendarKey) ?: return true // 旧形式の行
        if (row.accountType != c.accountType || row.accountName != c.accountName) return false
        val newKind = kindOf(keyOf(c))
        // 同期用の ID どうし・内部名どうしが違うなら別のカレンダー。表示名の変更と後からの同期用の ID だけ拾う
        return !(rowKind == newKind && (rowKind == 'S' || rowKind == 'N'))
    }

    fun bind(rows: List<CalendarPrefEntity>, calendars: List<CalendarIdentity>): Result {
        val sorted = calendars.sortedBy { it.id.toLongOrNull() ?: Long.MAX_VALUE }
        val rowsByKey = rows.associateBy { it.calendarKey }
        val bound = LinkedHashMap<String, Bound>()
        val used = HashSet<String>()
        val firstSeen = HashSet<String>()

        // 1 段目: 鍵が同じカレンダーに付ける。同じ鍵が複数あれば全部に同じ行を付け、書き直すのは最初の 1 件だけ
        for (c in sorted) {
            val key = keyOf(c)
            val r = rowsByKey[key] ?: continue
            val moved = r.copy(calendarId = c.id)
            bound[c.id] = Bound(row = moved, stored = if (firstSeen.add(key)) r else moved)
            used += key
        }

        // 2 段目: 控えの行番号が同じで、まだ行を持たないカレンダーへ引き継ぎ、鍵を書き直す
        val candidates = rows.mapNotNull { r ->
            if (r.calendarKey in used || r.calendarId.isEmpty()) return@mapNotNull null
            val c = sorted.firstOrNull { it.id == r.calendarId } ?: return@mapNotNull null
            if (c.id in bound || !mayTakeOver(r, c)) null else r to c
        }
        // 新しい鍵が同じになる行が複数ある時は、書き直すとどれかが孤児になり 1 段目で別の設定に上書きされる。
        // どれも書き直さず、行番号のまま各カレンダーに付ける
        val newKeyCount = candidates.groupingBy { keyOf(it.second) }.eachCount()
        for ((r, c) in candidates) {
            if (c.id in bound) continue
            if ((newKeyCount[keyOf(c)] ?: 0) > 1) {
                bound[c.id] = Bound(row = r, stored = r)
            } else {
                bound[c.id] = Bound(
                    row = r.copy(calendarKey = keyOf(c), accountType = c.accountType, accountName = c.accountName),
                    stored = r,
                )
            }
            used += r.calendarKey
        }

        // 3 段目: 一覧にその行番号が無い行は今までどおり行番号で効かせる (書き直さない)
        for (r in rows) {
            if (r.calendarKey in used || r.calendarId.isEmpty()) continue
            if (sorted.any { it.id == r.calendarId } || r.calendarId in bound) continue
            bound[r.calendarId] = Bound(row = r, stored = r)
            used += r.calendarKey
        }
        return Result(bound)
    }
}
