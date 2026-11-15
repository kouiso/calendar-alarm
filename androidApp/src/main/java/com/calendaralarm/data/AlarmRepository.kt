package com.calendaralarm.data

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.db.AuditLogEntity
import com.calendaralarm.data.db.CalendarPrefEntity
import com.calendaralarm.data.db.EventOverrideEntity
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.data.db.StandaloneAlarmEntity
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.logic.AlarmPlanner
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.CalendarSource
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.RepeatMode
import com.calendaralarm.shared.model.StandaloneAlarm
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * 鳴動ドメインの統合リポジトリ。
 * 「理想状態の展開 → 差分 → AlarmManager/DB への適用」を一手に引き受ける。
 */
private fun String?.toOffsets(): List<Int> =
    this?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 } ?: emptyList()

private fun List<Int>.toCsv(): String = distinct().sorted().joinToString(",")

class AlarmRepository(
    private val db: AppDatabase,
    private val scheduler: AlarmScheduler,
    private val calendarReader: CalendarContractReader,
    private val settings: SettingsRepository,
    private val hasCalendarPermission: () -> Boolean,
    private val appScope: CoroutineScope,
    /** 予約が変わった時の呼び出し (ウィジェット更新用)。 */
    private val onScheduleChanged: (suspend () -> Unit)? = null,
    /** 期限切れが MISSED 化された件数の通知 (missed 通知用)。 */
    private val onMissed: (suspend (Int) -> Unit)? = null,
) {
    // 自動スヌーズ・手動スヌーズ・停止の状態遷移を一つずつ通す (#145)
    private val ringTransitionLock = Mutex()
    // 自動スヌーズで作った子 (親 id → 子 id と作った時刻)。直後に届いた停止で子ごと取り消す
    private val autoSnoozedChildren = ConcurrentHashMap<String, Pair<String, Long>>()
    // 自動スヌーズと停止の重なりを測る時計。テストで 30 秒経過を作るための差込口
    internal var nowMillis: () -> Long = System::currentTimeMillis

    // ---- Google Calendar eventType 取得 ----

    /**
     * Google連携済みアカウントの Calendar API から eventType を引き、
     * iCalUID で照合して各イベントの googleEventType を埋める。
     * 未連携・トークン失効・API失敗・型なしイベントはそのまま残し
     * (resolveEventType がタイトル判定にフォールバックする)。
     */
    private suspend fun enrichWithGoogleTypes(
        events: List<com.calendaralarm.shared.model.CalendarEvent>,
        appSettings: SettingsRepository.Settings,
        beginMillis: Long,
        endMillis: Long,
    ): List<com.calendaralarm.shared.model.CalendarEvent> {
        if (appSettings.googleAccountEmails.isEmpty()) return events
        // 連携したアカウントごとに引いて合わせる (#74)。1アカウントの失敗で他を捨てない
        val types = appSettings.googleAccountEmails.mapNotNull { email ->
            com.calendaralarm.data.calendar.GoogleCalendarTypes
                .fetchEventTypes(calendarReader.appContext(), email, beginMillis, endMillis)
        }.fold(emptyMap<String, String>()) { acc, m -> acc + m }
        if (types.isEmpty()) return events
        return events.map { ev ->
            types[ev.iCalUID]?.let { ev.copy(googleEventType = it) } ?: ev
        }
    }

    // ---- 参照 ----

    fun pendingFlow(): Flow<List<AlarmInstance>> =
        db.scheduledInstances().pendingFlow().map { rows -> rows.map { it.toInstance() } }

    /**
     * 鳴動画面用: インスタンスと状態を1行クエリで同時に監視する。
     * 状態は通知の「停止」放送など画面外経路の終端化を検知するのに使う。
     */
    fun instanceWithStateFlow(id: String): Flow<Pair<AlarmInstance, AlarmState?>?> =
        db.scheduledInstances().byIdFlow(id).map { row ->
            row?.let {
                it.toInstance() to runCatching { AlarmState.valueOf(it.state) }.getOrNull()
            }
        }.distinctUntilChanged()

    suspend fun instanceById(id: String): AlarmInstance? =
        db.scheduledInstances().byId(id)?.toInstance()

    fun standaloneAlarmsFlow(): Flow<List<StandaloneAlarm>> =
        db.standaloneAlarms().allFlow().map { rows -> rows.map { it.toModel() } }

    suspend fun setState(id: String, state: AlarmState) =
        db.scheduledInstances().setState(id, state.name)

    /**
     * 鳴動ログへ 1 行書く。書けなくても例外を外へ出さない。
     * 呼び出し元の多くは鳴動・停止の経路にある独立コルーチンで、そこで例外が漏れると
     * プロセスごと落ちて鳴動と停止を巻き込む (#46)。ログが欠けるより鳴動が止まる方が重い。
     */
    suspend fun audit(action: String, detail: String, ring: ScheduledInstanceEntity? = null) {
        try {
            withContext(Dispatchers.IO) {
                db.auditLog().insert(
                    AuditLogEntity(
                        atMillis = System.currentTimeMillis(),
                        action = action,
                        detail = detail,
                        instanceId = ring?.id,
                        triggerAtMillis = ring?.triggerAtMillis,
                        title = ring?.title,
                    ),
                )
            }
        } catch (e: CancellationException) {
            // 呼び出し側自身が取り消されたのならそのまま伝える。閉じた DB 等が投げる
            // 取り消しは書き込み失敗として扱う
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "鳴動ログの書き込みに失敗: $action", e)
        } catch (e: Exception) {
            Log.w(TAG, "鳴動ログの書き込みに失敗: $action", e)
            // 書き込みの失敗中に呼び出し側が取り消されていたら、その取り消しは伝える
            currentCoroutineContext().ensureActive()
        }
    }

    fun auditFlow(limit: Int = 100) = db.auditLog().recentFlow(limit)

    /** 停止・スヌーズ・鳴らず の記録だけを新しい順に (設定先頭の「最近の鳴動」と「すべて見る」用) */
    fun recentRingLogFlow(limit: Int = 3) =
        db.auditLog().recentByActionsFlow(RING_OUTCOME_ACTIONS, limit)

    /**
     * 鳴動時刻が直近24時間の「鳴らず」の件数 (予定画面と設定の「鳴動の状態」用)。
     * 予約の行は単発アラームを使い直すと消えるので、鳴動ログの MISS 行で数える (#51 PR3)。
     * 画面を開いたままでも24時間を過ぎた見逃しが外れるよう、窓の起点を一定間隔で進め直す
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recentMissedFlow(
        nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        refreshMillis: Long = 60_000L,
    ): Flow<Int> =
        flow {
            while (true) {
                emit(nowMillis())
                delay(refreshMillis)
            }
        }.flatMapLatest { now ->
            db.auditLog().missedSinceFlow(now - com.calendaralarm.shared.logic.RingStatus.MISSED_WINDOW_MILLIS)
        }.distinctUntilChanged()

    /** アジェンダ画面用: イベント + 有効な鳴動ルールと解決済みアクション。 */
    data class AgendaItem(
        val event: com.calendaralarm.shared.model.CalendarEvent,
        val calendarName: String,
        val calendarColor: Int,
        /** 解決後の開始時アクション (上書き>コード>ルール)。 */
        val startAction: EventAction,
        /** 解決後のリマインダーアクション。 */
        val reminderAction: EventAction,
        val minutesBefore: Int,
        /** 予定ごとの指定かカレンダー既定の追加リマインダー (共通リマインダーは含めない) */
        val extraOffsets: List<Int>,
        /** 実際に効く追加リマインダー (共通リマインダー込み, #143)。一覧の表示と詳細シートのチップに使う */
        val effectiveExtraOffsets: List<Int> = extraOffsets,
        val hasOverride: Boolean,
        /** カレンダーを鳴らさない設定にしている (予定ごとの指定より強い。展開がカレンダー単位で外すため) */
        val calendarOff: Boolean = false,
        /** アカウントを鳴らさない設定にしている (#74。カレンダーを鳴らさない時と同じく展開が予定ごとの指定より先に外す) */
        val accountOff: Boolean = false,
        /** 終日の予定で、カレンダーが終日を鳴らさない設定 (予定ごとの指定より強い) */
        val allDayOff: Boolean = false,
        /** リマインダー側の鳴る時刻があるか (追加の何分前か、取り込んだカレンダーの通知)。無ければリマインダーの動作は何も鳴らさない */
        val hasReminderTriggers: Boolean = true,
        /** 設定の「アラームをすべてミュート」。展開はアラームを全部外し、通知だけ残す */
        val muteAll: Boolean = false,
        /**
         * 予定ごとの指定を外すと何も鳴らない (カレンダーの既定が鳴らさない・種類や招待の絞り込みで外れる等)。
         * 行のスイッチを ON にする時、これが true の時だけ「アラーム」の指定を書く (通知だけの予定を格上げしないため)
         */
        val defaultSilenced: Boolean = false,
        /**
         * 行のスイッチを ON にして戻る動作が、すべてミュートで外れて鳴らない。
         * defaultSilenced との違いは、すべてミュートで外れるアラームの既定も数える点 (通知だけの既定は数えない)
         */
        val restoreSilenced: Boolean = false,
    ) {
        /** 予定ごとの指定では鳴るようにできない (スイッチを押せなくする) */
        val lockedByCalendar: Boolean get() = calendarOff || accountOff || allDayOff

        /**
         * 行のスイッチを押せなくする。すべてミュート中は、ON で戻る動作 (アラームの指定、または外した後の既定) が
         * 展開で外れて鳴らないなら入れられない。ON で通知に戻る行は、今 OFF でも入れられるように残す
         * (今の muted ではなく ON で戻る先で決める。通知の行を OFF にした後で押せなくなるのを防ぐ)
         */
        val switchLocked: Boolean get() = lockedByCalendar || (muteAll && restoreSilenced)

        /**
         * 何も鳴らない状態 (UI のグレー化と行のスイッチ)。展開 (AlarmExpander.expandEvents) と同じく、
         * リマインダーの動作はリマインダー側の時刻がある時だけ鳴るので、時刻が無ければ開始の動作だけで決まる。
         * すべてミュート中のアラームも鳴らない側に数える (通知は残る)
         */
        val muted: Boolean
            get() = startAction.silenced() && (reminderAction.silenced() || !hasReminderTriggers)

        private fun EventAction.silenced(): Boolean =
            this == EventAction.MUTE || (muteAll && this == EventAction.ALARM)
    }

    /**
     * Reminders.MINUTES=-1 (MINUTES_DEFAULT) は「システム既定」。実値は端末依存で取得できないため、
     * 設定画面で変えられる既定分数 defaultMinutesBefore に解決する (代替値を隠さない)。
     * 予約 (resync) と一覧のスイッチが別々に解決すると、鳴るのに「鳴らない」と出るずれが起きるので、両方ここを通す
     */
    private fun CalendarEvent.withResolvedDefaultReminders(appSettings: SettingsRepository.Settings): CalendarEvent =
        copy(calendarReminderMinutes = calendarReminderMinutes.map { if (it < 0) appSettings.defaultMinutesBefore else it })

    suspend fun upcomingEvents(days: Int = 14): List<AgendaItem> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext emptyList()
        val now = Clock.System.now()
        val calendarList = calendarReader.calendars()
        val sources = calendarList.associateBy { it.id }
        val prefs = db.calendarPrefs().all().associateBy { it.calendarId }
        val overrides = db.eventOverrides().all().associateBy { it.instanceKey }
        val appSettings = settings.flow.first()
        // アカウント単位の設定 (#74): 使わないアカウントの予定は一覧に出さず、鳴らさない分は鳴らない表示にする
        val accountApplied = com.calendaralarm.shared.logic.AccountRules.apply(
            calendarList, appSettings.accountSettings, emptyMap(), AlarmRule(minutesBefore = appSettings.defaultMinutesBefore),
        )
        val events = enrichWithGoogleTypes(
            calendarReader.events(
                now.toEpochMilliseconds() - AlarmExpander.FIRE_GRACE.inWholeMilliseconds,
                (now + days.days).toEpochMilliseconds(),
            ).map { it.withResolvedDefaultReminders(appSettings) },
            appSettings,
            now.toEpochMilliseconds() - AlarmExpander.FIRE_GRACE.inWholeMilliseconds,
            (now + days.days).toEpochMilliseconds(),
        )
        events.filter { it.calendarId !in accountApplied.hiddenCalendarIds }.map { ev ->
            val pref = prefs[ev.calendarId]
            val ov = overrides[ev.instanceKey]
            val rule = AlarmRule(
                enabled = pref?.enabled != false,
                minutesBefore = pref?.minutesBefore
                    ?: accountApplied.calendarRules[ev.calendarId]?.minutesBefore
                    ?: appSettings.defaultMinutesBefore,
                allDayMinutes = pref?.allDayMinutes ?: 540,
                extraOffsets = pref?.extraOffsetsCsv.toOffsets(),
                startAction = pref?.startAction?.toAction() ?: appSettings.defaultStartAction,
                reminderAction = pref?.reminderAction?.toAction()
                    ?: appSettings.defaultReminderAction,
            )
            val override = ov?.toModel()
            // 展開と同じ規則で、この予定に効く追加リマインダー (共通リマインダー込み, #143)。
            // 一覧の鳴る／鳴らない表示が、共通リマインダーだけで鳴る予定を OFF と出さないようにする
            val effectiveExtras = AlarmExpander.effectiveExtraOffsets(
                ov?.extraOffsetsCsv?.toOffsets(), rule, appSettings.extraReminderOffsets,
            )
            // 招待フィルタも反映する: 展開側で鳴らない予定が「アラーム」
            // アイコン表示になるのは矛盾
            // 予約の展開と同じ判定関数を使い、一覧の鳴る／鳴らない表示を実際の予約と一致させる (#79)
            // 終日を鳴らさないカレンダーの終日予定も、個別指定より先に展開で外れるので鳴らない側に倒す
            val allDayOff = AlarmExpander.allDaySuppressed(ev, rule)
            // 鳴らさないアカウント (#74) の予定も展開が個別指定より先に外すので、スイッチで入れても鳴らない
            val accountOff = ev.calendarId in accountApplied.disabledCalendarIds
            val (start, reminder) = if (pref?.enabled == false || allDayOff || accountOff ||
                !AlarmExpander.passesEventFilters(ev, override, appSettings.inviteFilter, appSettings.eventTypeFilter)
            ) {
                EventAction.MUTE to EventAction.MUTE
            } else {
                AlarmExpander.resolveActions(ev, rule, override, appSettings.titleCodes)
            }
            AgendaItem(
                event = ev,
                calendarName = sources[ev.calendarId]?.name ?: ev.calendarId,
                calendarColor = sources[ev.calendarId]?.color ?: 0xFF888888.toInt(),
                startAction = start,
                reminderAction = reminder,
                minutesBefore = ov?.minutesBefore ?: rule.minutesBefore,
                // 共通リマインダーは含めない。詳細シートはチップを触った時だけ一覧を書く (#143)
                extraOffsets = ov?.extraOffsetsCsv?.toOffsets() ?: rule.extraOffsets,
                effectiveExtraOffsets = effectiveExtras,
                hasOverride = ov != null,
                calendarOff = pref?.enabled == false,
                accountOff = accountOff,
                allDayOff = allDayOff,
                hasReminderTriggers = effectiveExtras.isNotEmpty() ||
                    (appSettings.importEventReminders && ev.calendarReminderMinutes.any { it >= 0 }),
                muteAll = appSettings.muteAll,
                defaultSilenced = AlarmExpander.silencedWithoutOverride(
                    ev, rule, appSettings.titleCodes, appSettings.inviteFilter, appSettings.eventTypeFilter,
                    // 何分前などの個別指定は ON でも残すので、リマインダー側の時刻は指定の分まで数える
                    hasReminderTriggers = effectiveExtras.isNotEmpty() ||
                        (appSettings.importEventReminders && ev.calendarReminderMinutes.any { it >= 0 }),
                ),
                restoreSilenced = AlarmExpander.silencedWithoutOverride(
                    ev, rule, appSettings.titleCodes, appSettings.inviteFilter, appSettings.eventTypeFilter,
                    hasReminderTriggers = effectiveExtras.isNotEmpty() ||
                        (appSettings.importEventReminders && ev.calendarReminderMinutes.any { it >= 0 }),
                    muteAll = appSettings.muteAll,
                ),
            )
        }
    }

    suspend fun calendarSources(): List<CalendarSource> = withContext(Dispatchers.IO) {
        // 権限が無いまま読むと SecurityException でアプリごと落ち、設定画面にも入れなくなる (#85)。
        // resync と同じく先に確かめる。確かめた直後に権限を外される窓もあるので、読み取りの拒否も空として扱う
        if (!hasCalendarPermission()) return@withContext emptyList()
        try {
            calendarReader.calendars()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    suspend fun calendarPrefsFlowList() = db.calendarPrefs().all()

    // ---- 鳴動イベント ----

    suspend fun onFired(id: String) {
        // 停止が先に届いたレースで DISMISSED 等の終端を FIRED で上書き
        // しないよう、遷移元を UPDATE の WHERE で atomic に制限する。
        // 終端行が FIRED に戻ると kill 復活や再鳴動の対象に残ってしまう
        // 再同期の書き戻し (#232) と同じロックで、PENDING の確認と書き戻しの間に FIRED が挟まらないようにする
        val rows = ringTransitionLock.withLock { db.scheduledInstances().setFiredIfPending(id) }
        audit(
            if (rows > 0) "FIRE" else "STALE_FIRE",
            if (rows > 0) id else "$id: 終端状態のため FIRED 化を抑止",
        )
        onScheduleChanged?.invoke()
    }

    /** インスタンスの現在状態。行が無ければ null。 */
    suspend fun instanceState(id: String): AlarmState? =
        db.scheduledInstances().byId(id)?.state?.let {
            runCatching { AlarmState.valueOf(it) }.getOrNull()
        }

    suspend fun onDismissed(id: String?) {
        if (id == null) return
        ringTransitionLock.withLock { dismissRow(id) }
        onScheduleChanged?.invoke()
    }

    private suspend fun dismissRow(id: String) {
        setState(id, AlarmState.DISMISSED)
        scheduler.cancel(id)
        audit("DISMISS", id, db.scheduledInstances().byId(id))
        // 放置の自動スヌーズと停止が重なった時は停止を優先し、直前に作った子も取り消す (#145)
        autoSnoozedChildren.remove(id)?.let { (childId, at) ->
            val child = db.scheduledInstances().byId(childId)
            if (nowMillis() - at <= AUTO_SNOOZE_RACE_MS && child?.state == AlarmState.PENDING.name) {
                setState(childId, AlarmState.CANCELLED)
                scheduler.cancel(childId)
                audit("DISMISS", "$childId: 停止が自動スヌーズと重なったので子も取り消す", child)
            }
        }
        // 単発アラームは終端到達=消費。アラーム本体も OFF に倒して
        // 「有効表示なのに二度と鳴らない」状態を残さない
        db.scheduledInstances().byId(id)?.standaloneAlarmId?.let {
            disableConsumedOneShots(setOf(it))
        }
    }

    /**
     * 鳴動中 (または鳴動開始直前) の [id] が、同時刻などで後から来た [by] に座席を譲った時の扱い (#84)。
     * 押し出された側は誰も止めていないので終端にしない。行は FIRED (鳴ったが片付いていない) のまま残し、
     * 待ち行列 ([waitingRings]) に載せて、[by] が片付いた後に [rearmWaitingRings] で鳴らし直す。
     * PENDING に戻さないのは、resync が PENDING の過去分を予約し直して、[by] が鳴っている最中に
     * すぐ発火させてしまうため。待ち行列に載せるのは、通知だけの予定 (NOTIFY) が通知権限なしで
     * 鳴動に回った場合も取りこぼさないため
     */
    suspend fun onDisplaced(id: String, by: String) {
        waitingRings.add(id)
        audit("DISPLACED", "$id → $by: 後発が片付いた後に鳴らし直す")
    }

    /**
     * 押し出されて待っている鳴動を、すぐ鳴るよう予約し直す (#84)。
     * 対象は待ち行列の行と、鳴動猶予内で FIRED のまま残った鳴動 (押し出しの途中でプロセスが死んだ等)。
     * 押し出しで AlarmManager の発火は消費済みなので、予約し直さないと二度と鳴らない。
     * AlarmReceiver は PENDING の行だけを鳴らすので PENDING に戻してから予約する。
     * 複数あっても順に押し出し合い、1件ずつ鳴る。行の鳴動時刻は変えない (猶予の判定と予定との対応を保つため)。
     * 呼び出し側は、鳴動中の鳴動が無い時だけ呼ぶ。戻り値は予約し直した件数
     */
    suspend fun rearmWaitingRings(resolvedId: String?): Int {
        val now = System.currentTimeMillis()
        val since = now - AlarmExpander.FIRE_GRACE.inWholeMilliseconds
        val queued = waitingRings.all()
        resolvedId?.let { waitingRings.remove(it) }
        // 判定から実行までの間に鳴り始めた鳴動は、待ち側ではないので PENDING に戻さない
        val ringingNow = com.calendaralarm.engine.AlarmService.ringingInstanceId.value
        val candidates = (queued.mapNotNull { db.scheduledInstances().byId(it) } +
            db.scheduledInstances().firedBetween(since, now))
            .distinctBy { it.id }
            .filter { it.id != ringingNow }
            .map { it.toInstance() to it.state }
        // 待っている間に親のアラームを OFF・削除されたら鳴らし直さない。resync は PENDING しか
        // 取り消さないので、FIRED のまま待つ行はここで確かめる
        val enabledAlarmIds = db.standaloneAlarms().all().filter { it.enabled }.map { it.id }.toSet()
        val muteAll = settings.flow.first().muteAll
        val orphaned = candidates.filter { (_, state) ->
            state == AlarmState.FIRED.name || state == AlarmState.PENDING.name
        }.map { it.first }.filter { inst ->
            val parent = db.scheduledInstances().byId(inst.id)?.standaloneAlarmId
            inst.id != resolvedId && (inst.id in queued || inst.delivery == EventAction.ALARM) &&
                ((muteAll && inst.kind != AlarmKind.TIMER && inst.kind != AlarmKind.NOTIFICATION) ||
                    (parent != null && parent !in enabledAlarmIds))
        }.map { it.id }.toSet()
        orphaned.forEach {
            // 読んだ後に停止・スヌーズされた行は書き換えない (条件付き UPDATE で atomic に)
            val changed = db.scheduledInstances().setCancelledIfLive(it) > 0
            waitingRings.remove(it)
            if (changed) audit("REARM_SKIP", "$it: 待つ間に親のアラームが OFF・削除、または一括ミュートされたため鳴らし直さない")
        }
        val pendingAtRead = candidates.filter { it.second == AlarmState.PENDING.name }.map { it.first.id }.toSet()
        val waiting = candidates.filter { (inst, state) ->
            inst.id !in orphaned &&
            inst.id != resolvedId &&
                (state == AlarmState.FIRED.name || state == AlarmState.PENDING.name) &&
                inst.triggerAtMillis in (since + 1)..now &&
                // 待ち行列に無い FIRED は、鳴動の途中で途切れた鳴動だけ (通知だけの予定は通知済み)
                (inst.id in queued || inst.delivery == EventAction.ALARM)
        }.map { it.first }
        // 猶予を過ぎた待ち行は見逃しにして待ち行列から外す。FIRED のまま外すと、健全性チェックは
        // PENDING しか見ないので誰も見逃しにせず、単発アラームも ON のまま残る
        candidates.filter { (inst, state) ->
            inst.id in queued && inst.id != resolvedId &&
                (state == AlarmState.FIRED.name || state == AlarmState.PENDING.name) &&
                inst.triggerAtMillis <= since
        }.forEach { (inst, _) ->
            // markMissed(id) はロックを取らない条件付き UPDATE。ここは停止側のロックの外なので入れ子でも詰まらない
            if (markMissed(inst.id, "鳴らし直しを待つ間に鳴動猶予を過ぎた")) onMissed?.invoke(1)
        }
        // 片付いた行も待ち行列から外す
        queued.filter { q -> waiting.none { it.id == q } }.forEach { waitingRings.remove(it) }
        var rearmed = 0
        waiting.forEachIndexed { i, inst ->
            // FIRED からの巻き戻しが 0 行なら、読んだ後に停止・スヌーズされている。元々 PENDING の行
            // (巻き戻し不要) は今も PENDING の時だけ予約する
            val reverted = db.scheduledInstances().setPendingIfFired(inst.id) > 0
            waitingRings.remove(inst.id)
            if (!reverted && !(inst.id in pendingAtRead && instanceState(inst.id) == AlarmState.PENDING)) {
                return@forEachIndexed
            }
            rearmed++
            val fireAt = now + REARM_DELAY_MS + i * REARM_STAGGER_MS
            if (scheduler.schedule(inst.copy(triggerAtMillis = fireAt))) {
                audit("REARM", "${inst.id}: 押し出された鳴動を鳴らし直す")
            } else {
                audit("EXACT_DENIED", "rearm ${inst.id}")
            }
        }
        if (rearmed > 0) onScheduleChanged?.invoke()
        return rearmed
    }

    /**
     * 押し出されて待っている予定由来の鳴動のうち、ミュート・カレンダー OFF・予定削除などで
     * もう展開されなくなったものを取り消す。待っている行は FIRED で resync の差分に出ないため、ここで見る。
     * 読み取りの失敗中や権限なしの時は判断できないので何もしない
     */
    private suspend fun cancelStaleWaitingEvents(desired: Map<String, EventAction>, canJudge: Boolean) {
        if (!canJudge) return
        val since = System.currentTimeMillis() - AlarmExpander.FIRE_GRACE.inWholeMilliseconds
        for (id in waitingRings.all()) {
            val row = db.scheduledInstances().byId(id) ?: continue
            val isEvent = row.standaloneAlarmId == null && row.kind == AlarmKind.EVENT.name
            // スヌーズの子は展開に出ない (親の予定の id と別) ので判断しない。
            // 猶予切れは展開から外れるだけで設定の変更ではないので、見逃しの処理 (rearmWaitingRings) に任せる
            if (!isEvent || row.state != AlarmState.FIRED.name || row.snoozeSeq > 0) continue
            if (row.triggerAtMillis <= since) continue
            val now = desired[id]
            // 展開されなくなった (ミュート等)、または通知だけに変わったものは、音で鳴らし直さない
            val stale = now == null || (now != EventAction.ALARM && row.delivery == EventAction.ALARM.name)
            if (!stale) continue
            beforeCancelStaleWriteForTest?.invoke()
            // 読んだ後に停止・スヌーズされた行は書き換えない (条件付き UPDATE で atomic に)
            val changed = db.scheduledInstances().setCancelledIfLive(id) > 0
            waitingRings.remove(id)
            if (changed) audit("REARM_SKIP", "$id: 待つ間に予定の設定が変わり鳴らし直さない")
        }
    }

    /** 試験用の差込口: [cancelStaleWaitingEvents] が行を読んだ後、CANCELLED を書く直前に呼ぶ (#366) */
    @Volatile internal var beforeCancelStaleWriteForTest: (suspend () -> Unit)? = null

    /** 押し出されて鳴らし直しを待つ id。プロセスが死んでも残るよう端末に保存する (#84)。 */
    private val waitingRings = WaitingRingStore(calendarReader.appContext())

    suspend fun onSnoozed(id: String?, snoozeMinutes: Int? = null) {
        if (id == null) return
        val done = ringTransitionLock.withLock {
            // 先に停止・見逃しで終わった行へ遅れて届いたスヌーズは、子を作らない (止めたのに鳴るのを防ぐ)
            val entity = db.scheduledInstances().byId(id) ?: return@withLock false
            if (entity.state in TERMINAL_STATES) {
                audit("SNOOZE_SKIP", "$id: スヌーズの前に ${entity.state} になっていたので何もしない", entity)
                return@withLock false
            }
            snoozeRow(entity, snoozeMinutes)
            true
        }
        if (done) onScheduleChanged?.invoke()
    }

    /**
     * 放置による自動スヌーズ (#145)。手動の停止 [onDismissed] と排他にし、停止を勝たせる。
     * 鳴動中 (FIRED) のままの時だけ子を作る。先に停止された (DISMISSED など) なら何もしない。
     * 作った子は [AUTO_SNOOZE_RACE_MS] の間だけ覚え、その間に届いた停止で子ごと取り消す
     * (停止ボタンを押した瞬間に放置の時間切れが重なった時、押した側を優先するため)。
     * @return 子を作ったら true
     */
    suspend fun onAutoSnoozed(id: String, snoozeMinutes: Int): Boolean {
        val done = ringTransitionLock.withLock {
            val row = db.scheduledInstances().byId(id) ?: return@withLock false
            if (row.state != AlarmState.FIRED.name) {
                audit("SNOOZE_SKIP", "$id: 自動スヌーズの前に ${row.state} になっていたので何もしない", row)
                return@withLock false
            }
            val childId = snoozeRow(row, snoozeMinutes)
            autoSnoozedChildren[id] = childId to nowMillis()
            true
        }
        if (done) onScheduleChanged?.invoke()
        return done
    }

    /**
     * 放置の時間切れで「鳴らせなかった」にする (#145)。停止と排他にし、鳴動中 (FIRED) のままの時だけ書く。
     * 先に停止されていたら DISMISSED を MISSED で上書きせず、見逃しの通知も出さないよう false を返す
     */
    suspend fun markMissedIfFired(id: String, detail: String): Boolean {
        val done = ringTransitionLock.withLock {
            val row = db.scheduledInstances().byId(id) ?: return@withLock false
            if (row.state != AlarmState.FIRED.name) {
                audit("MISS_SKIP", "$id: 時間切れの前に ${row.state} になっていたので見逃しにしない", row)
                return@withLock false
            }
            setState(id, AlarmState.MISSED)
            audit("MISS", "$id: $detail", row)
            row.standaloneAlarmId?.let { disableConsumedOneShots(setOf(it)) }
            true
        }
        if (done) onScheduleChanged?.invoke()
        return done
    }

    /** スヌーズの子を予約して元を SNOOZED にする。呼び出し側が [ringTransitionLock] を持ち、
     *  ロックを放してから onScheduleChanged を呼ぶ (ウィジェット更新で停止の書き込みを待たせない)。子の id を返す */
    private suspend fun snoozeRow(entity: ScheduledInstanceEntity, snoozeMinutes: Int?): String {
        val instance = entity.toInstance()
        val minutes = snoozeMinutes ?: instance.snoozeMinutes
        val snoozed = instance.snoozed(
            Clock.System.now().toEpochMilliseconds() + minutes * 60_000L,
        ).copy(snoozeMinutes = minutes)
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(snoozed)))
        if (!scheduler.schedule(snoozed)) audit("EXACT_DENIED", snoozed.id)
        setState(instance.id, AlarmState.SNOOZED)
        audit("SNOOZE", "${instance.id} +${minutes}m → ${snoozed.id}", entity)
        return snoozed.id
    }

    /** タイマーなど単発インスタンスを即座に予約する。 */
    suspend fun scheduleAdhoc(instance: AlarmInstance) = withContext(Dispatchers.IO) {
        if (writeAdhoc(instance)) onScheduleChanged?.invoke()
    }

    /** 行を書いて予約する。onScheduleChanged は呼ばない (呼び出し側がロックを放してから呼ぶ)。予約まで通ったら true */
    private suspend fun writeAdhoc(instance: AlarmInstance): Boolean {
        // タイマー自体はスヌーズ分数を持たないので、設定の既定を載せる
        val inst = instance.copy(
            snoozeMinutes = settings.flow.first().defaultSnoozeMinutes,
        )
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(inst)))
        if (!scheduler.schedule(inst)) {
            db.scheduledInstances().setCancelledIfLive(inst.id)
            audit("EXACT_DENIED", "adhoc ${inst.id}")
            return false
        }
        audit("SCHEDULE", "adhoc ${inst.id} @${inst.triggerAtMillis}")
        return true
    }

    /**
     * 通知アラーム (`nf:`) を予約する。元アプリが同じ通知キーで出し直しても、同じ鎖
     * (`nf:X` とスヌーズの子 `nf:X:snz*`) の状態で次のように分ける (#371)。
     * 1. PENDING か FIRED がある: 何も書かない (鳴動待ち・鳴動中を上書きしない)
     * 2. DISMISSED がある: 最後に止めてから [NOTIFICATION_REPOST_WINDOW_MS] 未満なら鳴らさず記録を 1 行書く。以上なら鳴らす
     * 3. それ以外: [scheduleAdhoc] と同じに鳴らす
     * 止めた時刻は DISMISS の記録の行から読む (プロセスが落ちても残る)。読めなければ鳴動時刻から数える。
     * 落とした出し直しは窓を延ばさない。確かめと書き込みは [ringTransitionLock] の中で行う
     */
    suspend fun scheduleNotificationAlarm(instance: AlarmInstance) = withContext(Dispatchers.IO) {
        val changed = ringTransitionLock.withLock {
            val chain = db.scheduledInstances().chainOf(instance.id)
            val states = chain.map { it.state }.toSet()
            if (AlarmState.PENDING.name in states || AlarmState.FIRED.name in states) {
                return@withLock false
            }
            val dismissed = chain.filter { it.state == AlarmState.DISMISSED.name }
            if (dismissed.isNotEmpty()) {
                val stoppedAt = db.auditLog().latestAtMillis("DISMISS", dismissed.map { it.id })
                    ?: dismissed.maxOf { it.triggerAtMillis }
                val elapsed = nowMillis() - stoppedAt
                // 時計が止めた後に戻った (elapsed < 0) 時は窓の外とみなして鳴らす
                if (elapsed in 0 until NOTIFICATION_REPOST_WINDOW_MS) {
                    audit(
                        "NF_REPOST_SKIP",
                        "${instance.id}: 止めて ${elapsed / 1000} 秒なので出し直しを鳴らさない",
                        dismissed.maxByOrNull { it.triggerAtMillis },
                    )
                    return@withLock false
                }
            }
            writeAdhoc(instance)
        }
        if (changed) onScheduleChanged?.invoke()
    }

    // ---- 設定の変更から再同期 ----

    /**
     * 予約に効く既定のうち、設定画面が保存の後に自分で resync を呼んでいないもの (#231)。
     * ここに無い予約に効く欄 (種別・招待・タイトルコード・一括ミュート等) は、今は画面の各行が保存の後に
     * resync を呼んでいる。全部をここへ寄せて画面側の呼び出しを消すのは別の変更で行う
     */
    data class ScheduleDefaults(val minutesBefore: Int, val snoozeMinutes: Int) {
        companion object {
            fun of(s: SettingsRepository.Settings) =
                ScheduleDefaults(s.defaultMinutesBefore, s.defaultSnoozeMinutes)
        }
    }

    /**
     * 「何分前」「スヌーズ」の既定が変わったら再同期する (#231)。保存した層の flow を見張るので、
     * 設定画面を閉じても、アプリを開き直さなくても、予約済みのアラームが新しい分数になる。
     * 最初の値は起動時の状態なので再同期しない (起動時の再同期は別の入口が行う)。
     * 設定の読み取り自体が壊れても、例外をアプリへ漏らさない (鳴動のための起動を落とさない)
     * @param onFirstValue 最初の値を受け取った時に呼ぶ (試験が変更を入れる時を決めるため)
     */
    fun watchScheduleDefaults(scope: CoroutineScope, onFirstValue: () -> Unit = {}): Job = scope.launch {
        var first = true
        settings.flow
            .map { ScheduleDefaults.of(it) }
            .distinctUntilChanged()
            .catch { e -> Log.w(TAG, "設定の見張りが止まった", e) }
            .collect {
                if (first) {
                    first = false
                    onFirstValue()
                    return@collect
                }
                try {
                    resync("settings: defaults")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 見張りを止めない。次の変更か定期の再同期で直る
                    Log.w(TAG, "設定の変更の後の再同期に失敗", e)
                }
            }
    }

    // ---- 展開・適用 ----

    /**
     * 全ソースを再展開して予約を理想状態に合わせる。
     * カレンダー権限が無くても単発アラームは展開する（部分動作を優先）。
     */
    suspend fun resync(reason: String) = withContext(Dispatchers.IO) {
        // 差分計算は desired に無い PENDING を CANCELLED に倒すため、期限切れを
        // 先に MISSED 化しないと「鳴らせなかった」事実が通知も状態も残さず消える。
        // boot/時刻変更/手動/アプリ起動のどの入口からでも必ずここを通る。
        markMissed().let { if (it > 0) onMissed?.invoke(it) }
        val now = Clock.System.now()
        val horizon = now + 14.days
        val desired = mutableListOf<AlarmInstance>()

        // 終端行は先に読む。同 id の蘇生防止と単発アラームの消費判定の両方で使う
        val allRows = db.scheduledInstances().all()
        afterResyncReadForTest?.invoke()
        val terminalIds = allRows.asSequence()
            .filter { it.state in NO_REVIVE_STATES }
            .map { it.id }
            .toSet()

        // 単発/繰り返しアラーム。
        // 単発 (daysOfWeek 空) は一度終端に達したら二度と発行しない。
        // 展開は毎回「次の in-window 日付」へ新 id を発行するので、終端行が
        // 残る限り「1回のみ」の約束が日跨ぎで破られて毎日鳴り続ける。
        val consumedAlarmIds = allRows.asSequence()
            .filter { it.id in terminalIds }
            .mapNotNull { it.standaloneAlarmId }
            .toSet()
        val appSettings = settings.flow.first()
        val standalones = db.standaloneAlarms().all().map { it.toModel() }
        standalones.forEach { alarm ->
            // 一括ミュート中は単発/繰返しアラーム自体を展開しない (全部 ALARM 鳴動のため)
            if (!appSettings.muteAll &&
                (alarm.effectiveRepeatMode() != RepeatMode.ONCE || alarm.id !in consumedAlarmIds)
            ) {
                desired += AlarmExpander.expandStandalone(alarm, now = now, days = 14)
            }
        }

        // カレンダーイベント
        // 「権限が無い」と「読み取り自体が失敗」を区別する。後者 (同期中の
        // 権限剥奪・プロバイダ障害) では既存の EVENT 予約を維持し、一時的な
        // 障害で予定アラームが全部キャンセルされるのを防ぐ。
        var keepPendingEvents = false
        // スヌーズ中インスタンスの親がまだ「鳴らすべき」かの判定に使う。
        // 読み取り失敗時は空のまま (keepPendingEvents 経路で保護される)。
        var liveEventKeys = emptySet<String>()
        if (hasCalendarPermission()) {
            val prefRows = db.calendarPrefs().all()
            // 何分前を選んでいないカレンダー。アカウントの既定に従わせる (#74)
            val minutesFollowDefault = prefRows.filter { it.minutesBefore == null }.map { it.calendarId }.toSet()
            val prefs = prefRows.associate {
                it.calendarId to AlarmRule(
                    enabled = it.enabled,
                    minutesBefore = it.minutesBefore ?: appSettings.defaultMinutesBefore,
                    allDayMinutes = it.allDayMinutes,
                    extraOffsets = it.extraOffsetsCsv.toOffsets(),
                    startAction = it.startAction.toAction(),
                    reminderAction = it.reminderAction.toAction(),
                )
            }
            val overrideRows = db.eventOverrides().all()
            val storedOverrides = overrideRows.associate {
                it.instanceKey to EventOverride(
                    action = it.action.toActionOrNull(),
                    minutesBefore = it.minutesBefore,
                    extraOffsets = it.extraOffsetsCsv?.toOffsets(),
                )
            }
            var overrides = storedOverrides
            // DB への書き込みは展開に成功した後に行う。書き込みの失敗を読み取りの失敗と取り違えないため
            var pendingCarry: com.calendaralarm.shared.logic.OverrideCarry.Result? = null
            val snoozeDefault = appSettings.defaultSnoozeMinutes
            // calendar_prefs 行が無いカレンダーはグローバル既定が効く。
            // アジェンダ表示も同じ既定を読むため、UIとエンジンを一致させる。
            val defaultRule = AlarmRule(
                minutesBefore = appSettings.defaultMinutesBefore,
                startAction = appSettings.defaultStartAction,
                reminderAction = appSettings.defaultReminderAction,
            )
            val expanded = runCatching {
                // カレンダー一覧の読み取り失敗は予定の読み取り失敗と同じ扱いにする (下の runCatching で前回の予約を維持)。
                // 鳴らさないアカウントを無視して鳴らすことも、全部止めることもしない
                val calendarList = calendarReader.calendars()
                // アカウント単位の設定 (使う / 鳴らす / 何分前) を配下カレンダーへ当てはめる (#74)
                val accountApplied = com.calendaralarm.shared.logic.AccountRules.apply(
                    calendarList, appSettings.accountSettings, prefs, defaultRule, minutesFollowDefault,
                )
                // 未来側は「何分前」の最大分だけ horizon より先まで読む (#143)。
                // 開始が14日より先でも、共通リマインダーなどの前倒しで鳴動が14日以内に入るため。
                // 予約の範囲は展開側が鳴動時刻で horizon に切るので変わらない
                val fetchUntil = horizon + maxOf(
                    AlarmExpander.fetchLeadMinutes(
                        accountApplied.calendarRules.values + defaultRule,
                        overrides.values,
                        appSettings.extraReminderOffsets,
                    ),
                    // 予定側のリマインダーは読むまで分からないので、取り込む時は上限 (4週) まで読む
                    if (appSettings.importEventReminders) AlarmExpander.MAX_EVENT_REMINDER_MINUTES else 0,
                ).minutes
                // 過去側はグレース幅ではなく14日に広げる。スヌーズ連鎖が親予定の
                // 開始から長く伸びても liveEventKeys が親を見失わず、スヌーズ子が
                // 無言キャンセルされない。in-window 外の予定は展開側で弾かれる。
                val events = enrichWithGoogleTypes(
                    calendarReader.events(
                        (now - 14.days).toEpochMilliseconds(),
                        fetchUntil.toEpochMilliseconds(),
                    ).map { it.withResolvedDefaultReminders(appSettings) },
                    appSettings,
                    (now - 14.days).toEpochMilliseconds(),
                    fetchUntil.toEpochMilliseconds(),
                )
                // 予定があるのにカレンダーが1件も読めないのは一覧の読み取り失敗。
                // アカウント設定があるとアカウントへ当てはめられないので、前回の予約を維持する
                check(calendarList.isNotEmpty() || events.isEmpty() || appSettings.accountSettings.isEmpty()) {
                    "calendar list is empty while events exist"
                }
                // 予定の時刻が動いても予定ごとの指定を引き継ぎ、終わった回の指定は消す (#243)。
                // 読み取りに成功した時だけ行う (失敗時はどの指定もはぐれて見えるため)
                val carry = com.calendaralarm.shared.logic.OverrideCarry.reconcile(
                    storedOverrides, events, now.toEpochMilliseconds(), 1.days.inWholeMilliseconds,
                    fromMillis = (now - 14.days).toEpochMilliseconds(),
                    untilMillis = fetchUntil.toEpochMilliseconds(),
                )
                overrides = carry.overrides
                pendingCarry = carry
                val disabledCalIds = accountApplied.disabledCalendarIds
                liveEventKeys = events.asSequence()
                    .filter { overrides[it.instanceKey]?.muted != true }
                    .filter { it.calendarId !in disabledCalIds }
                    .map { it.instanceKey }
                    .toSet()
                AlarmExpander.expandEvents(
                    events = events,
                    calendarRules = accountApplied.calendarRules,
                    overrides = overrides,
                    disabledCalendarIds = disabledCalIds,
                    now = now, horizon = horizon,
                    zone = TimeZone.currentSystemDefault(),
                    defaultRule = defaultRule,
                    titleCodes = appSettings.titleCodes,
                    inviteFilter = appSettings.inviteFilter,
                    eventTypeFilter = appSettings.eventTypeFilter,
                    importEventReminders = appSettings.importEventReminders,
                    muteAll = appSettings.muteAll,
                    commonExtraOffsets = appSettings.extraReminderOffsets,
                )
            }.getOrNull()
            if (expanded != null) {
                pendingCarry?.let { applyOverrideCarry(overrideRows, it) }
                // イベント由来インスタンスは固有のスヌーズ設定を持たないため
                // 設定の既定を載せる
                desired += expanded.map { it.copy(snoozeMinutes = snoozeDefault) }
            } else {
                audit("ERROR", "カレンダー読み取り失敗: 既存の予定アラームを維持")
                keepPendingEvents = true
            }
        }

        // 終了済み状態 (鳴動/停止/スヌーズ/見逃し) の同 id は蘇生させない。
        // 展開が同 id を再発行しても、ユーザーが「止めた」「スヌーズした」意味を守る。
        val desiredBeforeTerminal = desired.associate { it.id to it.delivery }
        desired.removeAll { it.id in terminalIds }
        cancelStaleWaitingEvents(desiredBeforeTerminal, canJudge = hasCalendarPermission() && !keepPendingEvents)

        // タイマー・スヌーズなど Expander が再生しない予約は「理想状態」へ持ち越す。
        // 持ち越さないと差分計算で毎 resync キャンセルされてしまう。
        val pendingRows = allRows.filter { it.state == AlarmState.PENDING.name }
        val desiredIds = desired.map { it.id }.toSet()
        val adhoc = pendingRows.map { it.toInstance() }
            .filter { inst ->
                inst.id !in desiredIds && when {
                    // TIMER / NOTIFICATION は即時発火の adhoc。理想リストに無くても保持
                    inst.kind == AlarmKind.TIMER || inst.kind == AlarmKind.NOTIFICATION -> true
                    // スヌーズ中の子は「親が今も理想状態にある」場合だけ持ち越す。
                    // 親アラームの無効化・削除・予定ミュート後にスヌーズだけ鳴るのを防ぐ。
                    inst.snoozeSeq > 0 -> {
                        // 親の「鳴動意思」を見る。親インスタンス id の照合では
                        // グレース窓 (60分) を跨ぐスヌーズが resync で誤殺される
                        // ため、sa: はアラームの有効性、ev: は予定の存続+非ミュートを見る。
                        val root = inst.id.substringBefore(":snz")
                        when {
                            root.startsWith("sa:") ->
                                root.removePrefix("sa:").substringBefore(":")
                                    .toLongOrNull()?.let { aid ->
                                        standalones.any { it.id == aid && it.enabled }
                                    } == true
                            root.startsWith("ev:") ->
                                // カレンダー読み取り失敗時は平の EVENT 予約と同じく保守側に倒す
                                keepPendingEvents || root in desiredIds ||
                                    root.removePrefix("ev:").substringBeforeLast(":b") in liveEventKeys
                            else -> keepPendingEvents && inst.kind == AlarmKind.EVENT
                        }
                    }
                    else -> keepPendingEvents && inst.kind == AlarmKind.EVENT
                }
            }
        desired += adhoc

        // 書き戻しは鳴動・停止・スヌーズと同じロックの中で行う (#232)。
        // 上の読み取りから後に通信などを挟むので、その間に状態が変わった行がある。
        // ロックの中で読み直し、蘇生させない状態 (鳴動中・停止・スヌーズ・見逃し・飛ばし) になった行は書き戻さない。
        // 読んだ時にもう取り消し (CANCELLED) だった行は今までどおり、理想状態にあれば予約し直す (スイッチを ON に戻した時など)。
        // 読み取りの後にできた PENDING の行 (スヌーズの子など) は、この再同期では触らない (次の再同期で扱う)。
        // 再同期の全体はロックに入れない: 通信の間に停止ボタンが待たされるため
        ringTransitionLock.withLock {
            val current = db.scheduledInstances().all().associateBy { it.id }
            val noReviveNow = current.values.filter { it.state in NO_REVIVE_STATES }.map { it.id }.toSet()
            val stillPending = pendingRows.filter { current[it.id]?.state == AlarmState.PENDING.name }
            // 読んだ時に PENDING で、今は PENDING でない行も書き戻さない。停止がスヌーズの子を
            // 取り消した (CANCELLED) 時に、持ち越しの予約として鳴らし直さないため
            val leftPending = pendingRows.map { it.id }.toSet() - stillPending.map { it.id }.toSet()
            val finalDesired = desired.filterNot { it.id in noReviveNow || it.id in leftPending }
            val scheduled = stillPending.associate { it.id to it.triggerAtMillis }
            val plan = AlarmPlanner.plan(scheduled, finalDesired, now)
            applyPlan(plan, finalDesired, reason, scheduled.keys)
        }
        onScheduleChanged?.invoke()
    }

    /** 試験用の差込口: 再同期が全行を読んだ直後に呼ぶ (#232 の競合の再現) */
    @Volatile internal var afterResyncReadForTest: (suspend () -> Unit)? = null

    private suspend fun applyPlan(
        plan: AlarmPlanner.Plan,
        desired: List<AlarmInstance>,
        reason: String,
        armedIds: Set<String>,
    ) {
        for (id in plan.toCancel) {
            scheduler.cancel(id)
            setState(id, AlarmState.CANCELLED)
        }
        // 差分が無い予約も毎回 AlarmManager に再主張する。
        // 再起動・強制終了・パッケージ更新で OS 側だけ消えるケースを潰すため。
        // 近い順に予約する。AlarmManager の同時予約上限 (500) に万が一
        // 触れた場合、最も遠い予約から落ちる退化になる (近い側が鳴れば
        // 次回 resync で空きができて遠い側が拾い直される)。
        val fireNowIds = plan.toFireNow.map { it.id }.toSet()
        val toSchedule = desired.filter { it.id !in fireNowIds }
            .sortedBy { it.triggerAtMillis }
        if (toSchedule.isNotEmpty()) {
            db.scheduledInstances().upsert(toSchedule.map { ScheduledInstanceEntity.of(it) })
            toSchedule.forEach {
                if (!scheduler.schedule(it)) {
                    audit("EXACT_DENIED", it.id)
                    setState(it.id, AlarmState.CANCELLED)
                }
            }
        }
        // グレース幅内の過去分: PENDING に戻して即時トリガ (サービス経由で鳴らす)
        for (fire in plan.toFireNow) {
            // 一度も予約していない ev: インスタンスの過去分 (リマインダー取込ONの
            // 初回 resync 等) は「元カレンダー側が既に通知した時刻」。後追いで
            // 不意に鳴らさず、次回展開からも外れるよう終端にする。鳴らせなかったのではなく
            // わざと鳴らさなかったので、見逃しの件数に入らない SKIPPED にする。
            // setState は UPDATE WHERE id のみで行が無いと更新0になるため、
            // 先に行を作ってから終端化する (残さないと次回 resync で再展開される)。
            if (fire.id !in armedIds && fire.id.startsWith("ev:")) {
                db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(fire)))
                // markSkipped はウィジェットの更新を呼ぶので、ロックの中では状態と記録だけ書く
                setState(fire.id, AlarmState.SKIPPED)
                audit("SKIP", "${fire.id}: 予約前に通知時刻が過ぎていた")
                continue
            }
            db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(fire)))
            if (!scheduler.schedule(fire.copy(triggerAtMillis = Clock.System.now().toEpochMilliseconds()))) {
                audit("EXACT_DENIED", fire.id)
            }
        }
        audit(
            "RESYNC",
            "reason=$reason +${plan.toSchedule.size} -${plan.toCancel.size} fire=${plan.toFireNow.size}",
        )
        appScope.launch { pruneOldLogs() }
    }

    /** 鳴動中にプロセスが殺された後の再起動用: 鳴動猶予内の最新 FIRED を返す。 */
    suspend fun firedWithinGrace(): AlarmInstance? {
        val since = Clock.System.now().toEpochMilliseconds() - AlarmExpander.FIRE_GRACE.inWholeMilliseconds
        return db.scheduledInstances().latestFiredSince(since)?.toInstance()
    }

    /** 試験用の差込口: [markMissed] が MISSED を書く直前に呼ぶ。id 指定・引数なしの両方 (#366) */
    @Volatile internal var beforeMissedWriteForTest: (suspend () -> Unit)? = null

    /**
     * 単発指定で MISSED 化する。鳴動を試みたが起動を OS に拒否された時などに使う。
     * 生存中 (PENDING/FIRED) の行だけを条件付き UPDATE で書くので、先に停止・スヌーズされた行は
     * 上書きしない。ロックは取らない ([rearmWaitingRings] など呼び出し元がロックの外にあるとは限らないため)。
     * @return 実際に MISSED にしたら true。false なら見逃しの通知を出さない
     */
    suspend fun markMissed(id: String, detail: String? = null): Boolean {
        beforeMissedWriteForTest?.invoke()
        if (db.scheduledInstances().setMissedIfLive(id) == 0) {
            audit("MISS_SKIP", "$id: 見逃しにする前に終わっていたので何もしない", db.scheduledInstances().byId(id))
            return false
        }
        val row = db.scheduledInstances().byId(id)
        audit("MISS", if (detail == null) id else "$id: $detail", row)
        row?.standaloneAlarmId?.let {
            disableConsumedOneShots(setOf(it))
        }
        onScheduleChanged?.invoke()
        return true
    }

    /** 予約前に時刻が過ぎた行を、見逃しに数えない終端 (SKIPPED) にする。ev: 専用なので単発アラームの消費は見ない */
    suspend fun markSkipped(id: String, detail: String): Boolean {
        if (db.scheduledInstances().setSkippedIfPending(id) == 0) return false
        audit("SKIP", "$id: $detail")
        onScheduleChanged?.invoke()
        return true
    }

    /**
     * 鳴動時刻が過去の PENDING 行を MISSED に整理する（健全性チェック用）。
     * 戻り値は実際に変えた行の数 (読んだ後に停止された行は数えない)。
     */
    suspend fun markMissed(): Int {
        val cutoff = Clock.System.now().toEpochMilliseconds() - AlarmExpander.FIRE_GRACE.inWholeMilliseconds
        val stale = db.scheduledInstances().pending()
            .filter { it.triggerAtMillis < cutoff }
        beforeMissedWriteForTest?.invoke()
        val changed = stale.filter { db.scheduledInstances().setMissedIfOverduePending(it.id, cutoff) > 0 }
        changed.forEach { audit("MISS", it.id, it) }
        if (changed.isNotEmpty()) {
            disableConsumedOneShots(changed.mapNotNull { it.standaloneAlarmId }.toSet())
            onScheduleChanged?.invoke()
        }
        return changed.size
    }

    /** 単発アラームの終端到達をアラーム本体の OFF に反映する。 */
    private suspend fun disableConsumedOneShots(alarmIds: Set<Long>) {
        if (alarmIds.isEmpty()) return
        val alarms = db.standaloneAlarms().all().associateBy { it.id }
        for (id in alarmIds) {
            val e = alarms[id] ?: continue
            // 消費されるのは「1回のみ」のアラームだけ (繰返しは消費しない)
            if (e.enabled && (e.repeatMode == "ONCE" || (e.repeatMode == null && e.daysMask == 0))) {
                db.standaloneAlarms().upsert(e.copy(enabled = false))
                audit("ALARM_OFF", "単発アラーム消費で停止: ${e.label} (id=$id)")
            }
        }
    }

    // ---- 設定更新 ----

    suspend fun upsertStandaloneAlarm(alarm: StandaloneAlarm) {
        val entity = alarm.toEntity()
        val id = if (entity.id == 0L) db.standaloneAlarms().upsert(entity) else {
            db.standaloneAlarms().upsert(entity); entity.id
        }
        if (alarm.enabled && alarm.effectiveRepeatMode() == RepeatMode.ONCE) {
            // 単発アラームの再有効化は消費のリセット。終端行を消して
            // 消費判定 (終端行の有無) に引っかからないようにする
            db.scheduledInstances().deleteTerminalByAlarmId(id)
        }
        // 予約は再同期で整理する
        resync("alarm:$id updated")
    }

    suspend fun deleteStandaloneAlarm(id: Long) {
        db.standaloneAlarms().delete(id)
        resync("alarm:$id deleted")
    }

    suspend fun setCalendarPref(
        calendarId: String,
        enabled: Boolean,
        /** null = このカレンダーでは選ばず、アカウントの既定に従う */
        minutesBefore: Int?,
        allDayMinutes: Int? = null,
        extraOffsets: List<Int>? = null,
        startAction: EventAction? = null,
        reminderAction: EventAction? = null,
    ) {
        val current = db.calendarPrefs().all().firstOrNull { it.calendarId == calendarId }
        db.calendarPrefs().upsert(
            CalendarPrefEntity(
                calendarId = calendarId,
                enabled = enabled,
                minutesBefore = minutesBefore,
                allDayMinutes = allDayMinutes ?: current?.allDayMinutes ?: 540,
                extraOffsetsCsv = extraOffsets?.toCsv() ?: current?.extraOffsetsCsv ?: "",
                startAction = startAction?.name ?: current?.startAction ?: EventAction.ALARM.name,
                reminderAction = reminderAction?.name
                    ?: current?.reminderAction ?: EventAction.ALARM.name,
            ),
        )
        resync("calendar pref")
    }

    /** [OverrideCarry] の結果を DB へ書く。古いキーの行を新しいキーで作り直し、終わった回の行を消す (#243) */
    private suspend fun applyOverrideCarry(
        rows: List<EventOverrideEntity>,
        carry: com.calendaralarm.shared.logic.OverrideCarry.Result,
    ) {
        if (carry.moved.isEmpty() && carry.dropped.isEmpty()) return
        val byKey = rows.associateBy { it.instanceKey }
        try {
            // 途中で失敗して古いキーと新しいキーが両方残らないよう、まとめて 1 つのトランザクションで書く
            db.withTransaction {
                carry.moved.forEach { (old, new) ->
                    byKey[old]?.let { db.eventOverrides().upsert(it.copy(instanceKey = new)) }
                    db.eventOverrides().delete(old)
                }
                carry.dropped.forEach { db.eventOverrides().delete(it) }
            }
            audit("OVERRIDE_CARRY", "moved=${carry.moved.size} dropped=${carry.dropped.size}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 今回の展開は付け替えた後の指定で済んでいる。次の再同期でもう一度付け替える
            audit("ERROR", "予定ごとの指定の付け替えに失敗: ${e.javaClass.simpleName}")
        }
    }

    suspend fun setEventOverride(
        instanceKey: String,
        action: EventAction?,
        minutesBefore: Int?,
        extraOffsets: List<Int>? = null,
    ) {
        val current = db.eventOverrides().all().firstOrNull { it.instanceKey == instanceKey }
        val a = action?.name ?: current?.action
        val mb = minutesBefore ?: current?.minutesBefore
        val csv = extraOffsets?.toCsv() ?: current?.extraOffsetsCsv
        db.eventOverrides().upsert(EventOverrideEntity(instanceKey, a, mb, csv))
        resync("event override")
    }

    /**
     * 一覧の行のスイッチ (#51 PR2)。OFF は「鳴らさない」を書く。
     * ON は、指定を外すとそのまま鳴る予定なら指定を外してカレンダーの既定に戻す (通知だけの予定は通知のまま)。
     * 外すと鳴らない予定だけ「アラーム」を書く。何分前などの個別指定は残す
     */
    suspend fun setEventSwitch(item: AgendaItem, on: Boolean) {
        val key = item.event.instanceKey
        if (!on) {
            setEventOverride(key, EventAction.MUTE, null)
        } else if (item.defaultSilenced) {
            setEventOverride(key, EventAction.ALARM, null)
        } else {
            val current = db.eventOverrides().all().firstOrNull { it.instanceKey == key }
            if (current != null && (current.minutesBefore != null || current.extraOffsetsCsv != null)) {
                db.eventOverrides().upsert(current.copy(action = null))
                resync("event override action cleared")
            } else {
                removeEventOverride(key)
            }
        }
    }

    suspend fun removeEventOverride(instanceKey: String) {
        db.eventOverrides().delete(instanceKey)
        resync("event override cleared")
    }

    /**
     * 終了済み (PENDING 以外) インスタンス行を7日で削除する。
     * 7日以上前の発火は展開窓 (現在〜+14日) に二度と入らないため、
     * 削除しても終了状態の蘇生 (止めたアラームが再予約される) は起きない。
     */
    suspend fun pruneOldInstances() {
        val cutoff = Clock.System.now().toEpochMilliseconds() - 7L * 24 * 60 * 60 * 1000
        db.scheduledInstances().pruneTerminal(cutoff)
    }

    private suspend fun pruneOldLogs() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        // resync から独立したコルーチンで走るので、DB 障害を外へ出すとプロセスごと落ちる (#46)。
        // 古いログが残るだけなので、失敗は記録して次の resync に任せる
        try {
            db.auditLog().prune(cutoff)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "古い鳴動ログの掃除に失敗", e)
        } catch (e: Exception) {
            Log.w(TAG, "古い鳴動ログの掃除に失敗", e)
        }
    }

    // ---- 変換 ----

    private fun EventOverrideEntity.toModel() = EventOverride(
        action = action.toActionOrNull(),
        minutesBefore = minutesBefore,
        extraOffsets = extraOffsetsCsv?.toOffsets(),
    )

    private fun String?.toAction(): EventAction =
        runCatching { EventAction.valueOf(this ?: "") }.getOrDefault(EventAction.ALARM)

    private fun String?.toActionOrNull(): EventAction? =
        this?.let { runCatching { EventAction.valueOf(it) }.getOrNull() }

    private fun StandaloneAlarmEntity.toModel() = StandaloneAlarm(
        id = id,
        enabled = enabled,
        hour = hour,
        minute = minute,
        daysOfWeek = maskToDays(daysMask),
        label = label,
        soundUri = soundUri,
        snoozeMinutes = snoozeMinutes,
        exceptions = exceptionsCsv.split(',')
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            .toSet(),
        muteUntilUnlock = muteUntilUnlock,
        repeatMode = repeatMode?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() },
        repeatInterval = repeatInterval,
        repeatAnchorMillis = repeatAnchorMillis,
    )

    private fun StandaloneAlarm.toEntity() = StandaloneAlarmEntity(
        id = id,
        enabled = enabled,
        hour = hour,
        minute = minute,
        daysMask = daysToMask(daysOfWeek),
        label = label,
        soundUri = soundUri,
        snoozeMinutes = snoozeMinutes,
        exceptionsCsv = exceptions.joinToString(",") { it.toString() },
        muteUntilUnlock = muteUntilUnlock,
        repeatMode = repeatMode?.name,
        repeatInterval = repeatInterval,
        repeatAnchorMillis = repeatAnchorMillis,
    )

    companion object {
        /** 再同期が同じ id を予約し直さない状態。ユーザーが止めた・スヌーズした・鳴っている意味を守る */
        private val NO_REVIVE_STATES = setOf(
            AlarmState.FIRED.name, AlarmState.DISMISSED.name,
            AlarmState.SNOOZED.name, AlarmState.MISSED.name,
            AlarmState.SKIPPED.name,
        )
        /** 終わった行。遅れて届いたスヌーズで子を作らない (#145) */
        private val TERMINAL_STATES = setOf(
            AlarmState.DISMISSED.name, AlarmState.MISSED.name,
            AlarmState.CANCELLED.name, AlarmState.SKIPPED.name,
        )
        /** 自動スヌーズの直後に届いた停止を「同時に押した」とみなす長さ (#145) */
        const val AUTO_SNOOZE_RACE_MS = 30_000L
        /** 通知アラームを利用者が止めてから、同じ鎖の出し直しを鳴らさない長さ (#371) */
        const val NOTIFICATION_REPOST_WINDOW_MS = 10 * 60_000L
        /** 鳴らし直しの発火までの間。停止した鳴動の後始末 (通知の掃除など) が済んでから鳴らす */
        private const val REARM_DELAY_MS = 1_500L
        /** 待ちが複数ある時の発火のずらし幅。同じ時刻に重ねず、押し出しの順を安定させる */
        private const val REARM_STAGGER_MS = 500L
        private const val TAG = "AlarmRepository"

        /** 「最近の鳴動」に出す結果 (停止・スヌーズ・鳴らず)。鳴った (FIRE) は必ずどれかと対になるので出さない */
        val RING_OUTCOME_ACTIONS = listOf("DISMISS", "SNOOZE", "MISS")

        // ビット: 1=SUN,2=MON,4=TUE,8=WED,16=THU,32=FRI,64=SAT
        fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d ->
            acc or (1 shl ((d.ordinal + 1) % 7))
        }

        fun maskToDays(mask: Int): Set<DayOfWeek> = DayOfWeek.entries
            .filter { mask and (1 shl ((it.ordinal + 1) % 7)) != 0 }
            .toSet()
    }
}
