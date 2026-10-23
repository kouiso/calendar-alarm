import Foundation

/// アプリが見ていない間に鳴って止められたアラームの見分け方 (#61)。
///
/// アプリが終了・サスペンド中に AlarmKit のアラームが鳴り、ロック画面で止められると、
/// アプリは `.alerting` を観測できず記録は PENDING のまま残る。以前はこれを「予約が消えた」とみなして
/// 再予約していたため、止めたアラームが次の resync で約5秒後にもう一度鳴った (1時間を過ぎると見逃し通知)。
///
/// 決め方: 次のどちらかを満たす行を停止済みにする。どちらも、予約が成功していて鳴る時刻を過ぎ、AlarmKit から予約が消えている行が対象。
/// 1. 停止ボタンの intent (`AlarmStopIntent`) が「止めた」と書き残した行
/// 2. 書き残しが無くても、予約を入れた時から端末が起動し続けている行 (起動時刻 <= 予約した時刻)。
///    実機のロック画面のスライド停止は書き残しが付かないため (#118)
/// 予約のあとに再起動した行、予約した時刻が不明な旧版の行、起動時刻が読めない時は、消えただけでは決めない。
/// 再起動や OS の破棄で予約が本当に消えた行も同じく消えて見え、止めた扱いにすると、鳴らさず通知も出さず、
/// 履歴には「止めた」と残ってしまうため (仕様 7章)。その行は従来どおり「予約が消えた」側で拾い直す (グレース内なら即鳴動、過ぎれば見逃し通知)。
/// 既知の穴: 端末が起動したまま OS が予約を黙って捨てた行も、2. で止めた扱いになる (#136)。
/// 鳴っている間 (alerting) とスヌーズ中 (countdown) は AlarmKit に残るので、この判定には入らない。
enum OffAppStopPlan {
    struct Row: Equatable {
        let id: String
        let triggerAtMillis: Int64
        /// AlarmKit への予約が成功したか (ScheduledRecord.alarmKitId が入っているか)
        let wasReserved: Bool
        /// 予約を OS に入れた時刻。nil (旧版の行) は再起動をまたいだか分からないので、書き残しが無い限り止めた扱いにしない
        let reservedAtMillis: Int64?
        let standaloneAlarmId: Int64?

        init(id: String, triggerAtMillis: Int64, wasReserved: Bool, reservedAtMillis: Int64? = nil,
             standaloneAlarmId: Int64? = nil) {
            self.id = id
            self.triggerAtMillis = triggerAtMillis
            self.wasReserved = wasReserved
            self.reservedAtMillis = reservedAtMillis
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
    /// [bootMillis] は端末が最後に起動した時刻。書き残しが無くても、予約を入れた時から端末が起動し続けていて
    /// 予約だけ消えているなら、再起動で消えたのではなく鳴って止められたと見なす。
    /// 実機 (iOS 26.5.2) で、ロック画面のスライド停止後に書き残しが付かず鳴り直したため (#118)。
    /// nil (読めない) の時はこの見なしをしない
    static func consumedWhileAway(pending: [Row], nowMillis: Int64, stopped: Set<String>, bootMillis: Int64? = nil,
                                  isLive: (String) -> Bool) -> [String] {
        pending
            .filter {
                $0.wasReserved && $0.triggerAtMillis <= nowMillis && !isLive($0.id)
                    && (stopped.contains($0.id) || Self.stayedUpThrough($0.reservedAtMillis, bootMillis: bootMillis))
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
    /// 予約を入れた時から端末が起動したままか。予約後に再起動していれば OS が予約を捨てた可能性があり、
    /// 鳴る時刻と比べると「予約後・鳴る前の再起動」を見逃して、鳴らず通知も出ない行を作ってしまう (#118)。
    /// 予約した時刻が分からない (nil) 時は決められないので false
    static func stayedUpThrough(_ reservedAtMillis: Int64?, bootMillis: Int64?) -> Bool {
        guard let reserved = reservedAtMillis, let boot = bootMillis else { return false }
        return boot <= reserved
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
