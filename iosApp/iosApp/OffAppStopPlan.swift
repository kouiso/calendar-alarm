import Foundation

/// アプリが見ていない間に鳴って止められたアラームの見分け方 (#61)。
///
/// アプリが終了・サスペンド中に AlarmKit のアラームが鳴り、ロック画面で止められると、
/// アプリは `.alerting` を観測できず記録は PENDING のまま残る。以前はこれを「予約が消えた」とみなして
/// 再予約していたため、止めたアラームが次の resync で約5秒後にもう一度鳴った (1時間を過ぎると見逃し通知)。
///
/// 決め方: 停止ボタンの intent (`AlarmStopIntent`) が「止めた」と書き残した行だけを停止済みにする。
/// AlarmKit から消えたことだけでは決めない。再起動や OS の破棄で予約が本当に消えた行も同じく消えて見えるため、
/// 消えただけで止めた扱いにすると、鳴らさず通知も出さず、履歴には「止めた」と残ってしまう (仕様 7章)。
/// 書き残しの無い行は従来どおり「予約が消えた」側で拾い直す (グレース内なら即鳴動、過ぎれば見逃し通知)。
/// 鳴っている間 (alerting) とスヌーズ中 (countdown) は AlarmKit に残るので、この判定には入らない。
enum OffAppStopPlan {
    struct Row: Equatable {
        let id: String
        let triggerAtMillis: Int64
        /// AlarmKit への予約が成功したか (ScheduledRecord.alarmKitId が入っているか)
        let wasReserved: Bool
        let standaloneAlarmId: Int64?

        init(id: String, triggerAtMillis: Int64, wasReserved: Bool, standaloneAlarmId: Int64? = nil) {
            self.id = id
            self.triggerAtMillis = triggerAtMillis
            self.wasReserved = wasReserved
            self.standaloneAlarmId = standaloneAlarmId
        }
    }

    struct Decision: Equatable {
        /// 停止済みにする行の id
        let consumed: [String]
        /// 消費済みとして無効化する単発アラームの id
        let oneShotAlarmIds: Set<Int64>
    }

    /// 鳴って止められた行の id。[stopped] は停止ボタンの intent が書き残した id、[isLive] は AlarmKit に予約が残っているか
    ///
    /// [bootMillis] は端末が最後に起動した時刻。書き残しが無くても、鳴る時刻より前から端末が起動し続けていて
    /// 予約だけ消えているなら、再起動で消えたのではなく鳴って止められたと見なす。
    /// 実機 (iOS 26.5.2) で、ロック画面のスライド停止後に書き残しが付かず鳴り直したため (#118)。
    /// nil (読めない) の時はこの見なしをしない
    static func consumedWhileAway(pending: [Row], nowMillis: Int64, stopped: Set<String>, bootMillis: Int64? = nil,
                                  isLive: (String) -> Bool) -> [String] {
        pending
            .filter {
                $0.wasReserved && $0.triggerAtMillis <= nowMillis && !isLive($0.id)
                    && (stopped.contains($0.id) || Self.stayedUpThrough($0.triggerAtMillis, bootMillis: bootMillis))
            }
            .map { $0.id }
            .sorted()
    }

    /// 停止済みにする行と、それで消費される単発アラームを決める。
    /// スヌーズ子がまだ待っている単発は消費しない。アプリ内で止めた時 (dismiss / reconcileAlarmKitState) は
    /// すぐ無効化するので、この経路だけの新しい規則。単発を無効化すると、待っているスヌーズ子が resync で持ち越されず鳴らなくなるため
    static func decide(pending: [Row], nowMillis: Int64, stopped: Set<String>, bootMillis: Int64? = nil,
                       isLive: (String) -> Bool) -> Decision {
        let consumed = consumedWhileAway(pending: pending, nowMillis: nowMillis, stopped: stopped,
                                         bootMillis: bootMillis, isLive: isLive)
        let consumedSet = Set(consumed)
        let stillPending = pending.filter { !consumedSet.contains($0.id) }.map { $0.id }
        var oneShot: Set<Int64> = []
        for row in pending where consumedSet.contains(row.id) {
            guard let aid = row.standaloneAlarmId else { continue }
            if !stillPending.contains(where: { $0.hasPrefix("\(row.id):snz") }) { oneShot.insert(aid) }
        }
        return Decision(consumed: consumed, oneShotAlarmIds: oneShot)
    }
}

extension OffAppStopPlan {
    /// 鳴る時刻の前から端末が起動したままか。再起動をまたいでいないので、予約が消えた理由は「鳴って止められた」しか残らない
    static func stayedUpThrough(_ triggerAtMillis: Int64, bootMillis: Int64?) -> Bool {
        guard let boot = bootMillis else { return false }
        return boot <= triggerAtMillis
    }
}

/// 端末の最終起動時刻 (kern.boottime)
enum SystemBoot {
    static var millis: Int64? {
        var tv = timeval()
        var size = MemoryLayout<timeval>.stride
        guard sysctlbyname("kern.boottime", &tv, &size, nil, 0) == 0, tv.tv_sec > 0 else { return nil }
        return Int64(tv.tv_sec) * 1000 + Int64(tv.tv_usec) / 1000
    }
}

/// 停止ボタンの intent が「止めた」を書き残す場所。intent はアプリの外 (ロック画面) から呼ばれ、
/// Engine が居ないこともあるので、次の resync が読むまでここに置く。
struct OffAppStopLog {
    static let key = "offAppStoppedInstances"
    /// 対応する行が来ないまま残った書き残しを捨てるまでの時間 (展開ホライズンと同じ14日)
    static let retentionMillis: Int64 = 14 * 86_400_000

    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// instanceId → 止めた時刻 (ms)
    var entries: [String: Int64] {
        (defaults.dictionary(forKey: Self.key) ?? [:]).compactMapValues { ($0 as? NSNumber)?.int64Value }
    }

    /// 読んで足して書く一連を1本ずつ通す。停止 intent は複数同時に走り、resync の remove とも重なる。
    /// UserDefaults は1回の読み書きしか守らないので、重なると片方の書き残しが上書きで消え、
    /// 止めたアラームが「予約が消えた」側に回って鳴り直す
    private static let lock = NSLock()

    func record(_ instanceId: String, atMillis: Int64) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        var e = entries
        e[instanceId] = atMillis
        defaults.set(e.mapValues { NSNumber(value: $0) }, forKey: Self.key)
    }

    /// 使い終わった id と、古すぎる書き残しを消す
    func remove(_ ids: Set<String>, nowMillis: Int64) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        let kept = entries.filter { !ids.contains($0.key) && nowMillis - $0.value < Self.retentionMillis }
        defaults.set(kept.mapValues { NSNumber(value: $0) }, forKey: Self.key)
    }
}
