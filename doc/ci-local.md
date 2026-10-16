# CI の共通入口

現在のアプリは Kotlin Multiplatform である。
`task ci:fast` は shared JVM、shared Android、Android app unit を検査する。
`task ci` は同じ三つの検査と debug APK ビルドを実行する。
Android CI と同じ `scripts/ci.sh` を使う。
Gradle の三回の起動を一回へまとめ、必要な task をすべて指定する。
統合した検査は `--no-parallel` とし、project 間の並列実行を増やさない。
JDK 17 と既存の Android SDK を使う。`GRADLE_USER_HOME` は変更しない。

`task ci:ios` は既存手順と同じ iOS simulator ビルドである。
Xcode と xcodegen が必要である。署名、端末登録、配布は行わない。
Android の検査成功を iOS や emulator の成功とは扱わない。

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
