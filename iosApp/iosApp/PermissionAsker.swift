import SwiftUI

/// アプリを開いた時に、足りない許可を1つずつ自動で頼む (局長決定 2026-10-17 の1。Android の PermissionAsker と同じ流れ)。
/// 1画面に1つだけ出し、残りの数は数えない。設定アプリから戻っても未許可なら一度だけ
/// 「まだオフのまま」で頼み直し、「あとで」を押されたらこの起動中はもう頼まない。
/// 断られた後は設定の権限画面 (PermissionsView) から全部許可・個別に直せる。
@MainActor
final class PermissionAsker: ObservableObject {
    enum Action { case cta, later }

    @Published var current: AppPermission?
    @Published var showReady = false

    private var stopped = false
    private var asked: AppPermission?
    private var retried: Set<AppPermission> = []
    private var askedAny = false
    private var readyShown = false
    // OS のダイアログを閉じた時も前面復帰が来るので、判定の重なりを防ぐ
    private var busy = false

    /// 頼み直し (一度頼んだのにまだオフ) の表示にするか。
    func isRetry(_ p: AppPermission) -> Bool { asked == p }

    /// 今の許可状態を読み直して、次に頼む許可を決める。
    func advance(center: PermissionCenter, engine: Engine) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        await center.refresh(engine)
        if stopped { current = nil; return }
        let states = center.states
        let retry = asked.map { states[$0] != .granted } ?? false
        if retry, let a = asked, retried.contains(a) {
            // 頼み直しても戻ってきた = 断られた。ここで止めて権限画面に任せる
            stopped = true
            current = nil
            return
        }
        let next = retry ? asked : PermissionPlan.next(states, declined: [])
        if next == nil {
            current = nil
            if askedAny && !readyShown {
                readyShown = true
                showReady = true
            }
            return
        }
        if !retry { asked = nil }
        current = next
    }

    func handle(_ action: Action, for p: AppPermission, center: PermissionCenter, engine: Engine) async {
        switch action {
        case .later:
            stopped = true
            current = nil
        case .cta:
            if asked == p { retried.insert(p) }
            asked = p
            askedAny = true
            current = nil
            let opensSettings = center.states[p] == .denied
            await center.request(p, engine)
            // 設定アプリを開いた時は、戻ってきた時の前面復帰で判定する (今判定すると戻る前に「断られた」扱いになる)
            if !opensSettings { await advance(center: center, engine: engine) }
        }
    }
}

/// 1つの許可を頼むシート。
struct PermissionAskSheet: View {
    let permission: AppPermission
    let retry: Bool
    /// 一度断られていて、OS のダイアログでは頼めない (設定アプリで直す) か
    let needsSettings: Bool
    let onAction: (PermissionAsker.Action) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // 頼む文言は AppPermission が日本語の文字列で持つので、その文字列を鍵にして訳を引く
            Text(retry ? "まだオフのまま" : LocalizedStringKey(permission.askTitle))
                .font(NightTheme.font(20, weight: .semibold))
            Text(retry
                 ? "設定の「\(NSLocalizedString(permission.label, comment: ""))」をオンにすると、\(NSLocalizedString(permission.reason, comment: ""))。"
                 : LocalizedStringKey(permission.askBody))
                .font(NightTheme.font(14)).foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Button { onAction(.cta) } label: {
                Text(retry ? "もう一度設定を開く" : (needsSettings ? "設定でオンにする" : "許可する"))
                    .font(NightTheme.font(16, weight: .semibold))
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(NightTheme.indigo, in: RoundedRectangle(cornerRadius: 16))
                    .foregroundStyle(.white)
            }
            Button { onAction(.later) } label: {
                Text("あとで").font(NightTheme.font(14)).frame(maxWidth: .infinity)
            }
        }
        .padding(24)
        // 選ばずに閉じると「あとで」と区別できないので、どちらかを押してもらう
        .interactiveDismissDisabled(true)
    }
}

/// 全部そろった時に、一度鳴らして止まるか確かめてもらうシート。
struct ReadySheet: View {
    let onTest: () -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("これで、鳴ってすぐ止められる").font(NightTheme.font(20, weight: .semibold))
            Text("一度鳴らして、止まるか確かめておくと安心。").font(NightTheme.font(14)).foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Button(action: onTest) {
                Text("10秒後に鳴らしてみる")
                    .font(NightTheme.font(16, weight: .semibold))
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(NightTheme.indigo, in: RoundedRectangle(cornerRadius: 16))
                    .foregroundStyle(.white)
            }
            Button(action: onClose) {
                Text("閉じる").font(NightTheme.font(14)).frame(maxWidth: .infinity)
            }
        }
        .padding(24)
    }
}
