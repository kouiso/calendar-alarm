REVIEW | reviewer: general-purpose, opus | authored no commit on this PR | head 94d675b438f405dba51fc769bff52be0cce4179e | axes: pass line, design, scope, tests, body vs diff, evidence | verdict FAIL | must 3 want 3 nit 3

# kouiso/calendar-alarm PR #188 レビュー（r7）

- 確認した head: `git rev-parse HEAD` = 94d675b438f405dba51fc769bff52be0cce4179e（指定と一致）
- merge-base は 0e5e6f2。origin/master（a37e822）より 1 コミット遅れている（a37e822 は iOS CI だけの変更）。`git merge-tree --write-tree HEAD origin/master` は衝突なし
- 盤: `design/project/p154.dc.html`（2 列目「大きいボタン」、4 列目「足す行」）と `design/asset/41384cd4ff5e02c5cffc251dcfb23a2b.css`
- GitHub には書いていない。tracked file も変えていない。音は鳴らしていない（Robolectric の unit test だけを実行）

## 1. pass line

| 項目 | 結果 | 根拠 |
|---|---|---|
| 根本原因の修正 | OK | 盤 2 列目は「スヌーズも停止と同じ .bb」。`RingingActivity.kt:486-506`（スヌーズの高さ・角丸を LARGE_*）、`:508`（`if (!largeButtons)` で選ぶ・チップを出さない）、`:545-563`（停止の大ボタン）。オーバーレイも `RingingOverlay.kt:158-170,224-248,311-332` で同じ値。前の版でオーバーレイだけスライダーのままだった件は `AlarmService.kt:355` で設定を渡して直っている。`show` の `largeButtons` には既定値が無い（`RingingOverlay.kt:71`） |
| 振る舞いの変化はテストで base 失敗・head 成功 | OK | 下の「4. tests」 |
| i18n | OK | 新しいキーは `settings_large_stop_button`（ja `values/strings.xml:153`、en `values-en/strings.xml:156`）。停止の文言は既存の `common_stop`（ja「停止」・en「Stop」）。`node scripts/hardcoded-ja/check.mjs` は head・base とも 309 個で、増えていない。動かしただけの「スヌーズ時間を選ぶ ▾/▴」「スヌーズ ${n}分」の文字列は元のリテラルのまま |
| DB スキーマ・価格・本番・FAD | 触れていない | DataStore の boolean キー `large_buttons` を 1 つ足しただけ（`SettingsRepository.kt:473`）。Room の entity や migration は変わっていない |
| body と diff の一致 | **NG（must 1）** | 下の「5. body vs diff」 |
| head の CI | **NG（must 3）** | `gh api repos/kouiso/calendar-alarm/commits/94d675b…/check-runs`: build・i18n-check・plan・verify(control) は success、**`verify (fix)`（alarm stop E2E (emulator)、run 43600939014 / job 132465352366）は failure**。下の「4. tests」 |
| 画面 PR の body が盤の名前を出している | OK | body の「デザインの基準」に `design/project/p154.dc.html` と 2 列目・4 列目が書いてある |
| 実機のスクリーンショット | DEVICE（レビューの must にはしない） | body の「残り」に、確かめていないと書いてある |

## 2. design（盤の値とコードの値）

### 2 列目 大きいボタンの鳴動画面（RingingActivity）

| 盤（HTML/CSS） | コード | 判定 |
|---|---|---|
| `.bb` height 124px | `RingingSlideSpec.LARGE_BUTTON_HEIGHT_DP = 124`。スヌーズは `RingingActivity.kt:489`、停止は `:548` | 一致 |
| `.bb` border-radius 32px | `LARGE_BUTTON_CORNER_DP = 32`。スヌーズは `:495`、停止は `:549` | 一致 |
| `.bb` font-size 24px・weight 600 | `LARGE_BUTTON_TEXT_SP = 24`・`FontWeight.SemiBold`（`:502-503, :557-558`） | 一致 |
| `.bb.s` background `--glass` rgba(255,255,255,.08)・color `--tx` #ECEEF3 | `Color.White.copy(alpha = 0.08f)`・`Color(0xFFECEEF3)` | 一致 |
| `.bb.p` background `--ac`・color `--onac` | `containerColor = accent`・`contentColor = onAccent`（テーマの primary/onPrimary） | 一致 |
| `.acts` gap 12（スヌーズ↔停止↔foot） | スヌーズと停止の間は Spacer 12（選ぶの分岐の後）、停止と foot の間は `LARGE_BUTTON_GAP_DP` 12（`:564`） | 一致 |
| スヌーズが上・停止が下 | 並びのとおり | 一致 |
| 「スヌーズ時間を選ぶ」とチップは無い | `if (!largeButtons)`（`:508`） | 一致 |
| `.dial.sm` 176、内側の円は inset 18（=140）、`b` 66px | `LARGE_DIAL_DP = 176`、`ringInnerRatio = 140f/176f`、時刻は `ringOuter * 66/176` | 一致（窓が十分に高い時。低い窓では今までどおり `ringScale` で縮む） |
| `.rt` 22px・600・line-height 1.35 | `LARGE_TITLE_SP = 22`・SemiBold・`22 * 1.35` | 一致 |
| dial の線 1.5px `--acl`（.30）、内側の円は `--acl` に opacity .6 | 1.5dp accent .25 と 1dp accent .15 | 違う。ただし OFF のときから同じ値で、この PR では変わっていない → OWNER |

### 2 列目 オーバーレイ（RingingOverlay）と RingingActivity の比較

| 盤 | オーバーレイのコード | Activity | 判定 |
|---|---|---|---|
| `.bb` 124/32/24/600 | 高さ `LARGE_BUTTON_HEIGHT_DP`（`:241, :331`）、角丸 `LARGE_BUTTON_CORNER_DP`（`:167` と停止の背景）、24sp、`semiBold()` = Typeface weight 600（API 28 以上） | 同じ | 一致 |
| スヌーズ↔停止 12 | 停止の topMargin 12（`:332`） | 12 | 一致 |
| 停止↔foot 12 | foot の topMargin 10（`:342`、変更なし） | 12 | **違う（must 2）** |
| 選ぶ・チップ無し | `!largeButtons` の分岐（`:248`） | 同じ | 一致 |
| `.dial.sm b` 66 | 時刻の文字 66sp。円は無い | 円有り | 円が無いことは body の OWNER に書いてある |
| `.rt` 22・600 | 22sp、太さは指定なし（通常） | SemiBold | 太さが違う。前からの差だが body に書いていない（want 2） |
| `.dial b` Outfit 200 | 端末の既定の書体・通常 | Outfit ExtraLight | 前からの差。body に書いていない（want 2） |
| `.pill` の日付、「10分後に開始」 | 無い | 有り | 前からの差。body に書いていない（want 2） |

### 4 列目 足す行（SettingsScreen）

| 盤 | コード | 判定 |
|---|---|---|
| `.card.ls` 角丸 20、左右 16、上下 4、背景 `--card` | `RoundedCornerShape(20.dp)`、`padding(horizontal = 16.dp, vertical = 4.dp)`、`surfaceContainerLow`（他のカードと同じ）（`SettingsScreen.kt:1683-1694`） | 一致 |
| `.it` min-height 56 | 行の上下 4 とスイッチの `minimumInteractiveComponentSize` 48 で 56（`:1704-1723`） | 一致（`LargeButtonsRowTest` が 56 以上を確かめている） |
| `.t` 15px・500・`--tx` | 15.sp・Medium・onSurface | 一致 |
| 見出し「鳴動画面を大きいボタンにする」、説明文なし、`.sw` 1 つ | 同じ | 一致 |
| 外観の先頭で、テーマのカードの前 | `LargeButtonsCard` は `AppearanceCard` の前の item（`:262`、`AppearanceCard` は `:269`） | 一致 |
| 「文字の大きさ」「鳴動画面の背景」のカード | 無い | OWNER（仕様に無い項目。body に書いてある） |
| テーマのカードの案B の鍵 | 無い | OWNER（無料か Pro かは #140 の後に決める。body に書いてある） |

### OFF のモードが変わっていないか

- RingingActivity: スヌーズ 80dp・角丸 TRACK_CORNER、文字 22sp、選ぶ・チップは今までどおり開閉、停止はスライド、その下は 10、文字盤 290/230/112、予定名 32/42。diff を読んで、元と同じ式であることを確かめた。`LargeButtonRingingLayoutTest` の OFF のケースも通る
- RingingOverlay: スヌーズ 80・22sp、時刻 96、予定名 30、スライダー TRACK_HEIGHT、topMargin 12・10。違うのは太字の付け方だけ（`setTypeface(tf, BOLD)` から `setTypeface(Typeface.create(tf, BOLD))` に変わった。nit 3）
- スライダーの `dismiss` を外へ出しても、送る中身（ACTION_DISMISS・EXTRA_INSTANCE_ID・AlarmReceiver・audit の STOP_TAP）は同じ。`RingingOverlaySlideStopTest` の既存 7 件が通る

## 3. scope

- 変えたファイル 15 個はどれも #154（大きいボタン・外観の範囲）の中。scope の外の変更は無い
- `doc/spec/kmp-replacement.md` に §7.2 が足されている。大ボタンモードの行は「停止をスライドから大ボタンに切替」としか書いていない。この PR はスヌーズを大きくし、大きいボタンモードで「スヌーズ時間を選ぶ」とチップを無くしている。どの項目が有るかは仕様が決めるので、仕様が古いままになっている（want 1）

## 4. tests

実行した環境: Linux コンテナ、`LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew --max-workers=2`

| 実行 | コマンド | 結果 |
|---|---|---|
| head・全体 | `:androidApp:testDebugUnitTest` | BUILD SUCCESSFUL。ただしタスクは 46 個とも up-to-date で、入力が同じ前回の結果を Gradle が使い回した。自分で実際に走らせたのは次の行の `--rerun` の分だけ。test-results の XML を数えて 241 件、failures/errors 0、skipped 0（body の「241 件で失敗 0」と一致） |
| head・対象だけ再実行 | `--rerun --tests 'com.calendaralarm.LargeButton*' --tests 'com.calendaralarm.RingingOverlay*' --tests 'com.calendaralarm.SettingsLargeButtonsOrderTest'` | 成功。LargeButtonRingingLayoutTest 4、LargeButtonStopTest 1、LargeButtonsRowTest 1、RingingOverlayLargeLayoutTest 3、RingingOverlaySlideStopTest 8、RingingOverlaySnoozeChipsTest 5、SettingsLargeButtonsOrderTest 1。どれも失敗 0 |
| head・盤と同じ高さ（scratch worktree に 94d675b を置き、`LargeButtonRingingLayoutTest` の qualifiers だけを `w360dp-h740dp-mdpi` に変えた） | `--tests 'com.calendaralarm.LargeButtonRingingLayoutTest'` | 4 件中 2 件が失敗。大きいボタンの文字盤は `Actual width is 170.0.dp, expected 176.0.dp`、OFF は `208.0.dp, expected 290.0.dp`（OFF が縮むのは前から）。Robolectric の 740 にはシステムバーが含まれるので、盤の 740 と同じ大きさの枠ではない（未確認）。原因は `RingingActivity.kt:402` の `maxHeight - 170.dp` で、OFF 用の「下の文字群 170」を大きいボタンでもそのまま引いている。大きいボタンでは予定名が 22 で、下の文字群は 170 より低い → want 3 |
| base（origin/master a37e822 の scratch worktree に新しいテスト 6 ファイルをコピー） | 同じ `--tests` | BUILD FAILED。`compileDebugUnitTestKotlin` で `e:` が 47 行（`LARGE_STOP_TAG`・`RING_DIAL_TAG`・`SNOOZE_BUTTON_TAG`・`SNOOZE_CHOOSE_TAG`・`TITLE_TAG`・`setLargeButtons`・`buildView(largeButtons=)` が無い）。base にはこの機能が無いので、テストは base で落ちる |

テストの中身:
- `LargeButtonRingingLayoutTest` はスヌーズと停止が 124dp であること、選ぶが無いこと、文字盤が 176dp、予定名が 22sp であることを確かめる。OFF でスヌーズ 80・選ぶ有り・290・32 であることも確かめる。管理役が決めた値を、実際に描いた結果で測っている
- `RingingOverlayLargeLayoutTest` は高さ・選ぶ・チップ・文字 24・予定名 22 を確かめる。OFF は 80・選ぶ有り・30
- 足りないテスト: オーバーレイで、停止と foot の間が Activity と同じかを測るテストが無い（must 2 と一緒に足す）

CI の stop E2E（`verify (fix)`、head 94d675b）:
- job log の結果は unlocked PASS、race PASS、locked PASS、**locked_screen_on FAIL `UNLOCKED_BY_STOP keyguard not showing after stop`**。手順「Evaluate against lane expectation」が exit 1 で終わっている（lane の expect は pass）
- 同じブランチの前の head（90ef21b・6398aca・61c1be6・e759485・aecf600・5f172b2）では同じ workflow がどれも success。ほかの PR の最近の run（1f41f45・ce97d42・4211b71・3f401c3・c241800）も success。d70ad1d の失敗は別の理由（unlocked の PROBE_PRECONDITION）
- 90ef21b から 94d675b までには、この PR の鳴動画面の変更と master の取り込み（`gradle/libs.versions.toml`・`fastlane/Fastfile` など）が入っている。OFF の停止の経路やロック画面の扱いを diff は変えていないので、この PR のせいかどうかは分からない（未確認。flaky の可能性もある）

## 5. body vs diff

| body の記述 | diff | 判定 |
|---|---|---|
| 「`RingingOverlaySlideStopTest` の大ボタンのケースを 96 から 124 に直した」 | base に対しては、`大ボタンはスライダーを置き換えて同じ鳴動を停止する` という**新しい**テストを 1 件足している（`RingingOverlaySlideStopTest.kt:98-109`）。base には 96 の版は無い | **違う** |
| 「前から有る `LargeButtonsRowTest`、`LargeButtonStopTest`」 | 2 つとも、この PR の前のコミットで足したファイル（`git log --diff-filter=A origin/master..HEAD` で e759485 と bf48004）。base には無い | **違う** |
| 「前のコードにタグだけ足して実行し、7 件が失敗するのを確かめた」 | 「前のコード」は PR の前の head（a0882ff）のことで、origin/master ではない。そう読むと数が合う: 新しいケース 9 件から OFF の 2 件を引くと 7 件。a0882ff の `RingingOverlaySlideStopTest.kt:104` には `assertEquals(96, button.layoutParams.height)` がある。base（master）では落ち方がコンパイルエラーで、7 件の assertion 失敗にはならない | **違う**（基準が base でない） |
| 241 件・失敗 0、lint、hardcoded-ja 309 | 241 件と 309 個は手元でも同じ値が出た。lint は手元では実行していない。CI の `build` は success だが、lint が含まれるかは確かめていない | 一致（lint は未確認） |
| 数値（124/32/24/600/12、176/66、22）、カードの値（20/16/4、15/500） | コードと一致 | 一致 |
| 「§7.2 は判定役のメモで、持ち主の仕様ではない」 | この PR が §7.2 を `doc/spec/` に足しており、行の中に「（決定）」がある | 書き方が食い違っている。want 1 で一緒に直すとよい |

## must

1. **body のテストの説明が diff と合っていない**（pass line の「body vs diff exact」）。原因は、テストの節を origin/master ではなく PR の前の head（a0882ff）を基準に書いたこと。直し方: 「テスト」の節を base に対する差分で書き直す。
   - `LargeButtonsRowTest`・`LargeButtonStopTest` は「この PR で足した」と書く
   - `RingingOverlaySlideStopTest` は「大ボタンが DISMISS を送るケースを 1 件足した（高さ 124）」と書く
   - 「前のコードで 7 件失敗」は「a0882ff にタグを足して 7 件失敗」と書く。base については「origin/master ではコンパイルが通らずに落ちる」と書き足す
2. **オーバーレイの停止と foot の間が 10 で、Activity と盤の 12 と違う**（`RingingOverlay.kt:342`）。盤 2 列目の `.acts` は style で gap 12 を指定している。この PR が作る画面の中の差で、持ち主に決めてもらう話ではないので直す。直し方: `topMargin = dp(if (largeButtons) RingingSlideSpec.LARGE_BUTTON_GAP_DP else 10)`。`RingingOverlayLargeLayoutTest` に、この間を測る 1 行を足す。
3. **head の stop E2E（`verify (fix)`）が赤い。** locked_screen_on で、停止のあとロック画面が戻らない（`UNLOCKED_BY_STOP`）。直し方: 再実行して、また落ちるかを確かめる。また落ちるなら、master の取り込みとこの PR のどちらが原因かを切り分ける（a0882ff と 94d675b を同じ lane で走らせる）。緑になるまでマージしない。1 回だけの flaky なら、再実行した run の URL を body に書く

## want

1. **§7.2 の大ボタンモードの行が古い**（`doc/spec/kmp-replacement.md:235`）。今は「停止をスライドから大ボタンに切替」とだけある。直し方: 「スヌーズと停止を同じ大きさの大ボタン（124）にする。大きいボタンモードでは『スヌーズ時間を選ぶ』とチップを出さない」と書く。body の「§7.2 は持ち主の仕様ではない」という書き方も、仕様書にあることと合うように直す。
2. **オーバーレイが盤 2 列目と違うところが body の OWNER に全部は書かれていない。** 予定名の太さ（盤は 600、オーバーレイは通常）、時刻の書体（盤は Outfit 200）、日付の pill と「10分後に開始」が無いこと、の 3 つ。どれも前からの差だが、デザインの差は直すか OWNER に書く決まりなので、body の「盤と違う所」に足す（予定名の太さは 1 行で直せる）。
3. **高さが盤に近い窓では、大きいボタンの文字盤が 176 より小さくなる**（h740 の Robolectric で 170dp）。`RingingActivity.kt:402` の、下の文字群のために引く 170 が OFF の値のまま。直し方: 大きいボタンでは、予定名 22・行の高さ 1.35 に合わせて引く量を小さくする（例: `if (largeButtons) 140.dp else 170.dp`。値は計算して決める）。`LargeButtonRingingLayoutTest` に h740 のケースを足す。Robolectric の枠は盤の枠と同じではない（未確認）ので want にした。実機での高さは DEVICE で確かめる

## nit

1. `SettingsRepository.kt:130-131` の KDoc「ON=誤タッチしにくい大きな停止ボタン」が古い。スヌーズも大きくなる。
2. オーバーレイの大きい停止 `android.widget.Button` は、背景を `GradientDrawable` にしたので押した時の ripple が出ない（`RingingOverlay.kt:311-325`）。スライダーとは違い 1 回押すだけで止まるので、押せたことが見て分かるようにした方がよい（`RippleDrawable` で包む）。
3. OFF のスヌーズの太字が `setTypeface(typeface, BOLD)` から `setTypeface(Typeface.create(typeface, BOLD))` に変わった（`RingingOverlay.kt:164`）。前者は太字の書体が無い時に偽の太字を使うが、後者は使わない。端末の既定の書体では見た目は同じ。OFF を確実に今のままにするなら、`if (largeButtons) setTypeface(semiBold(typeface)) else setTypeface(typeface, BOLD)` に戻す。

## OWNER（持ち主が決めること）

- 盤 4 列目の「文字の大きさ」と「鳴動画面の背景」の行を足すかどうか（仕様に無いので、この PR では足していない）
- 無料か Pro か（案A・案B）と、案B の鍵の行。盤では「#140 の後に決める」とある。今は全部無料
- オーバーレイに文字盤の円を足すかどうか（今は時刻の文字 66 だけ）
- 文字盤の線の色の濃さ（盤は `--acl` .30 と .6 を掛けた値、コードは .25/.15。前からの差）
- オーバーレイの時刻の書体・予定名の太さ・日付の pill・「10分後に開始」（want 2 で直さないものはここに残す）
- DEVICE: 実機で ON/OFF の見た目を比べること、大きいボタンで停止とスヌーズを押すこと、TalkBack、高さ 740 前後の実機で文字盤が何 dp になるか（want 3 を直したあと）

---
_Generated by [Claude Code](https://claude.ai/code)_
