import UIKit
import UniformTypeIdentifiers

/// 共有シート「予定に変換」エントリ (元アプリのメール→予定に相当)。
/// 拡張側は薄い受け口に徹する: 本文を App Group コンテナへ置き、
/// カスタムスキームで本体アプリを開くだけ。抽出・確認・保存は本体で行う
/// (APIキー・EventKit・確認UIが本体側にあるため)。
final class ShareViewController: UIViewController {

    private let groupId = "group.com.calendaralarm.ios"
    private let appUrl = URL(string: "calendaralarm-import://mail")!

    private lazy var label: UILabel = {
        let l = UILabel()
        l.text = "予定に変換中…"
        l.textAlignment = .center
        l.translatesAutoresizingMaskIntoConstraints = false
        return l
    }()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
        extract()
    }

    private func extract() {
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let providers = items.compactMap { $0.attachments }.flatMap { $0 }
        // メール共有は text/plain が本命。無ければ URL (webページ共有) も拾う
        if let p = providers.first(where: {
            $0.hasItemConformingToTypeIdentifier(UTType.plainText.identifier)
        }) {
            p.loadItem(forTypeIdentifier: UTType.plainText.identifier) { [weak self] item, _ in
                let text = (item as? String) ?? (item as? NSAttributedString)?.string
                DispatchQueue.main.async { self?.handOff(text) }
            }
        } else if let p = providers.first(where: {
            $0.hasItemConformingToTypeIdentifier(UTType.url.identifier)
        }) {
            p.loadItem(forTypeIdentifier: UTType.url.identifier) { [weak self] item, _ in
                DispatchQueue.main.async {
                    self?.handOff((item as? URL)?.absoluteString)
                }
            }
        } else {
            fail("テキストを受け取れませんでした")
        }
    }

    /// 本文を App Group へ書いて本体アプリを起動する。
    private func handOff(_ text: String?) {
        guard let text = text, !text.isEmpty else { return fail("テキストを受け取れませんでした") }
        guard let dir = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: groupId) else {
            return fail("アプリ間の受け渡し領域がありません")
        }
        do {
            try text.write(
                to: dir.appendingPathComponent("mail-share.txt"),
                atomically: true, encoding: .utf8,
            )
        } catch {
            return fail("共有データを保存できませんでした")
        }
        openHostApp()
    }

    /// 拡張から本体アプリを開く。extensionContext.open はカスタムスキームを
    /// 拒否するため、レスポンダチェーン経由で UIApplication.open を呼ぶ
    /// (共有拡張→本体起動の確立手法)。
    private func openHostApp() {
        var responder: UIResponder? = self
        while let r = responder {
            if let app = r as? UIApplication {
                app.open(appUrl)
                break
            }
            responder = r.next
        }
        extensionContext?.completeRequest(returningItems: nil)
    }

    private func fail(_ message: String) {
        label.text = message
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in
            self?.extensionContext?.cancelRequest(
                withError: NSError(domain: "MailShareExt", code: 1))
        }
    }
}
