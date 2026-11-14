REVIEW | reviewer: general-purpose, opus | authored no commit on this PR | head 4211b71c3d6fd6e21eafc9fed761cb67fb5763bd | axes: pass line, design, scope, tests, body vs diff, evidence | verdict FAIL | must 1 want 2 nit 2

# PR #399 レビュー (Refs #220, SBI-2 の R8 部分)

対象: `kouiso/calendar-alarm` PR #399、head `4211b71c3d6fd6e21eafc9fed761cb67fb5763bd` (`/home/user/wt220` の HEAD がこれと一致することを `git rev-parse HEAD` で確かめた)。base は `origin/master` (`3d251e4`)。
差分は 4 ファイル・+37/-8: `androidApp/build.gradle.kts`、`androidApp/proguard-rules.pro`、`scripts/ci.sh`、`.github/workflows/emulator_verify.yml`。

## 1. 合格ライン (pass line)

| 項目 | 結果 | 根拠 |
|---|---|---|
| 根本原因 | 一部だけ | release の `isMinifyEnabled = true` は入った (`androidApp/build.gradle.kts:36`)。ただ、門にしている `fix-minified` レーンの APK は名前を変えていない (難読化なし)。下の must-1 |
| CI/ビルドだけの変更なので、mutation で落ちることを示す | 示した | `-dontwarn com.gemalto.jp2.JP2Decoder` を消すと `:androidApp:minifyReleaseWithR8` が `Missing class com.gemalto.jp2.JP2Decoder` で落ちた。変更していない head (`/home/user/wt220`) では同じ R8 が成功している |
| 停止 E2E が minify 後の build で通る (管理者の門) | 通った (ただし縮小だけ) | check-run `verify (fix-minified)` success (job 132463045711)。ログ 479 行に `> Task :androidApp:minifyDebugWithR8` があり、unlocked / race / locked / locked_screen_on の 4 件すべて PASS。`verify (fix)` と陰性対照 `verify (control-3d106817)` のログには minifyDebugWithR8 が 0 回。陰性対照は期待どおり `STOP_UI_MISSING` を出した |
| i18n | 対象外・green | 文字列の変更なし。`node scripts/hardcoded-ja/check.mjs` は OK (合計 309 個で増減なし)。check-run `i18n-check` success |
| shared の signature / iOS | 変更なし | `shared/`・`iosApp/` に差分なし |
| DB スキーマ・価格ゲート・本番・FAD 配布 | 触れていない | FAD Distribution workflow は `assembleDebug` を `-PminifyDebug` なしで呼ぶので、配る APK は今までと同じ |
| 作者以外の REVIEW | 0 件 | `pulls/399/reviews` は空。inline コメントも 0 件。Codex の issue コメントを全文読んだ。中身は状態の記録だけ (`code_review.status: failed`、「We couldn't complete this review」)。`has_any_findings: true` と書かれているが、指摘の本文も inline コメントも出ていない。次のコメントは利用枠の上限の通知。CodeRabbit も利用枠の上限の通知だけ |
| Issue を閉じるか | `Refs #220` で正しい | #220 の完了条件 (配布物で有効と証明する、mapping の非公開での保管、両 OS の代表 E2E) はこの PR では満たされない。PR 本文の「Closes しない理由」と合っている |

## 2. 設計 (design)

- `androidApp/build.gradle.kts:36-38`: release は `isMinifyEnabled = true`、`isShrinkResources = isMinifyEnabled`、`proguard-android-optimize.txt` + `proguard-rules.pro`。戻し方の 1 行コメントがあり、切り戻しやすい。
- `androidApp/build.gradle.kts:40-44`: debug は `providers.gradleProperty("minifyDebug").orNull == "true"` の時だけ minify。既定は従来どおり。configuration cache とも相性がよい。
- **R8 の debug モードで難読化が止まる問題 (must-1)**: debug は debuggable な variant なので、AGP が R8 を debug モードで動かす。手元で `-PminifyDebug=true :androidApp:minifyDebugWithR8` を回し、`mapping/debug/mapping.txt` を調べた。
  - `com.calendaralarm` のクラス行で、左右の名前が違うもの (正規表現の形に頼らない数え方): **debug は 1317 行中 0 件**。release は 587 行中 574 件。
  - 例: `com.calendaralarm.widget.NextAlarmWidget` は debug では元の名前のまま、release では `P2.b`。
  - 消えたクラス (`R8$$REMOVED`) は debug でも 115 件あるので、縮小は効いている。
  - つまり `fix-minified` レーンが試しているのは「縮小だけの R8」で、release が出す「難読化 + 最適化 + リソース縮小」ではない。名前に頼る壊れ方 (リフレクション、永続化したクラス名、名前での呼び出し) は、この門では見つけられない。
- keep ルールが足りているか (release の mapping で確かめた):
  - **manifest の Activity / Service / Receiver**: マージ後の release manifest に出てくる `com.calendaralarm.*` の全 10 クラスが、mapping で元の名前のまま残った (CalendarAlarmApp, MainActivity, MailPrintService, AlarmReceiver, AlarmService, BootReceiver, NotificationAlarmService, RingingActivity, TimeChangeReceiver, NextAlarmWidgetReceiver)。
  - **Room**: `AppDatabase` と `AppDatabase_Impl` は名前が残った。DAO の `_Impl` は名前が変わったが、`AppDatabase_Impl` から直接参照されているので問題ない。
  - **WorkManager**: `SyncWorker` は名前が残った。work-runtime 2.10.0 の consumer rules に `-keepnames class * extends ListenableWorker` があるので、WorkManager の DB に保存済みのクラス名も、更新したあとに解決できる。
  - **kotlinx.serialization**: `$$serializer` が 28 個残っている (名前は変わる)。直列化名はコンパイル時の plugin が決めるので、名前が変わっても影響しない。
  - **Glance**: consumer rules に `-keep public class * extends ActionCallback` がある。
  - **pdfbox-android**: AAR に `proguard.txt` があり、SecurityHandler のリフレクションを keep している。リソースは `assets/` から読むので、リソース縮小の対象外。
  - **リフレクション**: `androidApp`・`shared` の main に `Class.forName` / `getDeclared*` / kotlin-reflect / `getIdentifier` は見当たらない。
  - 結論: release の keep は足りている (静的な確認。実行時の確認は must-1 の修正後に E2E で行う)。
- `proguard-rules.pro` が `-dontwarn` 1 行だけで済んでいる点は妥当。理由のコメントも正しい。

## 3. 範囲 (scope)

SBI-2 の R8 部分だけに絞れている。FAD で配る APK、DB、iOS、文字列は変えていない。PR 本文にある監査 (SBI-1/SBI-4) の要約は、攻撃に使える詳細を含まない非公開前提の要約で、公開リポジトリとして問題ない。このレビューにも、悪用できる詳細は書いていない。

## 4. テスト (tests)

実行したコマンドと結果:

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew --max-workers=2 :androidApp:assembleRelease` (head, `/home/user/wt220`) → 成功、`androidApp-release-unsigned.apk`、`mapping.txt` が生成された
- mutation: scratch の worktree `rv399/mut` (head) で `proguard-rules.pro` から `-dontwarn com.gemalto.jp2.JP2Decoder` を消し、`:androidApp:minifyReleaseWithR8` を実行 → `ERROR: R8: Missing class com.gemalto.jp2.JP2Decoder` で FAILED。`git checkout -- androidApp/proguard-rules.pro` で元に戻した (`git status` で差分なし)
- `-PminifyDebug` の受け渡し (`assembleDebug --dry-run` の中の `minifyDebugWithR8` の数):

  | 対象 | 指定なし | `-PminifyDebug=true` |
  |---|---|---|
  | head | 0 | 1 |
  | base (`rv399/base`, origin/master) | 0 | 0 |

  base では新しいレーンが minify にならず、head ではなる。
- `ci.sh` の引数組み立てを `set -u` で単体確認: `MINIFY_DEBUG` が空なら `[]`、`true` なら `[-PminifyDebug=true]`。`bash -n scripts/ci.sh` は OK
- `:shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest` (head) → 488 件、失敗 0、エラー 0、スキップ 0。PR 本文の 488 件と一致
- `-PminifyDebug=true :androidApp:minifyDebugWithR8` → 成功。ただし名前が変わったクラスは 1317 行中 0 件 (must-1 の根拠)
- `scripts/test-ci-contract.sh` は手元に `task` (go-task) が無くて回せなかった。CI の check-run `contract` は success。この契約テストには `MINIFY_DEBUG` / `minify` への言及が 0 件 (grep で確認)

## 5. 本文と差分の突き合わせ (body vs diff)

- 本文「release の `isMinifyEnabled = true` (+ shrinkResources)」「debug は `-PminifyDebug=true` の時だけ」「`-dontwarn` のみ」「fix-minified レーンと dispatch の minify 入力」: どれも差分と一致する。
- 合わない点: `emulator_verify.yml:8` のコメント「R8 (縮小・難読化) 後の APK で」、dispatch 入力の説明 (`emulator_verify.yml:36`)、`build.gradle.kts:41` と `scripts/ci.sh:46` の「R8 (縮小・難読化)」。実際の debug-minified APK は難読化されていない (must-1)。
- 本文「停止 harness を入れた debug を `-PminifyDebug=true` でビルドし成功」: CI ログで確認できた。

## 6. 証拠 (evidence)

- レビュー後の `/home/user/wt220`: `git status --short` は空、HEAD は `4211b71c3d6fd6e21eafc9fed761cb67fb5763bd` のまま。scratch の worktree (`rv399/mut`, `rv399/base`) は消さずに残した

- check-runs (head): verify (fix) / verify (fix-minified) / verify (control-3d106817) / build / i18n-check / plan / contract がすべて success。CodeRabbit は neutral (利用枠の上限)
- fix-minified の job ログ: 479 行に `minifyDebugWithR8`、493 行に `BUILD SUCCESSFUL`、739〜767 行に 4 シナリオの PASS
- mapping での比較: 上の「2. 設計」に記載

## must

1. **`fix-minified` の門が、release が出す R8 (難読化あり) を試していない。説明文も「縮小・難読化」と事実と違う**
   - 証拠: `-PminifyDebug=true` の debug mapping では、`com.calendaralarm` のクラス行で左右の名前が違うものが 1317 行中 0 件。release では 587 行中 574 件 (例: `NextAlarmWidget` → `P2.b`)。debuggable な variant では R8 が debug モードで動くため。
   - 影響: 「R8 を入れても停止経路が壊れない」という門 (#220) が、名前に頼る壊れ方を見つけられない。
   - 直し方の例 (どれか 1 つ):
     - (a) E2E 用の build type を作る: `create("e2eMinified") { initWith(getByName("release")); signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }`。harness をその source set (`src/e2eMinified`) に入れ、`ci.sh emulator-build` は `MINIFY_DEBUG=true` の時に `assembleE2eMinified` を作って、その APK を `emulator-run` に渡す。
     - (b) minify する debug の時だけ `isDebuggable = false` にする。harness が `run-as` などの debuggable 前提を使っていないかを先に確かめる。
     - (c) 当面は直さないなら、4 か所の「縮小・難読化」を「縮小のみ (debug モードの R8。難読化なし)」に直し、PR 本文の「残り」に「難読化後の停止 E2E は未確認」と書く。ただしこの場合、管理者の門 (minify 後の build で停止 E2E) の意図に足りるかは、管理者の判断が要る。
   - 確かめ方: 直したあとのレーンで生成される mapping で、`com.calendaralarm` の中に名前が変わったクラスが 1 件以上あること。そのうえで 4 シナリオが PASS すること。

## want

1. **release の R8 を CI のどこでも組み立てていない**
   - `scripts/ci.sh` の `all` / `android-build` と FAD workflow はすべて `assembleDebug`。`assembleRelease` / `minifyReleaseWithR8` を呼ぶ workflow は 0 件 (`.github/workflows` を grep で確認)。
   - 依存を上げた時に新しい missing class が出ても (上の mutation と同じ失敗)、誰かが手元で release を作るまで気づけない。
   - 直し方: `ci.sh all` (または新しい `release-build` モード) に `:androidApp:minifyReleaseWithR8` を足し、all_check で回す。
2. **`fix-minified` レーンが、R8 が本当に動いたかを確かめていない**
   - プロパティ名の打ち間違いなどで受け渡しが切れると、minify なしの APK で 4 件 PASS になり、緑のまま門が素通りになる。`-PminifyDebug` を渡さない時は `minifyDebugWithR8` が 0 件になること (dry-run で確認)、その APK でも 4 件 PASS になること (CI の `fix` レーンの minify なしの結果) から、そう言える。
   - 直し方: `ci.sh emulator-build` の最後に `if [ "${MINIFY_DEBUG:-}" = true ]; then test -s "$app_dir/androidApp/build/outputs/mapping/debug/mapping.txt" || { echo 'R8 did not run' >&2; exit 1; }; fi` を足し、`test-ci-contract.sh` に `MINIFY_DEBUG=true` で `-PminifyDebug=true` が渡ることの検査を 1 件足す。must-1 を (a) で直すなら、mapping のパスはその build type に合わせる。

## nit

1. release では、`AlarmRepository.kt:1121`・`AlarmReceiver.kt:59`・`RingingOverlay.kt:108` の監査ログに出る `e.javaClass.simpleName` が、アプリやライブラリの例外なら短い名前になる。#220 の「mapping は非公開で保全」と合わせて、mapping の置き場 (CI の公開 artifact には上げない) を後続の SBI で決めておくとよい。今の PR は mapping を artifact に上げていない (`emulator_verify.yml` の upload は `out/` だけ) ので、漏れてはいない。
2. `proguard-rules.pro:3` のコメント「AAPT が keep を生成する」は、正確には AAPT2 が出す `aapt_rules.txt` を R8 が読む仕組み。動作には関係ない。

---
_Generated by [Claude Code](https://claude.ai/code)_
