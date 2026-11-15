REVIEW | reviewer: general-purpose subagent, Opus 5.5 medium | authored no commit on this PR | head b19a8857c83091286c6834799f503c2d4a966a0a | axes: pass line, design, scope, tests, body vs diff, evidence | verdict PASS | must 0 want 2 nit 3

## 指摘
- [want] PR 本文「テスト」「残り」 — 本文が古い。テストの実行が「head 0e7eb9e で 189 件」のままで、今の head は b19a885（1ef6a65 では 211 件と別のレビューにある）。「残り」には実機の前後スクショが書かれたままだが、もうコメント 6878896105 で出ている。本文の数字と残りを今の head に合わせること。
- [want] SettingsScreen.kt:174・:1312 — 通知タイミング（4 枚）とスヌーズ（9 枚）の行も IconChipRow に変えたが、実機の証跡は招待の行だけ。同じ条件で 2 行の after を 1 枚足すとよい。unit テスト（スヌーズ 9 枚）はある。
- [nit] androidApp/src/test/java/com/calendaralarm/SettingRowLayoutTest.kt:79 — 「辞退」を押すテストは直す前（幅 0）でも通る。回帰を守るのは幅のテストだけ（前のレビューと同じ）。
- [nit] SettingRowLayoutTest.kt:83 — Composable と FlowRowScope を完全修飾で書いている。import の方が読みやすい。
- [nit] SettingsScreen.kt:515 — 2 行に折り返すと、左のアイコンが 2 行の真ん中に来る（after の画像）。デザインはこの行にアイコンが無く見出しを置く形なので、#211 で見出しに変える時に一緒に片づく。

## デザインとの比較
基準: design/project/p155.dc.html 1 列目（#211 #204）と d68/settings.dc.html 4 列目「選ぶ札は折り返して全部見せる」。p155 を 864x940・dsf1 で描いた（support.js が無いので theme を light に置き換え、CSS は design/asset から。端末が light のため）。
- 折り返し: デザインは `.chips` が flex-wrap: wrap。after は FlowRow で 2 行目に「辞退」が出る。合っている（範囲内）。
- 最後のチップが押せる: after で「辞退」が幅いっぱいに見える。デザインの「全部見せる」と合う（範囲内）。
- 横の間隔: デザイン 8px、after はチップの end 4dp。4dp はこの画面の全チップと同じ既存の値で、この PR は変えていない（範囲外）。
- 縦の間隔: デザインは行の間 8px。after は 48dp の押せる範囲のぶん空いて見える（約 16dp）。Material の最小タッチ領域によるもの（範囲内、許容）。
- 形・色: デザインは丸み 20px の塗りの札（選択 #8B5000 に白字、未選択 #EDEFF4 に黒字、枠なし）。after は Material3 FilterChip（角 8dp、未選択は枠線、選択は薄い橙に濃い字）。master と同じ部品で、この PR は触っていない（範囲外。デザインシステムの話）。
- 文字: デザイン 13px/600。after は FilterChip 既定（文字 1.3 倍）。触っていない（範囲外）。
- 見出し「招待の返事」と説明: デザインにはあり、after はアイコンだけ。p155 の注記「今は返事の行に見出しが無く」は #211 の範囲（範囲外）。
→ この PR が触った所（折り返し・最後のチップ）にデザインとの食い違いは無い。must 0。

## 軸ごと
- 合格線: #204 の「末尾まで到達して押せる」「小画面・大きい文字で隠れない」を満たす。予定種別の行は master で既にスイッチ列（#79）で、本文の説明どおり。iOS 未確認は本文に明記あり。Refs #204 で閉じない書き方も妥当（スクショ以外の終了条件がまだあるため）。
- 範囲: 差分は SettingsScreen.kt と新しいテスト 1 つだけ。通知タイミング・スヌーズの行の変更は #204 の「その他すべて…隠れない」と前のレビューの指摘に沿う。保存・判定のロジックは変えていない（InviteFilterRow は前の中身をそのまま移しただけ）。
- テスト: SettingRowLayoutTest 3 件（360dp・文字 1.3・NATIVE 描画）。直す前に落ちることは前のレビューが戻して確認済み（「辞退」幅 0 で失敗）。私は gradle を回していない。
- 本文と差分: 変更の説明は差分と一致。数字と「残り」が古い（want 1）。
- 証跡: 実機 Pixel 7 Pro・Android 17・ja-JP・文字 1.3・360dp の前後スクショあり。before は「辞退」が右端で潰れて空の枠だけ、after は折り返して全部見える。
- master の取り込み: `git diff 1ef6a65 b19a885 --stat` に SettingsScreen.kt は無い。UI で触るのは AgendaScreen.kt の `private`→`internal` 1 行だけ。だから 1ef6a65 のスクショは b19a885 の画面としてそのまま使える。

## 前の指摘の状態
- 独立レビュー（3a7f1f5）should 1「通知タイミング・スヌーズも潰れる」: 直った（IconChipRow で両方 FlowRow に）。
- should 2「実機の前後スクショが無い」: 招待の行は済み。他の 2 行は want 2。
- nit 1「IconSettingRow の複製」: IconChipRow として共通化され、見出し＋スイッチの行は IconSettingRow のまま。理由（FlowRow の中では weight が効かない）も KDoc にある。解消。
- nit 2「InviteFilter の完全修飾」: import にした。解消。
- 1ef6a65 のレビュー（must 0）: 変わらず。

## 手元の検査
/tmp/claude-0/-home-user-calendar-alarm/4883ef84-55b1-5e04-8735-0a29b98f7ecb/scratchpad/localcheck-363.txt — hardcoded-ja OK（309 個, exit=0）、sync-ai-rule --check in sync（exit=0）。HEAD b19a885 の worktree で実行。
並べた画像: /tmp/claude-0/-home-user-calendar-alarm/4883ef84-55b1-5e04-8735-0a29b98f7ecb/scratchpad/side-363.png
