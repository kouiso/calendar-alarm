IN-PROGRESS 2026-11-14 07:28 UTC

calendar-alarm [cloud]

使命（持ち主の言葉）: 「どのプロジェクトもまず既存のPRを本質的に対処することを作業を優先してください。そうしないと溜まってばっかりなので」

## 2026-11-14 06:55 UTC の数

- 開いている PR 10（うち #141 は対象外、#358 はこの記録）/ 開いている issue 174

## 2026-11-14 07:28 UTC の進み

### 取り込んだ・閉じた（持ち主の常の言葉「レビューが通った PR は聞かずに取り込む」による）

- 取り込み: #362（7561d42）、#359（f780ef6）、#364（e129dd5）。どれも独立レビュー must 0、CI 成功、未解決の指摘 0 を確かめてから squash で入れた。
- #360: master を取り込み直した（609a682。衝突なし、手元 193 件 失敗 0）。CI が通ったら取り込む。その後 #361 も同じ手順で入れる。
- 閉じた PR: #141（広告。持ち主の答え Q72「入れない」。ブランチは残した）。
- 閉じた issue: #140・#196（広告。今はやらない）、#254（#359 で完了）、#297（#364 で完了）。
- 画面が変わる PR（#191・#188・#363・#189）は、依頼文の「持ち主の画面確認が要る」に従い、まだ入れない。#191 は ebd28fe で must 0（Android 8 の優先モードの件を直した後）。

### advisor の決まり（manager からの依頼への答え）

- 主の model と effort: Opus 5.5、medium。
- advisor: on（Fable）。このセッションで `/advisor fable` が効いている。Sonnet・Haiku の作業者には「助言が要る時は Opus の lead に上げる」と頼む。
- 手順 3（リポジトリの `.claude/settings.json` に `"advisorModel": "fable"` を足す PR）: 作れなかった。AI の設定ファイルを書き換える操作を、このセッションの安全の確認が止めた。回り道はしていない。OWNER: 持ち主が自分でこの 1 行を足すか、この種の操作をこのセッションに許す設定を足す必要がある。

### 持ち主の答え（2026-11-14）の反映

- Q72（広告）: 入れない → 上のとおり閉じた。
- Q77（言語）: 公開は日本向け、ただし多言語の仕組みは最初に作る → これまで「新しい機能なので着手の許可が要る」としていた文言の移し替え（#288・#290・#292・#313・#291・#293）は、許可が出たものとして次に着手する。順は #313 → #292 → #290 → #288 → #291 → #293（小さく、他の PR と重ならない物から）。
- Q71（実機の音）: 時間切れ。音は出さない。

## PR の今（2026-11-14 07:15 UTC）

| PR | 区分 | head | CI | 独立レビュー | 足りないもの（持ち主） |
|---|---|---|---|---|---|
| #362（#309） | a | 68166d0 | 成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/362#issuecomment-6871833158) | 「merge #362」 |
| #359（#231） | a | 804760a | 成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/359#issuecomment-6871690563) | 「merge #359」 |
| #360（#232） | a | aa78f2b | 成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/360#issuecomment-6871728088) | 「merge #360」（#359 の後。master を取り込み直す） |
| #361（#243） | a | 8c28816 | 成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/361#issuecomment-6873615277) | 「merge #361」（#360 の後。master を取り込み直す） |
| #364（#297） | b | dc98b92 | 実行中 | レビュー中 | レビューの結果 |
| #191（#149） | c | 3a66da2 | 実行中 | レビュー中（OFF の意味を判断役の決定に合わせて直した後） | 画面の確認、端末での比較、「merge #191」 |
| #188（#154） | c | aecf600 | 成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/188#issuecomment-6873609241) | 画面の確認、外観の料金の判断、「merge #188」 |
| #363（#204） | c | 0e7eb9e | 成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/363#issuecomment-6873503995) | 画面の確認と前後のスクリーンショット、「merge #363」 |
| #189（#147） | c | fc92a5f | 成功（Android だけ） | [must 0（読んだだけ）](https://github.com/kouiso/calendar-alarm/pull/189#issuecomment-6871360657) | Mac での iOS ビルドとテスト 2 件、実機での鳴動、画面の確認、「merge #189」 |
| #141 | 対象外 | - | - | - | 依頼文どおり触っていない |
| #358 | この記録 | - | - | - | - |

### プロダクトの判断（判断役 Fable の決定。2026-11-14 07:15 UTC）

1. #191 の OFF の意味: OS に従う。OS がアラームを通す時は鳴らし、止める時だけ鳴らさない。→ 3a66da2 で入れた。
2. #191 のスイッチ: 許可が無くても押せるまま。ON で許可が無い時は、その状態をスイッチの所に出す。→ 3a66da2 で入れた。#149 のデザイン案（押せない）とは違う。画面の確認で見てほしい。
3. #188: フォント選択は持たない。iOS は各画面がテーマに従うところまで。→ aecf600 で仕様書に書いた。料金は持ち主のまま。
4. #189: 「任意の MP3 は使えない」とは書かない（実際に使えるため）。代わりに「選ぶ」の近くに、使えるファイルの種類と、先頭の約 30 秒だけを使うことを書く。→ Swift の文言なので Mac で入れる（DEVICE）。#147 の終了条件の言い換えも要る。
5. #255 の残り（画面の再同期の呼び出しを 1 か所へ）: #359・#188・#191・#363 が全部入ってから。
6. #236: 先に #265（エミュレータで今は鳴らないことの記録）。`emulator_verify.yml` で取れる見込み（未確認）。その後、#360 が入ってから #266 を小さく作る。

## マージ（取り込み）について

- RESUME の文は「取り込みを任せる」と言うが、最初の依頼文には「このチャットで持ち主が merge #N と打った時だけマージする。持ち主のものらしい GitHub の承認でも足りない」とある。取り込みは元に戻しにくく、別のセッション経由の委任はこの条件を満たさない。
- そこでマージはしない。どの PR も、独立レビューが must 0・CI 成功まで持っていき、下の OWNER に「merge #N を打てば入る」と書く。
- 持ち主がこのチャットで「merge #359」などと打てば、すぐ入れる。

## 残りの issue 145 件の仕分け（2026-11-14 07:14 UTC）

### IN-PR（開いている PR が作業を持つ）

- #204: PR #363
- #254: PR #359
- #255: PR #359
- #257: PR #360
- #258: PR #360
- #282: PR #361
- #297: PR #364
- #309: PR #362

### DEVICE（端末・エミュレータ・スクリーンショットが要る）

- #221: iOS 26+ 多機種の実機 E2E 基盤（選択肢比較は DOC で可、PoC と複数機種実行は実機/Mac）。費用ゼロ既定はオーナー決定済み
- #261: 裏の更新が実機でどの頻度で走るか計測
- #265: ロック解除前の再起動でアラームが鳴らないことを emulator/実機で記録
- #267: ロック解除前に鳴動画面と音が動くことを emulator/実機で確認
- #270: バックアップから戻した端末で設定の付き先・見逃し通知を確認（実機/emulator）
- #274: 実機か Mac で端末カレンダーの終日予定の開始が現地0時で返ることを確かめる
- #337: ロック中と他アプリ上の RingingOverlay を emulator/実機で撮って鳴動画面と並べる
- #338: emulator/実機 360x740dp のライト・ダーク screenshot を板と並べる（予定画面。コードの形そろえ自体は Kotlin で可）
- #340: emulator/実機 360x740dp のライト・ダーク screenshot を板と並べる（アラーム一覧・編集。コードの形そろえ自体は Kotlin で可）
- #342: emulator/実機 360x740dp のライト・ダーク screenshot を板と並べる（タイマー/ストップウォッチ。コードの形そろえ自体は Kotlin で可）
- #344: emulator/実機 360x740dp のライト・ダーク screenshot を板と並べる（設定画面。コードの形そろえ自体は Kotlin で可）
- #346: emulator/実機 360x740dp のライト・ダーク screenshot を板と並べる（鳴動画面・通知。コードの形そろえ自体は Kotlin で可）
- #348: emulator/実機 360x740dp のライト・ダーク screenshot を板と並べる（権限の画面。コードの形そろえ自体は Kotlin で可）
- #350: emulator/実機 360x740dp のライト・ダーク screenshot を板と並べる（許可を頼む画面。コードの形そろえ自体は Kotlin で可）
- #352: emulator/実機 360x740dp のライト・ダーク screenshot を板と並べる（下タブ MainActivity.kt。コードの形そろえ自体は Kotlin で可）
- #354: emulator/実機 360x740dp のライト・ダーク screenshot を板と並べる（TroubleshootingScreen。コードの形そろえ自体は Kotlin で可）
- #356: targetSdk 35→36 (gradle/libs.versions.toml; code S) の後、emulator で予約・鳴動・停止・スヌーズ・再起動後鳴動を確認し Android 16 挙動変更の確認結果を記録
- #266: Direct Boot の作り直し。判断役の決定で、先に #265（エミュレータでの再現の記録）を取り、#360 が入ってから小さく作る。

### DEVICE（iOS。Mac と Xcode が要る。この Linux では作れない）

- #219: iCloud/EventKit 正式対応。合格は実機 iOS 26+ で iCloud 予定が AlarmKit で鳴ること
- #256: iOS 設定変更から再同期へ
- #259: EKEventStoreChanged で再同期
- #260: BGAppRefreshTask 追加
- #262: 最後の取り込み時刻を予定画面とウィジェットに
- #263: CAPACITY_SKIP を失敗に数えない
- #264: iOS 「○月○日 ○時の分まで予約済み」表示
- #273: iOS に「すべてミュート中」表示
- #276: EventKit 終日を UTC 0時に直して渡す
- #277: 起点日読み込みを保存の逆変換にし TZ 往復テスト
- #279: iOS 同じ直し（今日鳴るか）
- #281: iOS 一覧「次」に日を入れる
- #283: iOS に同じ直し（指定の引き継ぎ）
- #285: iOS 保存と画面に入れる（全部/この回だけ）
- #287: iOS 診断共有の入口
- #294: iOS 日付時刻表示を端末設定に
- #295: 設定画面を Localizable.xcstrings で引き英語追加
- #296: 天気の言語を端末に合わせる
- #302: 同じ表を Swift テストで読み iOS 写しと照合
- #303: SharedBridge へ判定を移し Swift の写しを消す
- #305: iOS resync を shared の突き合わせに差し替え。Mac と実機
- #307: iOS 側の種類判定を直す（#306 の決定待ちでもある）
- #310: ci.sh に ios-test(xcodebuild) 追加＋macOS job。Mac/macOS CI で XCTest 11本が走ること
- #311: ci.sh ios-logic に swiftc 2本をまとめる（Mac で走らせる検証）。スクリプト/文書変更自体は Linux でも書けるが合格は Mac 実行
- #312: iOS 題の既定を文字の表から引く
- #326: iOS 設定・予定詳細の鳴らし方/終日の言葉
- #328: iOS でテーマ既定の時の印とインディゴ保存を直す
- #331: iOS 予定行の「○分前」札を下段へ
- #334: iOS 予定行の鳴らない理由1行（#333 の後）
- #339: Swift/SwiftUI の形そろえ＋Mac のビルドと機種別 screenshot
- #341: Swift/SwiftUI の形そろえ＋Mac のビルドと機種別 screenshot
- #343: Swift/SwiftUI の形そろえ＋Mac のビルドと機種別 screenshot
- #345: Swift/SwiftUI の形そろえ＋Mac のビルドと機種別 screenshot
- #347: Swift/SwiftUI の形そろえ＋Mac のビルドと機種別 screenshot
- #349: Swift/SwiftUI の形そろえ＋Mac のビルドと機種別 screenshot
- #351: Swift/SwiftUI の形そろえ＋Mac のビルドと機種別 screenshot
- #353: Swift/SwiftUI の形そろえ＋Mac のビルドと機種別 screenshot

### OWNER（持ち主の判断・操作が要る）

- #140: 未チェック項目は同意管理・ストア申告・本番 AdMob 設定。iOS 証跡の扱いもオーナー判断。PR #141 は open
- #203: 「タイトルコード」を削除するか維持するかの判断（必要性の記録は DOC で先行）。既存保存値の移行設計が要る
- #208: メール/端末通知/アラームの経路別ON/OFF。メール実現可否の先行調査と判断。#213/#215 と重複範囲あり
- #209: ロゴ/アイコン3案比較をオーナーが採用承認（デザイン好み）
- #210: ストア公開前の公開可否判定表・法務/プライバシー/サポート情報・オーナー承認
- #211: 設定画面の各機能の存廃判断（監査表は DOC で先行可）。#203 #217 #218 を含む上位
- #213: 「カレンダーの通知設定を使う」を popup/email に分離する設計判断（Google 書込 scope 含む）。#208/#215 と重複範囲あり
- #214: App Store 提出・TestFlight 実機検証・オーナー承認後の申請
- #215: Google 側 popup/email の書込可否・一括適用の方針判断。#213 #208 #207 と範囲が重なる（統合要検討）
- #216: ポートフォリオ掲載。公開ステータス確認と文面・モバイル表示のオーナー確認が要る
- #217: スヌーズ時間プリセットの維持/整理/削除の判断。先に使用箇所の監査は DOC 可
- #218: 設定の「人＋/ⓘ/再同期」アイコンの採否（存廃判断）と導線の再設計。監査記録は DOC で先行可
- #268: カレンダー設定を引く組（アカウント+名前など）の持ち主の判断待ち。出たら Android+移行 L
- #278: 鳴動 id に時刻を含めるか終端行を消すか、今日鳴らすかの持ち主の判断待ち。出たら shared+Android M
- #284: 全部の回への指定を入れるか・優先順位の持ち主の判断待ち。出たら shared+Android L
- #286: 診断の記録共有。親 #245 の持ち主の判断（共有文字で予定の題を伏せるか）待ち。出たら Android M
- #298: クラウド環境へ Android SDK を入れる準備。SDK 取得先への通信と環境登録に持ち主の承認が要る（セットアップスクリプトと AGENTS.md 追記は書ける）
- #306: 題による種類判定の直し方の案を持ち主が選ぶまで着手不可（#201 コメント）。出たら shared S-M
- #332: 親 #315 の持ち主の判断（理由の種類）待ち。出たら shared+Android S-M（AgendaItem に理由種別）
- #333: 親 #315「持ち主の判断が要る点」（鳴らない理由の種類と文言）の答えが出るまで着手不可。出たら Android S-M

### OWNER（新しい機能の作業。依頼文が「新しい機能の PR は作らない」としているので、着手の許可が要る）

推し: 依頼の範囲を広げてよければ、小さい物（S）から 1 つずつ PR にする。#271 → #272、#269、#327、#330、#325、#275 の順を推す。

- #205: アカウントごとのグルーピング自体は実装済み(AccountRules.groupByAccount)。足りないのはアカウント行の件数表示と折り畳み/展開と保持（AccountHeader に無い）。目視確認は emulator
- #206: アカウント一括 OFF は「このアカウントの予定で鳴らす」(SettingsScreen AccountHeader/AccountRules)で実装済み。残りは OFF 中の子行の抑止表示・再同期での取消と ON 復元のテスト・iOS（未確認）
- #207: 元カレンダー通知設定の継承。調査表（DOC）から。#75/#142 で既実装の部分が多い可能性（未確認）。差分のみ実装
- #220: 配布物の逆解析対策。Android(R8/難読化/secret scan CI)は Linux で可、iOS・配布物の E2E は Mac/実機。SBI 未分割。脅威モデルは DOC から
- #222: 全非同期ボタンの押下フィードバック。SBI 未分割（本文に SBI-1..5 候補）。Android の Compose 実装＋テストは可、iOS は Mac、操作証跡は emulator。まず棚卸しの DOC から
- #266: BootReceiver を directBootAware にし LOCKED_BOOT_COMPLETED で端末保護領域の控えから setAlarmClock を張り直す。Robolectric（Manifest 現状 directBootAware 無…
- #269: scheduled_instances と event_overrides を dataExtractionRules/fullBackupContent で除外（Manifest 35行は allowBackup=true のみ、res/xml に規則無し）…
- #271: RingStatus.Kind に「すべてミュート中」を足し許可不足の次に強く出す（現 Kind は ALARM_MUTED=音量0 のみ）。shared 単体テスト
- #272: 予定・アラーム両タブ上に「すべてミュート中 ― 解除」帯、アラーム行を鳴らない見た目に（#271 後）。目視は emulator
- #275: CalendarEvent の注釈に終日開始は UTC 0時と明記し、東京ゾーンで UTC 0時/現地0時の展開日付をテスト固定（shared commonTest）
- #280: 一覧「次」を今日/明日/11/15(土) 形にし、保存直後に「明日 7:00 に鳴ります」を1回知らせる。日付文字の単体テスト
- #288: SettingsScreen の直書き日本語を文字リソース化し英語追加（量が多い）
- #290: AgendaScreen の直書き日本語を文字リソース化し英語追加
- #291: shared RingStatus を文ではなく種類と数で返し、Android の文字リソースで引く
- #292: 鳴動画面・通知の直書き日本語と shared 題の既定を文字リソース化し英語追加
- #293: Locale.JAPAN と "H:mm" 決め打ち廃止、端末の地域・12/24h に従う書式＋時刻入力。地域違いの単体テスト（12/24h の目視は emulator）
- #300: all_check.yml に lintDebug を足し lint-baseline.xml に既存指摘を固定。baseline 生成には Android SDK が要る（ここに SDK 無し・未確認）
- #301: 同じ入力/期待値の JSON 表を置き shared の判定と Kotlin テストで照合（:shared:jvmTest）
- #304: AlarmRepository.kt の行の残す/予約/取消/見逃し判定を shared AlarmPlanner の純粋関数へ出す。先に現行動作固定テスト。#258 の後
- #313: shared SettingsPreview の「アプリ」「Google」を種類で返し、Android は strings.xml で引き英語追加。shared 単体テスト
- #325: 設定・予定画面の言葉: 3択「アラーム/通知のみ/鳴らさない」、「5分前」形、終日OFF→鳴らさない 等。360dp で切れないテスト
- #327: SettingsScreen のテーマ丸: 保存値 "default" で琥珀に選択印、ダークで丸をダーク色にする(現状 1477/1485行は default を INDIGO に印)。テスト固定
- #329: NotificationRulesCard のルール行タップで編集ダイアログ（上書き保存・削除）。Robolectric＋shared NotificationRuleSpecTest
- #330: AgendaScreen EventRow の「○分前」札を予定名の下段へ移す。幅 360dp で「定例ミーティング」が切れないテスト
- #335: TroubleshootRow に状態の札(OK/要対応/対象外/参考)と「設定を開く」を出す。strings.xml に OK 追加。2倍文字の Robolectric テスト
- #336: RingingOverlay を鳴動画面と同じ形・テーマ色にし、日付/二重の輪/○分後に開始を足す。Robolectric で位置・大きさを比べるテスト
- #289: 画面のコードに日本語を直書きしたら CI で止める検査。既存の直書きの基準ファイルが要る。上と同じく着手の許可が要る。
- #299: `scripts/test-ci-contract.sh` の守りと軽い CI のジョブ。#364（#297）が入った後。上と同じく着手の許可が要る。

### 子の issue が作業を持つ PBI（PARENT。子が全部片付くと閉じられる）

- #201: 勤務場所・誕生日・不在の既定OFF
- #223: SOLID 監査の段階的リファクタ
- #231: 設定→再同期（子の Android 2件は PR #359）
- #232: 競合の直し（子は両方 PR #360）
- #233: iOS 再同期
- #234: iOS 最終取り込み時刻
- #235: iOS 予約枠
- #236: ロック中の再起動で鳴動
- #237: バックアップ/機種変更
- #238: ミュート中表示
- #239: iOS 終日予定
- #240: iOS 周期アラーム起点日
- #241: 今日鳴るかの表示と合わせる（owner decision #278）
- #242: 次に鳴る日時の表示
- #243: 指定の引き継ぎ
- #244: 全部の回への指定（owner decision gate #284）
- #245: 診断ログ共有（owner decision gate #286）
- #246: 設定画面の英語化
- #247: 予定画面の英語化
- #248: 鳴動画面・通知の英語化
- #249: 日付時刻の地域対応
- #250: iOS 英語化
- #251: ci.sh fast がクラウドで通ること
- #252: 契約テスト CI は子で
- #253: lint CI 化は子で
- #315: 鳴らない理由の種類・文言は持ち主の判断待ち
- #316: 形そろえは各 SBI。screenshot 並置が合格条件（Android=device, iOS=Mac）
- #317: 形そろえは各 SBI。screenshot 並置が合格条件（Android=device, iOS=Mac）
- #318: 形そろえは各 SBI。screenshot 並置が合格条件（Android=device, iOS=Mac）
- #319: 形そろえは各 SBI。screenshot 並置が合格条件（Android=device, iOS=Mac）
- #320: 形そろえは各 SBI。screenshot 並置が合格条件（Android=device, iOS=Mac）
- #321: 形そろえは各 SBI。screenshot 並置が合格条件（Android=device, iOS=Mac）
- #322: 形そろえは各 SBI。screenshot 並置が合格条件（Android=device, iOS=Mac）
- #323: 形そろえは各 SBI。screenshot 並置が合格条件（Android=device, iOS=Mac）
- #324: open children: 354（Android screenshot 要）；#335 は別 PBI #228 の子

## issue の仕分け（PR が紐づく 29 件。2026-11-14 07:07 UTC）

サブエージェントが issue の終了条件と、紐づく PR・master のコードを突き合わせた。DONE-ALREADY（もう閉じてよい）は 0 件。紐づく PR は、どれも `Refs` と `No-Close` で、端末での確認が残ると書いている。

### DEVICE（端末・エミュレータ・Mac が要る。コードは master にある）

- #200: real-device lock-screen drag-to-stop repro: lock/screen-off ring, finger slide, intermittent non-acceptance repro, OS input vs Compose vs handler isol…
- #155: emulator/device check of accept/tentative/declined ring differences (受入条件, PR #180 No-Close)
- #153: emulator/device check: permission OFF -> item detected -> settings guidance works
- #151: display diff for each ON/OFF (emulator/simulator); design-canonical comparison; PR #181 toggles next ALARM not next EVENT (semantic mismatch noted by …
- #148: emulator check of volume, crescendo, duration auto-stop; explicit spec of crescendo toggle vs existing 30s crescendo not found in doc/ (未確認)
- #146: emulator check of ring with picked MP3 and fallback after file deletion; 360dp button crush finding (review) not confirmed fixed
- #145: emulator/device check of snooze-minute change and auto-snooze up to cap; iOS side of auto-snooze not in PR (Android only) (未確認)
- #144: create all-day event -> ring at set time on Android and iOS; also OWNER: body says default 10:00 but spec and code default 9:00 (not owner-confirmed)
- #143: emulator/device check that common reminder auto-applies to new events; 14-day-ahead finding fix unconfirmed
- #139: power-saving (Doze) and TZ/time-change behaviour on real device; own iOS real-device confirmation of substitute behaviours
- #136: real iPhone check that lock-screen slide stop does not re-ring after narrowing; narrowing of via=boot rule not confirmed
- #130: real iPhone: whether stop intent runs for lock-screen stop, in-app stop, banner swipe, power button; alternative cues if not
- #124: before/after screenshots in PR comments, iOS real-device results, #118 evidence decision, other-bot review OK
- #101: real-device confirmation of what AlarmKit can do (.named playback, volume, vibration); settings text for unsupported items
- #68: real Android and iOS device: deny all permissions, grant all from permission screen, ring and stop; before/after dark/light screenshots per PR
- #61: real iPhone repro: snooze with non-default minutes, kill app, stop on lock screen, reopen and check RECOVER/MISSED re-ring

### OWNER（issue）

- #212: Play Console account/listing/Data safety/store submission need owner approval; real-device alarm/Crash/ANR tests; child SBIs e.g. #309 open
- #196: owner yes/no to merge #141 without/with iPhone evidence; iPhone before/after screenshots in repo (need Mac/simulator); #141 not merged
- #194: PR ごとに macOS で iOS をビルドする CI。macOS の runner を使ってよいか（費用の判断）が持ち主待ち。推し: 公開リポジトリの標準 runner なら追加の費用は無いので、paths で iOS の変更時だけに絞って足す。
- #197: issue・PR の書き方の自動チェック。判定の規則（どの言葉を止めるか）を持ち主から受け取っていない。推し: 規則が届くまで保留。

### 子の issue が作業を持つ PBI（PARENT）

- #229: child SBI #336 (overlay UI) and #337 (emulator/device check) open; theme colour/date/ring/minutes-before not in RingingOverlay.kt; parity test and scr…
- #228: child SBI #335 open; status words/'設定を開く' strings in TroubleshootingScreen not done
- #227: children #330 (Android) and #331 (iOS, Mac) open; badge-below-title layout not done
- #226: child #329 open; tap-to-edit/delete in NotificationRulesCard and save->reload->match tests not done
- #225: children #327 (Android) and #328 (iOS, Mac) open; default-theme marker fix and test not done
- #224: children #325 (Android) and #326 (iOS, Mac) open; wording unification and word-change list comment not done

### PR が作業中（NOT-DONE のうち PR がある物）

- #147 → #189、#149 → #191、#154 → #188。

## OWNER（持ち主だけが出せるもの）

- 「merge #N」: 上の表の a の PR。#362 → #359 → #360 → #361 の順を推す（画面は変えない）。
- 画面の確認: #191・#188・#189・#363（区分 c）。
- #188: 外観を無料にするか Pro と連動させるか（料金の判断）。

## DEVICE

- #191: おやすみモード中の ON/OFF の端末での比較。
- #188: 大ボタンの ON/OFF の見た目と停止の操作、TalkBack。
- #189: Mac での iOS ビルド、テスト 2 件、実機で 2 つ以上のプリセットが鳴ること。
- #363: 前後のスクリーンショット。
- #236: エミュレータでの再起動の再現（#265）。

# クラウドでの引き継ぎ作業の記録（calendar-alarm）

新しい記録が上。数字は PR か issue の番号。

## 2026-11-14 04:54 UTC 時点

区分ごとの数: a（マージできる）3 / b（ここで直せる・レビュー中）2 / c（端末・画面・持ち主の判断が要る）4 / d 0
対象外: #141（広告。触っていない）

| 番号 | 区分 | やったこと | head | checks | レビュー | マージ済みか / 足りないもの |
|---|---|---|---|---|---|---|
| #363（#204） | b | 設定のチップの行（招待予定・通知タイミング・スヌーズの選択肢）を折り返すようにした。360dp・文字 1.3 倍で右端のチップが幅 0 に潰れるのを、テストで再現してから直した | 0e7eb9e | 手元 unit 189 件 失敗 0、lint 成功 | [must 0（3a7f1f5）](https://github.com/kouiso/calendar-alarm/pull/363#issuecomment-6871929498)。should 1 は直した。差分の再レビュー中 | 未マージ。画面が変わるので、持ち主の画面確認と前後のスクリーンショットが要る（区分 c になる見込み） |
| #362（#309） | a | versionCode を `-PversionCode` から読み、配布の workflow が run number を渡す。手元で合成後の manifest を見て確かめた。配布の workflow は動かしていない | 68166d0 | CI 全部成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/362#issuecomment-6871833158) | 未マージ。持ち主の「merge #362」で入れられる。番号が上がることは次の配布で確かめる |
| #361（#243） | b | 予定の時刻が動いても予定ごとの指定を引き継ぎ、終わった回の指定を消す（shared の OverrideCarry と再同期）。レビューの must 2 回（別の回へ付け間違える 2 つの形）を直した | cb05f1f | 手元 shared 114+114 件・androidApp 189 件 失敗 0、lint 成功 | [1 回目 must 1](https://github.com/kouiso/calendar-alarm/pull/361#issuecomment-6871836927)、[2 回目 must 1](https://github.com/kouiso/calendar-alarm/pull/361#issuecomment-6871963689)。3 回目のレビュー中 | 未マージ。レビューの結果待ち。iOS（#283）は Mac が要る |
| #360（#232） | a | 前の表のとおり | aa78f2b | CI 全部成功 | [差分の再レビュー must 0](https://github.com/kouiso/calendar-alarm/pull/360#issuecomment-6871728088) | 未マージ。持ち主の「merge #360」で入れられる |
| #359（#231） | a | 前の表のとおり | 804760a | CI 全部成功 | [差分の再レビュー must 0](https://github.com/kouiso/calendar-alarm/pull/359#issuecomment-6871690563) | 未マージ。持ち主の「merge #359」で入れられる |
| #236 | c | 手を付けていない | - | - | - | ロック解除の前に鳴らすには、受信・鳴動のサービス・音を、DB を読めない状態（Direct Boot）で動かす必要がある。エミュレータで確かめずに入れると、起動時に落ちる危険がある。子の #265（今は鳴らないことをエミュレータで記録する）が先。推し: #265 をエミュレータのある端末で行い、その後で #266 を小さく作る |

### マージの順の提案

1. #362（ビルドの設定だけ。他と重ならない）
2. #359 → #360 → #361（どれも `AlarmRepository.kt` を変える。1 つ入れるたびに、次の PR へ master を取り込み直し、テストと差分のレビューをやり直す）
3. 画面の PR（#191・#188・#363・#189）は、持ち主の画面確認の後。#188・#191・#363 は `SettingsScreen.kt` を変えるので、同じく 1 つずつ。

## 2026-11-14 04:27 UTC 時点（再開後）

区分ごとの数: a 0 / b 2（#359・#360。差分の再レビュー待ち）/ c 3（#191・#188・#189）/ d 0
次: #243 → #236・#204・#309

| 番号 | 区分 | やったこと | head | checks | レビュー | マージ済みか / 足りないもの |
|---|---|---|---|---|---|---|
| #360（#232） | b | 再同期の書き戻しを鳴動・停止・スヌーズと同じロックに入れ、途中で鳴った・止めた・スヌーズした行を PENDING に戻さない。テスト 4 件（直す前は落ちる） | aa78f2b | 手元 unit 190 件 失敗 0、lint 成功 | [must 0（15a73c6）](https://github.com/kouiso/calendar-alarm/pull/360#issuecomment-6871643208)。should 1 は直した。差分の再レビュー中 | 未マージ。画面は変えない。持ち主の「merge #360」で入れられる |
| #359（#231） | b | 「何分前」「スヌーズ」の既定を変えたら再同期する見張りを足した。テスト 3 件（直す前は 2 件が落ちる） | 804760a | 手元 unit 189 件 失敗 0、lint 成功、3 回の再実行で成功 | [must 0（fe3ae60）](https://github.com/kouiso/calendar-alarm/pull/359#issuecomment-6871554433)。should 2 件は直した。差分の再レビュー中 | 未マージ。画面は変えない。持ち主の「merge #359」で入れられる |

### 新しい不具合の候補（issue にはしていない）

- 再同期の外にも、行を読んでから状態を無条件で書く所が残っている。`cancelStaleWaitingEvents`（CANCELLED）、`markMissed()`（MISSED）、`rearmWaitingRings`（`setPendingIfFired` の前後）。読んだ後に停止が入ると上書きされる。提案: どれも `ringTransitionLock` の中で読み直すか、条件付きの UPDATE にする。（#360 のレビューの should 2）
- #359 と #360 は同じ `AlarmRepository.kt` を変える。後でマージする方は、master を取り込み直して、もう一度テストとレビューを通す。

## 2026-11-14 04:05 UTC 時点

区分ごとの数: a（マージできる）0 / b（ここで直せる）0 / c（端末・画面の証拠・本番・持ち主の判断が要る）3 / d（不要・置き換え済み）0
対象外: #141（広告。触っていない）
次: #231 → #232 → #243 → #236・#204・#309

| 番号 | 区分 | やったこと | head | checks | レビュー | マージ済みか / 足りないもの |
|---|---|---|---|---|---|---|
| #191 | c | master を取り込んだ。チャンネルの選び方を `ringChannelId(vibrate, ignoreDnd)` の 1 つにし、停止手段の判定・MainActivity・検査画面に通した。テスト 2 件を追加（直す前は 2 件とも落ち、直した後は通る）。manifest の表に権限 #15 を追加。本文を差分に合わせた | 15bb42f | CI 全部成功。手元 androidApp unit 198 件 失敗 0、lint 成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/191#issuecomment-6871389425) | 未マージ。足りないもの: OFF の意味の判断（局長確認 9）、おやすみモード中の ON/OFF の端末での比較（確認 8）、持ち主の画面確認 |
| #188 | c | 持ち主のスレッドの指摘どおり、フォントと iOS の範囲を「オーナー判断待ち」にした。master を取り込んだ。レビューの should に合わせ `largeButtons` の既定値を外した。本文を差分に合わせた。スレッドは返信して解決済み | 5f172b2 | CI 実行中（前の head bca0e14 は成功）。手元 unit 188 件 失敗 0、lint 成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/188#issuecomment-6871353363)、[差分の再レビュー must 0](https://github.com/kouiso/calendar-alarm/pull/188#issuecomment-6871414155) | 未マージ。足りないもの: 無料か Pro と連動するか（局長確認 7）、フォント・iOS の範囲の判断、端末での ON/OFF の比較（確認 8）、持ち主の画面確認 |
| #189 | c | master を取り込み、project.pbxproj の 1 か所の衝突を git で直した（両方の行を残した）。本文の食い違い（iOS のビルド成功と書きつつ未確認）を直した。足りないテスト 2 件は Linux に Swift が無いので書いていない（not run） | fc92a5f | CI（Android のビルドのみ）成功 | [must 0（読んだだけ）](https://github.com/kouiso/calendar-alarm/pull/189#issuecomment-6871360657) | 未マージ。足りないもの: Mac での iOS ビルドとテスト 2 件、実機で 2 つ以上のプリセットが鳴る確認（確認 8）、持ち主の画面確認 |

### 直していない指摘（should 以下）

- #191: 鳴動の設定（バイブ・おやすみモード・全画面）がサービス全体の値で、同時の要求で取り違える余地がある（master も同じ形）。設定の読み取りが時間切れの時、短い間 bypass 側の通知が出る。検査画面の `collect(ignoreDnd = true)` に既定値がある。
- #188: 鳴動画面の最初の 1 コマはスライダー。オーバーレイの大ボタンに押した時の波紋が無い。
- #189: 新しい文字列 4 つが Localizable.xcstrings に無い（英語でも日本語で出る）。チップの「標準」と上の行の「iPhone の標準」で呼び名がずれる。#147 の「任意の MP3 は使えないと説明文に書く」が入っていない。同名ファイルがあるとコピーし直さない（音を差し替えても古いまま）。どれも Swift のビルドが要るので、ここでは直していない。

### マージの順の注意

- #188 と #191 は SettingsScreen.kt・SettingsRepository.kt・AlarmService.kt の同じあたりを変える。後でマージする方は、master を取り込み直して、もう一度独立レビューが要る。

## 待っているもの（持ち主）

マージは持ち主がこのチャットで「merge #N」と打った時だけ行う。3 つとも画面が変わるので、持ち主の画面確認も要る。

- #191 の「確認 7」: 依頼文には #191 の確認 7 の答えが無いとあるが、#191・#149 に「確認 7」は無い。#191 で番号が付いているのは 8（端末での比較）・9（OFF の意味）・11（取り込みの時のチャンネル。今回直した）。局長確認 7 は #188 の「外観は全部無料で、Pro と連動しない」のこと（下に別に書いた）。#191 の未決は確認 9 とみなし、提案の文を残す。
  - 確認 9 の提案: 「OFF の時、OS が『アラームのみ』や『重要な通知のみ（アラームは許可）』なら鳴らす」。選択肢: (1) 今のまま、おやすみモード中は全部黙る (2) OS がアラームを許す設定の時は鳴らす。推し: (2)。寝過ごしの危険が小さいため。
- #191: #149 の「権限が無い時はトグルを無効表示」は入っていない。今はトグルはいつでも押せて、案内は検査画面に出る。選択肢: (1) このままでよい (2) 無効表示を足す。推し: (1)。OFF は許可が無くても意味があるため。
- #188 の確認 7: 外観（大ボタン・テーマ・背景）を全部無料にし Pro と連動させないか。選択肢: (1) 全部無料 (2) 一部を Pro に。推し: 判断材料が無いので持ち主に任せる。仕様書は「判断待ち」と書いてある。
- #188: フォント選択を持つか、iOS をどこまで合わせるか。仕様書の提案はフォント無し・iOS はテーマ追随のみ。

## 環境のこと（2026-11-14 時点）

- Android SDK（cmdline-tools・platform 35・build-tools 35.0.0）はこのコンテナに入れられた。止められた host は無い。
- Gradle は `LANG=C.UTF-8` が要る。無いと日本語のテスト名のクラスファイルが書けずにビルドが落ちる。
- Swift・Xcode は無い。iOS のビルドとテストは回せない。
- エミュレータ・実機・音は使っていない。
- GitHub の GraphQL はこのセッションでは使えない（REST のみ）。

## 新しい不具合の候補（issue にはしていない）

- 設定画面の各行が、保存の後に自分で再同期を呼んでいる（#231 の根）。保存する層から再同期を呼ぶ形に寄せた後、画面側の呼び出しを外す片付けは、#188 と #191 が入った後の別の PR にする提案。
