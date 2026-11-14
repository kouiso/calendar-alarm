REVIEW | reviewer: general-purpose, opus | authored no commit on this PR | head 1f41f456686d6cb73882010f5a702229268f0cfa | axes: pass line, design, scope, tests, body vs diff, evidence | verdict FAIL | must 4 want 3 nit 1

# kouiso/calendar-alarm PR #191 レビュー（r7、非作者・読み取りのみ）

- 対象: `/home/user/wt191`。`git rev-parse HEAD` = `1f41f456686d6cb73882010f5a702229268f0cfa`（一致）
- base: `origin/master` = `a37e822`。merge-base は `0e5e6f2`。差分は `a37e822 ci(ios)` の 1 件だけ。`git merge-tree --write-tree origin/master HEAD` → CLEAN
- 盤: `design/project/p149.dc.html`（状態「1. 許可あり」「2. 許可がまだない」）。盤の CSS は `design/asset/41384cd4ff5e02c5cffc251dcfb23a2b.css`
- 書いた仕様: Issue #149（REST `gh api repos/kouiso/calendar-alarm/issues/149`）
- レビュースレッド: `GET .../pulls/191/ccr/review_threads` → 7 件すべて resolved（未解決 0）

## 1. 合格ライン

| 項目 | 結果 | 根拠 |
|---|---|---|
| 根本原因 | OK | 音は USAGE_ALARM で鳴らすので、チャンネルの bypassDnd を外すだけでは「アラームのみ」などのおやすみモードを素通りしてしまう。これを、座席を取る前の `silencedByDnd` で止めている（`engine/AlarmService.kt:251`、`:981`）。判定は `engine/DndPolicy.kt:64` の `effectiveIgnoreDnd` と `:70` の `dndActive` の 1 か所にまとめてある |
| 動きの変化に、base で落ちて head で通るテストがあるか | OK | 下の「4. テスト」 |
| i18n | OK | ja・en とも 15 キーを足していて、en の抜けは無い。残した文言 `trouble_dnd_ok`・`_ng`・`_desc` は旧文言のまま。hardcoded-ja は head・base とも合計 309。ファイルごとの差も 0（`countAll` は `{パス: 数}` を返す。base と head で値が違うキーは 1 つも無かった） |
| DB スキーマ・価格・本番・FAD | 触っていない | `Daos.kt` はクエリ（`setMissedIfFired`）を 1 本足しただけで、スキーマも migration も変えていない |
| 本文と差分の一致 | **NG** | M1 と M3 |
| 「通知だけ残す」を固めるテスト | **NG** | M4 |
| 画面 PR の本文が盤を名指ししているか | OK | 本文に「`design/project/p149.dc.html`」とある |
| 実機の写真 | DEVICE（着地の関門。このレビューの must には入れない） | 本文の「残り」にも書いてある |

## 2. デザイン（盤の値とコードの値）

| 項目 | 盤 p149 | コード（head） | 判定 |
|---|---|---|---|
| 見出し | 「おやすみモード中も鳴らす」 | `settings_ignore_dnd_title` 同文 | 一致 |
| 説明（ON） | 「サイレントにしていても、アラームだけは鳴ります」 | `settings_ignore_dnd_hint_on` 同文 | 一致 |
| 既定 | 「既定はオン」 | `SettingsRepository.kt:184` `p[KEY_IGNORE_DND] ?: true` | 一致 |
| 状態 1 のカードの下の `.prev` 箱 | 「オフにすると」／「おやすみモード中は鳴らさず、通知だけ残す」を、ON の時もカードの下の箱に出す | 箱は無い。OFF かつ許可ありの時だけ説明行を `hint_off` に差し替える（`ui/SettingsScreen.kt:1715`）。この「OFF の状態」は盤に無い | **不一致（M3）** |
| アイコン | 三日月（`path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"`）。許可ありは `c-ac`、無しは `c-mu` | `Icons.Default.DoNotDisturbOn`（丸に横棒）。色は primary か outline（`ui/SettingsScreen.kt:1699-1704`） | 形が**不一致（M2）**、色は一致 |
| 許可なし: スイッチ | `sw` disabled、OFF | `checked && policyGranted`、`enabled = policyGranted`（`:1724-1726`） | 一致 |
| 許可なし: 見出し・説明の薄さ | `.grow` に opacity .6（アイコンは薄くしない） | `Column(...).alpha(0.6f)`、アイコンには alpha をかけない | 一致 |
| 許可なし: 案内 | 「使うには「おやすみモードの例外」の許可が要ります。」、`.m c-tx` | 同文、bodySmall、onSurface | 一致 |
| 許可なし: 「許可を開く」 | `<button class="btn">`。CSS は `.btn{background:var(--ac);color:var(--onac)}`、高さ 52px、角丸 26px、幅 100%（塗りつぶしのアクセント） | `OutlinedButton`、幅いっぱい（`:1740`） | **不一致（M1）**。マネージャー決定の「outlined」とも食い違う → OWNER |
| 読み上げ名 | 許可あり「おやすみモード中も鳴らす」／無し「おやすみモード中も鳴らす（許可が必要）」 | `:1722-1727` 同文 | 一致 |
| 許可なしの間の説明文 | ON の文を薄く出す | `!policyGranted` の時は `hint_on` | 一致 |
| iPhone | 出さない | Android だけの変更 | 一致 |
| 検査画面の行 | 「おやすみモード中にアラーム可能」 | `trouble_dnd_ok` を旧文言のまま使う（効いている時） | 一致 |

## 3. 範囲

- 変えたファイルは 20。どれも #149 の機能（設定・鳴動の判断・チャンネル・検査画面・宣言の表）に関わる。関係の無い変更は見当たらない。
- `MainActivity.kt` と `StopUiPlan.kt` は、`ringChannelId(vibrate, ignoreDnd)` に引数を足したのに合わせた追従だけ。

## 4. テスト

コマンドと結果（Linux コンテナ。音は出していない。Robolectric の ShadowMediaPlayer だけで動かした）:

- head: `LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew --max-workers=2 -q :androidApp:testDebugUnitTest` → exit 0。結果 XML の合計は 268 件で、失敗 0・エラー 0。`DndServiceTest` 12、`DndSilenceTest` 9、`DndPolicyTest` 6、`DndSuppressedStateTest` 4、`IgnoreDndRowTest` 5
- head: `./gradlew --max-workers=2 -q :androidApp:lintDebug` → exit 0。出たのは baseline の通知（`LintBaseline` と `LintBaselineFixed`）だけで、新しい指摘は無い
- base で落ちるか: 一時の worktree `scratchpad/rr191`（`origin/master` a37e822）に新規・変更のテスト 7 ファイルを写して `--tests 'com.calendaralarm.Dnd*' --tests IgnoreDndRowTest` を回した → コンパイルエラー 79 件（`dndAllowsAlarms`・`setIgnoreDnd`・`IgnoreDndRow` などが base に無い）。新規 API なので、base で落ちるのは当然
- 動きの差を直接確かめた: 保存値なし（既定）・通知ポリシーの許可なしの探りテスト 2 本（`scratchpad/BaseProbe191Test.kt`）を書いた。期待値は MISSED（鳴らさない）
  - base: 2 件とも失敗。`expected:<MISSED> but was:<FIRED>`。base は「アラームのみ」でも「優先モード＋アラーム許可あり」でも鳴らしていた
  - head（一時の worktree `scratchpad/rh191`）: 2 件とも通る
  - 一時の worktree は 2 つとも `git worktree remove` で消した。PR の worktree の tracked ファイルは変えていない（`git status --short` は空）
- マネージャー決定の各項目と、それを固めるテスト:
  - OFF ＋ PRIORITY（アラーム許可あり・なし）／ALARMS／NONE → 鳴らさない・MISSED: `DndServiceTest:123,149,161,172`、`DndSilenceTest:35,42,47`。サービスが自分で止まること、音を出さないこと、行が MISSED になることを確かめている
  - 同じ場合の「通知（鳴らせなかった）を残す」: **固めるテストが無い**。`androidApp/src/test` で `MissedNotifier` を参照しているテストは 0 件。`DndServiceTest` が見ているのは前面化の通知（`lastForegroundNotification`）と鳴動の通知（`NOTIFICATION_ID`）だけで、見逃しの通知（`MissedNotifier.kt:18-19`、channel `missed_v1`、id 2）は見ていない（M4）
  - 許可が無ければ OFF 表示で押せない: `IgnoreDndRowTest:73`
  - 許可なしの ON は OFF と同じ: `DndServiceTest:194`、`DndSilenceTest:64`
  - 枠付きの「許可を開く」: `IgnoreDndRowTest:73` は文言と押せることだけを確かめていて、枠付きかどうかは見ていない（M1 のとおり、盤はそもそも塗りつぶし）
- 既定値と移行: キーが無ければ true（`SettingsRepository.kt:184`、`IgnoreDndRowTest:91`）。移行の処理は無い。更新した既存の利用者はキーが無いので ON 扱いになるが、通知ポリシーの許可は出していない。このため実際には OFF と同じに動く（OWNER O1）

## 5. 本文と差分

- 本文「盤の `.btn` の CSS がリポジトリに無いため、Material の OutlinedButton で合わせた」→ **事実と違う**。`design/asset/41384cd4ff5e02c5cffc251dcfb23a2b.css` に `.btn` の定義がある（塗りつぶし・アクセント色）（M1）
- 本文「盤と合った項目: …OFF の動き（動きと、OFF の時の説明文）」→ 盤の OFF の文は、カードの下の「オフにすると」の箱に出すもの。説明行の差し替えではない（M3）
- 本文はアイコンの違いに触れていない（M2）
- 本文「268 件で失敗 0」「lint 新規なし」「hardcoded-ja OK」→ 手元で同じ結果になった
- `doc/android-manifest-declaration.md:36` の #15 は「検査画面 `ui/TroubleshootingScreen.kt:137` が `readPolicyAccessGranted` を共通で使う」と書いている。しかし `:137` は自前で `nm?.isNotificationPolicyAccessGranted` を呼んでいて、共通の関数は使っていない（W1）

## 6. 検査画面の行は鳴動の経路と合っているか

- 鳴動チャンネルの行（`TroubleshootingScreen.kt:110-114`）: `ringChannelId(vibrate, ignoreDnd)` で鳴動と同じチャンネルを見る。条件は重要度 HIGH 以上と、ロック画面で隠さないことだけで、`canBypassDnd` は見ない。このため OFF の人の `_dnd` チャンネルで NG が出続けることは無い。**一致**
- 効いている（ON ＋ 許可あり）時: 鳴動側は常に鳴らし、OS がアラームを通すかで鳴るかどうかが決まる。検査画面は `dndAllowsAlarms` で OK/NG を出す（`TroubleshootingScreen.kt:138,201`）。**一致**
- 効いていない、かつおやすみモード中: 鳴動側は `silencedByDnd` で MISSED にする。検査画面は `blockedByApp = !bypassing && dndActive(filter)` で NG「…鳴らさず、通知だけ残します」を出す（`:141`）。どちらも `effectiveIgnoreDnd`／`dndActive` を通るので、**一致**
- 効いていない、かつおやすみモードでない: 鳴動側は鳴らす。検査画面は OK `trouble_dnd_off`。**一致**
- ずれている所: 許可の読み方が違う。鳴動側は `readPolicyAccessGranted`、検査画面は自前の runCatching。今は意味が同じだが、片方だけ直すとずれる（W1）。
- 自分で OFF を選んだ人（許可あり）が、おやすみモード中に検査画面を開くと NG（赤）になり、`fix` は null なので押しても何も起きない（`:212`）。説明文は「ON にすると鳴ります」（W2・O4）

## 7. 証拠

| 何を | どこに | 結果 |
|---|---|---|
| head の unit テスト | `scratchpad/r191-head.log`、`/home/user/wt191/androidApp/build/test-results/testDebugUnitTest/*.xml` | 268 件、失敗 0 |
| head の lint | `scratchpad/r191-lint.log`、`/home/user/wt191/androidApp/build/reports/lint-results-debug.xml` | baseline の通知だけ |
| base に新しいテストを写した時 | `scratchpad/r191-base.log` | コンパイルエラー 79 件 |
| 探りテスト（base と head） | `scratchpad/BaseProbe191Test.kt`、`scratchpad/r191-probe-base.log`、`scratchpad/r191-probe-head.log` | base 2 件失敗（FIRED）、head 2 件成功 |
| hardcoded-ja | `node scripts/hardcoded-ja/check.mjs`（head と base）、`scratchpad/cnt.mjs` | 309 = 309、ファイルごとの差 0 |
| 以前のコメント | `scratchpad/pr191-comments.txt`（REST で 22 件） | M1・M3 の経緯 |

`scratchpad` は `/tmp/claude-0/-home-user/c98a3d37-2e8b-5f9f-bf50-a9de72bb8786/scratchpad`。

確かめていない事:
- 実機・エミュレータでのおやすみモード中の音（DEVICE の関門）
- 盤の正本（アーティファクト）。リポジトリの写し `1794404197-426d` だけを見た
- スヌーズとテストで鳴らすの経路（O3）

## must（合格に必須）

- **M1 「許可を開く」の見た目と本文の理由** — `ui/SettingsScreen.kt:1740`。盤は `.btn`（塗りつぶしのアクセント、52px、角丸 26px）で、CSS はリポジトリにある。同じ CSS には細い枠の `.btn2`（`box-shadow:inset 0 0 0 1px var(--hair)`）もあるが、盤が選んだのは `.btn` の方。「枠付き」という言い方は、この PR の以前のレビューコメント（`issues/191/comments` の 2026-11-14 の回、「`.btn`（枠付きボタン）」）から来ていて、CSS とは合わない。直し方: (a) `Button`（primary の塗りつぶし、高さ 52dp、角丸 26dp）にする。(b) 枠付きを残すなら、本文の「CSS が無い」を消し、「盤は塗りつぶし、マネージャー決定は枠付き。どちらにするか」を OWNER の節に挙げる。どちらにしても本文の事実の誤りは直す。
- **M2 アイコンの形** — `ui/SettingsScreen.kt:1700` の `Icons.Default.DoNotDisturbOn` は、盤の三日月と違う。直し方: `Icons.Default.Bedtime`、`ModeNight`、`DarkMode` のうち三日月のもの（material-icons-extended に入っていて、`build.gradle.kts:83` で依存済み）にする。または OWNER に挙げる。
- **M3 状態 1 の「オフにすると」箱が無い** — 以前のレビュー（同じコメント欄）は、これを「盤では行の外のプレビュー」と呼び、違いとして本文に載せるよう求めていた。今の本文は違いとして挙げず、「合った」と書いている。 盤はカードの下（`.prev`）に「オフにすると／おやすみモード中は鳴らさず、通知だけ残す」を常に出す。コードは箱を出さず、盤に無い「OFF の時は説明行を差し替える」を作っている（`:1713-1716`）。リポジトリでは `.prev` を `PreviewLine` などの画面上の部品として作っている（`SettingsScreen.kt:188,205`）ので、注記ではなく画面の要素と読む。直し方: カードの下に箱を出し、説明行は盤どおり常に `hint_on` にする（差し替えをやめる）。または差し替えを残す理由を OWNER に挙げる。本文の「OFF の時の説明文が合った」も直す。

- **M4 「通知だけ残す」にテストが無い** — 盤 p149 の OFF の約束は「鳴らさず、**通知だけ残す**」の 2 つ。鳴らさない方は固めてあるが、見逃しの通知を出す方（`AlarmService.kt` の `if (marked) MissedNotifier.post(...)`）を確かめるテストが無い。直し方: `DndServiceTest:161`（OFF ＋ アラームのみ）などに、`nm.activeNotifications` に id 2（channel `missed_v1`）があり、本文に予定名「会議」が入っていることの確認を足す。base には `ignoreDnd` が無く、この経路そのものが無いので、base では落ちる。

## want（直してほしい）

- **W1** `ui/TroubleshootingScreen.kt:137` の自前の許可の読みを、`engine.readPolicyAccessGranted(context)` に替える。宣言の表（`doc/android-manifest-declaration.md:36`）の記述と合い、鳴動の判断とも同じ関数になる。
- **W2** `ui/TroubleshootingScreen.kt:212`。自分で OFF にした人（許可あり）がおやすみモード中に見ると、NG なのに押した先が無い。設定画面の該当行へ飛ばすか、状態を NA か情報にする（どちらにするかは O4）。
- **W3** 既定（保存値なし）・許可なし・「優先モード＋アラーム許可あり」の鳴動経路を固めるテストが head に無い（`DndSilenceTest:64` は純関数だけを見ている）。`scratchpad/BaseProbe191Test.kt` の 2 本（base で FIRED、head で MISSED）を、O1 の答えが出た後の動きとして足す。

## nit

- **N1** `engine/DndPolicy.kt:52` の `dndAllowsAlarmsNow` はどこからも呼ばれていない。消すか、検査画面で使う。

## OWNER（持ち主に聞くこと。must ではない）

- **O1（利用者への影響が大きい）既定 ON ＋ 許可なし ＝ おやすみモード中は鳴らない。** 通知ポリシーへのアクセスは、利用者が設定アプリで自分で許可するまで出ない。いま更新した利用者と新しく入れた利用者は、ほぼ全員が「保存したキーが無い → 既定 ON → 許可なし → OFF と同じ」になる。base では「アラームのみ」や「優先モード＋アラーム許可あり」でも鳴動を始めていた（行が FIRED になる）が、head では鳴動を始めずに MISSED になる。上の探りテストで base FIRED・head MISSED を確かめた（Robolectric で確かめたのは鳴動の開始と行の状態まで。実機で音が聞こえるかは未確認）。Issue #149 の「なぜ欲しいか」は「DND 中にアラームが鳴らず予定を逃すことを防ぎたい」で、更新しただけで鳴らなくなる人が出るのは、この目的と逆向きになる。選択肢: (a) このまま（盤とマネージャー決定のとおり）。(b) 許可なしの ON は base と同じ「OS に任せる」にし、スイッチは盤どおり押せない表示にする。(c) 初回だけ許可の案内を出す。移行の処理は無い（キーが無ければ true）ので、どれを選んでも既存の利用者に同じ形で効く。
- **O2** 「許可を開く」を盤の `.btn`（塗りつぶし）にするか、マネージャー決定の枠付きにするか（M1）。
- **O3** OFF（または許可なし）でおやすみモード中に止めるのは、カレンダーのアラームだけでよいか。今はタイマー（`AlarmKind.TIMER`。`DndServiceTest` はタイマーで試している）、スヌーズの鳴らし直し、「テストで鳴らす」も、`AlarmService` の START の経路を通るなら同じく MISSED になる（スヌーズとテストで鳴らすは未確認。判定が START の共通の経路 `AlarmService.kt:251` にあることから推した）。
- **O4** 自分で OFF を選んだ人のおやすみモード中の検査画面を、NG（赤）にするか、NA か情報にするか（W2）。
- **O5** （本文にもある）盤の説明文「サイレントにしていても、アラームだけは鳴ります」は、NONE（アラームも止める設定）では OS が音を止めるので、実際より強く言っている。盤の文を変えるか。

---
_Generated by [Claude Code](https://claude.ai/code)_
