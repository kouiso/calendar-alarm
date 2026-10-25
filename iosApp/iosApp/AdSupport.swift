import SwiftUI
import GoogleMobileAds
import UserMessagingPlatform

/// 広告 (AdMob) の同意と初期化 (#140)。
///
/// アラーム経路とは完全に分離し、全て非同期で進める。
/// 同意取得・SDK初期化・広告読込みのどれかが失敗・遅延・オフラインでも
/// 「広告が出ない」だけに倒れ、鳴動・停止・スヌーズ・予定確認へ影響しない。
/// 予定のタイトルや内容は広告 SDK へ送らない (ターゲティング情報は一切渡さない)。
@MainActor
final class AdCoordinator: ObservableObject {
    static let shared = AdCoordinator()

    /// 広告を読み込んでよい状態か。同意が要る地域では同意後にだけ true になる
    @Published private(set) var canRequestAds = false

    private var started = false

    /// 同意フォームを表示している間だけ true。鳴動時にフォームを閉じる判定に使う
    private var isPresentingConsentForm = false
    /// 鳴動中かどうかを呼び出し側に委ねる述語 (engine.alertingIds の中身を見る)
    private var isAlerting: () -> Bool = { false }

    /// GDPR/EEA の同意状態を確認し、必要なら同意フォームを出してから GMA SDK を初期化する。
    /// 合意不要の地域ではフォームは出ずそのまま進む。1度だけ呼ぶ。
    /// 鳴動・停止・スヌーズ操作を同意フォームで塞がないよう、鳴動中は表示を待つ。
    func startIfNeeded(isAlerting: @escaping () -> Bool = { false }) {
        guard !started else { return }
        started = true
        self.isAlerting = isAlerting
        ConsentInformation.shared.requestConsentInfoUpdate(with: RequestParameters()) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                while self.isAlerting() {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                }
                self.isPresentingConsentForm = true
                ConsentForm.loadAndPresentIfRequired(from: Self.topRootViewController()) { [weak self] _ in
                    Task { @MainActor in
                        self?.isPresentingConsentForm = false
                        self?.finishConsent()
                    }
                }
            }
        }
    }

    /// 同意フォーム表示中に鳴動したらフォームを閉じて停止操作を優先させる (#140)。
    /// UMP のフォームしか対象にしないよう、表示中フラグと VC の型名の両方を確認する。
    func dismissConsentFormForRinging() {
        guard isPresentingConsentForm, let top = Self.topRootViewController() else { return }
        let name = String(describing: type(of: top))
        guard name.contains("ConsentForm") || name.contains("UMP") else { return }
        top.dismiss(animated: true)
    }

    /// ビュー切替のたびに作り直して広告リクエストが増えないよう、バナーを共有する
    private var cachedBannerView: BannerView?

    func sharedBannerView() -> BannerView {
        if let cachedBannerView { return cachedBannerView }
        let banner = BannerView(adSize: AdSizeBanner)
        banner.adUnitID = AdConfig.bannerUnitID
        banner.rootViewController = Self.topRootViewController()
        banner.load(GoogleMobileAds.Request())
        cachedBannerView = banner
        return banner
    }

    private func finishConsent() {
        guard ConsentInformation.shared.canRequestAds else { return }
        canRequestAds = true
        MobileAds.shared.start(completionHandler: nil)
    }

    static func topRootViewController() -> UIViewController? {
        var top = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

/// 広告ユニットIDの供給元。Info.plist の `AdMobBannerUnitID` 経由で差し替える
/// (既定は Google 公式のテストユニット ID。本番は project.yml の設定で差し替える)
enum AdConfig {
    static var bannerUnitID: String {
        Bundle.main.object(forInfoDictionaryKey: "AdMobBannerUnitID") as? String
            ?? "ca-app-pub-3940256099942544/2934735716"
    }
}

/// バナーの GADBannerView ラッパ。
private struct AdBannerViewRepresentable: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> BannerView {
        AdCoordinator.shared.sharedBannerView()
    }

    func updateUIView(_ uiView: BannerView, context: Context) {
        if uiView.rootViewController == nil {
            uiView.rootViewController = AdCoordinator.topRootViewController()
        }
    }

    final class Coordinator {}
}

/// 予定一覧の末尾に出すバナー1枠 (#140)。
/// 同意が取れない・読込み失敗・オフライン時は枠自体を出さない。
/// 末尾への追記なので既存の行やボタンの位置は動かない (CLS対策)。
struct AdBannerSlot: View {
    @ObservedObject private var coordinator = AdCoordinator.shared

    var body: some View {
        if coordinator.canRequestAds {
            VStack(spacing: 4) {
                Text("広告")
                    .font(NightTheme.font(11))
                    .foregroundStyle(.secondary)
                AdBannerViewRepresentable()
                    .frame(height: 50)
            }
            .frame(maxWidth: .infinity)
            // フローティングタブバーと重ならないよう下に余白 (SE等の小画面で末端に来ても隠れない)
            .padding(.bottom, 24)
        }
    }
}
