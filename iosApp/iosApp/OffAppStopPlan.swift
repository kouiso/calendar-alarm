import Foundation

/// アプリが見ていない間に鳴って止められたアラームの見分け方 (#61)。
///
/// アプリが終了・サスペンド中に AlarmKit のアラームが鳴り、ロック画面で止められると、
/// アプリは `.alerting` を観測できず記録は PENDING のまま残る。以前はこれを「予約が消えた」とみなして
/// 再予約していたため、止めたアラームが次の resync で約5秒後にもう一度鳴った (1時間を過ぎると見逃し通知)。
///
/// 決め方: AlarmKit に予約したことがあり (alarmKitId あり)、今は AlarmKit に無く、予定時刻を過ぎた
/// PENDING 行だけを「鳴って止められた」として扱う。
/// 予約に失敗したまま残した行 (スヌーズ子の予約失敗など、alarmKitId なし) は鳴りようがないので、
/// 時刻を過ぎていても従来どおり「予約が消えた」側で拾い直す (グレース内なら即鳴動、過ぎれば見逃し通知)。
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

    /// 鳴って止められたとみなす行の id。[isLive] は AlarmKit に予約が残っているか
    static func consumedWhileAway(pending: [Row], nowMillis: Int64, isLive: (String) -> Bool) -> [String] {
        pending
            .filter { $0.wasReserved && $0.triggerAtMillis <= nowMillis && !isLive($0.id) }
            .map { $0.id }
            .sorted()
    }

    /// 停止済みにする行と、それで消費される単発アラームを決める。
    /// スヌーズ子がまだ待っている単発は消費しない (reconcile と同じ規則)
    static func decide(pending: [Row], nowMillis: Int64, isLive: (String) -> Bool) -> Decision {
        let consumed = consumedWhileAway(pending: pending, nowMillis: nowMillis, isLive: isLive)
        let consumedSet = Set(consumed)
        let stillPending = pending.filter { !consumedSet.contains($0.id) }.map { $0.id }
        var oneShot: Set<Int64> = []
        for row in pending where consumedSet.contains(row.id) {
            guard let aid = row.standaloneAlarmId else { continue }
            if !stillPending.contains(where: { $0.hasPrefix("\(row.id):snz") }) { oneShot.insert(aid) }
        }
        return Decision(consumed: consumed, oneShotAlarmIds: oneShot)
    }

    /// 予約が消えたので拾い直す行の id。予定時刻がまだ先の行と、予約に成功していない行
    static func lostReservations(pending: [Row], nowMillis: Int64, isLive: (String) -> Bool) -> [String] {
        pending
            .filter { ($0.triggerAtMillis > nowMillis || !$0.wasReserved) && !isLive($0.id) }
            .map { $0.id }
            .sorted()
    }
}
