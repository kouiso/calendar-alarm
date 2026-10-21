import Foundation

/// アプリが見ていない間に鳴って止められたアラームの見分け方 (#61)。
///
/// アプリが終了・サスペンド中に AlarmKit のアラームが鳴り、ロック画面で止められると、
/// アプリは `.alerting` を観測できず記録は PENDING のまま残る。以前はこれを「予約が消えた」とみなして
/// 再予約していたため、止めたアラームが次の resync で約5秒後にもう一度鳴った (1時間を過ぎると見逃し通知)。
///
/// 決め方: AlarmKit に無い PENDING 行のうち、予定時刻を過ぎたものは「鳴って止められた」として扱う。
/// 予定時刻がまだ先のものだけを「予約が消えた」(再起動・OS の破棄) として拾い直す。
/// 鳴っている間 (alerting) とスヌーズ中 (countdown) は AlarmKit に残るので、この判定には入らない。
enum OffAppStopPlan {
    struct Row: Equatable {
        let id: String
        let triggerAtMillis: Int64
    }

    /// 鳴って止められたとみなす行の id。[isLive] は AlarmKit に予約が残っているか
    static func consumedWhileAway(pending: [Row], nowMillis: Int64, isLive: (String) -> Bool) -> [String] {
        pending
            .filter { $0.triggerAtMillis <= nowMillis && !isLive($0.id) }
            .map { $0.id }
            .sorted()
    }

    /// 予約が消えたので拾い直す行の id (予定時刻がまだ先のものだけ)
    static func lostReservations(pending: [Row], nowMillis: Int64, isLive: (String) -> Bool) -> [String] {
        pending
            .filter { $0.triggerAtMillis > nowMillis && !isLive($0.id) }
            .map { $0.id }
            .sorted()
    }
}
