import SwiftUI

/// タイトルコード編集シート (Android TitleCodeDialog と同じ構造):
/// 「必ず鳴らす」「鳴らさない」コードリスト + 適用範囲 (両方/開始のみ/通知分のみ)。
struct TitleCodesSheet: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var engine: Engine
    @Environment(\.dismiss) private var dismiss

    @State private var always: [String] = []
    @State private var never: [String] = []
    @State private var scope = 0 // 0=両方 1=開始のみ 2=通知分のみ

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    CodeEditor(title: "必ず鳴らすコード", codes: $always)
                }
                Section {
                    CodeEditor(title: "鳴らさないコード", codes: $never)
                }
                Section {
                    Picker("適用範囲", selection: $scope) {
                        Text("両方").tag(0)
                        Text("開始のみ").tag(1)
                        Text("通知分のみ").tag(2)
                    }
                    .pickerStyle(.segmented)
                }
            }
            .navigationTitle("タイトルコード")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("キャンセル") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        store.setTitleCodes(TitleCodeSettings(
                            alwaysCodes: always, neverCodes: never,
                            applyToStart: scope != 2, applyToReminders: scope != 1,
                        ))
                        Task { await engine.resync(reason: "title codes") }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
            .onAppear {
                let tc = store.state.titleCodes
                always = tc.alwaysCodes
                never = tc.neverCodes
                scope = tc.applyToStart && tc.applyToReminders ? 0 : (tc.applyToStart ? 1 : 2)
            }
        }
    }

    /// コード入力+一覧。元アプリ同様、同一リストあたり15個まで。
    private struct CodeEditor: View {
        let title: String
        @Binding var codes: [String]
        @State private var input = ""

        var body: some View {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(NightTheme.font(13, weight: .medium))
                HStack {
                    TextField("例: -w", text: $input)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("追加") {
                        let v = input.trimmingCharacters(in: .whitespaces)
                        guard !v.isEmpty, !codes.contains(v), codes.count < 15 else { return }
                        codes.append(v)
                        input = ""
                    }
                    .disabled(input.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                if !codes.isEmpty {
                    FlowLayout(spacing: 6) {
                        ForEach(codes, id: \.self) { code in
                            Button {
                                codes.removeAll { $0 == code }
                            } label: {
                                HStack(spacing: 4) {
                                    Text(code).font(NightTheme.font(12))
                                    Image(systemName: "xmark").font(.system(size: 9))
                                }
                                .padding(.horizontal, 10).padding(.vertical, 5)
                                .background(NightTheme.indigo.opacity(0.15), in: Capsule())
                                .foregroundStyle(NightTheme.indigo)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }
}

/// iOS 15+ 互換の簡易フローレイアウト (FlowRow 相当)。
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineH: CGFloat = 0, maxW: CGFloat = 0
        for v in subviews {
            let size = v.sizeThatFits(.unspecified)
            if x + size.width > width { x = 0; y += lineH + spacing; lineH = 0 }
            x += size.width + spacing
            lineH = max(lineH, size.height)
            maxW = max(maxW, x)
        }
        return CGSize(width: maxW, height: y + lineH)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineH: CGFloat = 0
        for v in subviews {
            let size = v.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX { x = bounds.minX; y += lineH + spacing; lineH = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineH = max(lineH, size.height)
        }
    }
}
