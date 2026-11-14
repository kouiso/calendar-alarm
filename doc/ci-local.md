# CI の共通入口

現在のアプリは Kotlin Multiplatform である。
`task ci:fast` は shared JVM、shared Android、Android app unit を検査する。
`task ci` は同じ三つの検査と debug APK ビルドを実行する。
Android CI と同じ `scripts/ci.sh` を使う。
Gradle の三回の起動を一回へまとめ、必要な task をすべて指定する。
統合した検査は `--no-parallel` とし、project 間の並列実行を増やさない。
JDK 17 と既存の Android SDK を使う。`GRADLE_USER_HOME` は変更しない。
UTF-8 のロケールが要る。テスト名が日本語なので、`C` などのロケールでは Kotlin のクラスファイル名が書けずに落ちる。
`scripts/ci.sh` は、`LC_ALL` と `LANG` が UTF-8 でない時に `C.UTF-8` を設定する。`./gradlew` を直接動かす時は自分で `LC_ALL=C.UTF-8` を付ける。
`bash scripts/ci.sh shared` は shared JVM だけを検査する（ロケールの確認に使う軽い入口）。

`task ci:ios` は既存手順と同じ iOS simulator ビルドである。
Xcode と xcodegen が必要である。署名、端末登録、配布は行わない。
`bash scripts/ci.sh ios-logic` は iOS の画面や SharedKit に依存しない計算（`iosApp/iosApp/ring-history.swift`）を
macOS の swiftc で動かす単体テストである。シミュレータは要らない。
Android の検査成功を iOS や emulator の成功とは扱わない。

## 日本語の直書き検査

`bash scripts/ci.sh i18n-check` は、Kotlin の文字列リテラルに日本語を直接書いた数が増えていないかを調べる（#289）。
Gradle も Android SDK も要らず、Node 22 だけで数秒で終わる。Android CI（`all_check.yml`）の `i18n-check` job が PR ごとに同じコマンドを回す。
先に検査器自身のテスト（`scripts/hardcoded-ja/check.test.mjs`）を回し、そのあと本体を回す。

- 対象: `androidApp/src/main/java/**/*.kt` と `shared/src/commonMain/**/*.kt`。
- 数えるもの: `"..."` と `"""..."""` の中の日本語（ひらがな・カタカナ・漢字・全角半角形・CJK 記号句読点、`\uXXXX` 書きも）。`${...}` の中の文字列は別に 1 個と数える。
- 数えないもの: `//` `/* */` `/** */` のコメントと KDoc（AGENTS.md が日本語コメントを求めるため）。
- 判定は構文だけで、文の意味は見ない。
- 比べる相手は base ブランチの `scripts/hardcoded-ja-baseline.json`（ファイルごとの個数）。PR（`GITHUB_BASE_REF` あり）では `git show origin/<base>:scripts/hardcoded-ja-baseline.json` で読むので、PR の中で baseline を上げても通らない。workflow は `fetch-depth: 0` で base ref を取る。ローカルや push では作業ツリーのファイルと比べる。
- 1 ファイルでも base より増える、または載っていないファイルに 1 個でも出れば失敗する。baseline ファイルを base より上げる変更も失敗する。baseline を下げるのは通る。
- 減ったのに baseline を下げていないときも失敗する（余白を後の PR が再利用できないように）。`node scripts/hardcoded-ja/check.mjs --update` を実行し、下がった数字をコミットする。
- base に baseline ファイルがまだ無いとき（この検査を入れる最初の PR）だけ、その PR の baseline を基準にする。base ref が取れないときは失敗する。
- 失敗の文は、ファイル名と何個から何個に増えたかを出す。androidApp のファイルには `strings.xml`（values と values-en）へ移して `stringResource` / `getString` を使うよう、shared のファイルには shared から種類（kind / enum）か引数を返し、文言への変換は各プラットフォーム側で行うよう案内する。

数えない常設の許可（`scripts/hardcoded-ja/check.mjs` の `ALLOWLIST`。理由もそこに書く）:

- `NotificationRuleTemplates.kt` はファイル全体。キーワード一覧は通知本文との照合用で、表示文言ではない。
- `Models.kt` は `hit(...)` の直接引数だけ。予定タイトルとの照合キーワードである。
- `StopUiPlan.kt` はファイル全体。停止手段の警告文は設定の「鳴動ログ」にそのまま出るが、診断ログの文面であり、このフェーズの対象外とするのがプロダクト判断（#289）。
- どのファイルでも、`audit(` `markMissed(` `markMissedIfFired(` `markDndSuppressed(` `markSkipped(` と `Log.v/d/i/w/e(` の直接引数。診断ログの文面（detail）で、鳴動ログには出るが、このフェーズの対象外とするのがプロダクト判断。

## Emulator の各レーン

CI と同じ API 35、google_apis、x86_64 の emulator を準備する。
アプリ用の隔離 checkout を `CI_EMULATOR_APP` で指定する。
`task ci:emulator:build` はその checkout へ既存 harness を注入する。
既存 debug manifest があれば上書きせず失敗する。

作成した APK を `CI_EMULATOR_APK`、証跡先を `CI_EMULATOR_OUT` に指定する。
`task ci:emulator` は実行後に既存 evaluator で期待値を評価する。
検査途中の失敗は `SCENARIO_OUTCOME` に反映する。
実行前に今回の results.tsv を空にし、古い陰性対照を成功根拠に使わない。

修正側は `EXPECT=pass` と次の全シナリオを使う。

```text
SCENARIOS="unlocked race locked locked_screen_on"
```

陰性対照は既存の `3d106817a95987a3b22c0669f8cc6490bceb29b1` を使う。
別の隔離 checkout と APK、証跡先を指定する。
`EXPECT=stop-ui-missing SCENARIOS=unlocked` とする。
陰性対照は停止 UI 欠落を再現できた場合だけ成功となる。
修正側だけの成功を二つのレーンの成功とは扱わない。

CI の source SHA、注入済み harness、署名鍵が異なるため、
CI、emulator、FAD の APK を横断して再利用しない。
FAD 配布と iOS device sync の既存 workflow は保持する。

```bash
bash scripts/test-ci-contract.sh
```

契約検査では Gradle、Xcode、実デバイス呼出しを代替する。
既存 harness 注入と evaluator は実際のコードを実行する。
この結果は実 SDK の build、emulator、実機、配布の成功とは区別する。
