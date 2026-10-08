import SwiftUI

/// 共有拡張から受け取ったメール本文 → AI 抽出 → 確認シート → カレンダー挿入。
/// App Group の mail-share.txt を読んで OpenRouter に投げ、結果をフォームで確定させる。
struct MailImportSheet: View {

    let rawText: String
    let onDone: (_ saved: Bool) -> Void

    @EnvironmentObject var store: Store
    @EnvironmentObject var engine: Engine

    @State private var loading = true
    @State private var error: String? = nil
    @State private var title = ""
    @State private var start = ""
    @State private var end = ""
    @State private var location = ""
    @State private var allDay = false

    var body: some View {
        NavigationStack {
            Form {
                if loading {
                    Section { ProgressView("予定を抽出中…") }
                } else if let error {
                    Section { Text(error).foregroundStyle(.secondary) }
                } else {
                    Section {
                        TextField("タイトル", text: $title)
                        TextField(allDay ? "日付 (YYYY-MM-DD)" : "開始 (YYYY-MM-DDTHH:mm)",
                                  text: $start)
                        if !allDay {
                            TextField("終了 (空=1時間)", text: $end)
                        }
                        TextField("場所", text: $location)
                    }
                }
            }
            .navigationTitle("予定に追加")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("戻る") { onDone(false) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("追加") { save() }
                        .disabled(loading || error != nil || title.isEmpty || start.isEmpty)
                }
            }
        }
        .task { await extract() }
    }

    private func extract() async {
        guard let key = store.state.openRouterApiKey, !key.isEmpty else {
            error = "OpenRouter のキーが未設定です (設定 → AI)"
            loading = false
            return
        }
        do {
            let svc = OpenRouterService(apiKey: key, model: store.state.openRouterModel)
            if let ev = try await svc.extractEvent(text: rawText) {
                title = ev.title
                start = ev.start
                end = ev.end ?? ""
                location = ev.location ?? ""
                allDay = ev.allDay
            } else {
                error = "本文から予定を抽出できませんでした"
            }
        } catch {
            self.error = "抽出に失敗: \(error.localizedDescription)"
        }
        loading = false
    }

    private func save() {
        let id = engine.reader.insert(
            title: title, startIso: start, endIso: end.isEmpty ? nil : end,
            allDay: allDay, location: location.isEmpty ? nil : location,
            notes: nil,
        )
        if id != nil {
            Task { await engine.resync(reason: "mail import") }
            onDone(true)
        } else {
            error = "カレンダーへの書き込みに失敗しました"
        }
    }
}
