REVIEW | reviewer: general-purpose subagent, Opus 5.5 medium | authored no commit on this PR | head 3a819333e4bba2710d3e63ee3b04a509754455fb | axes: pass line, design, scope, tests, body vs diff, evidence | verdict PASS | must 0 want 0 nit 0

対象は head `3a81933`、base は master `e224f93`。PR の差分は 7 ファイル、+533。Gradle はこの環境で回せない（dl.google.com への通信が止められている）。テストは読んで経路を追い、hosted CI の結果で判断した。変異は回していない。

## 指摘
0件

## 軸ごとの確認
- **pass line**: 復元の印があれば 2 表を 1 つのトランザクションで消し、印を消して resync する（`RestoreReset.kt:54-78`）。これは #269 で決めた代わりの方法（DB が 1 ファイルなので表ごとの除外はできず、復元されたら捨てる）と一致する。本文の 1 行目は `Refs #269` で正しい。issue の確かめ方は「除外の指定」なので、閉じるにはオーナーの判断が要る。これはこの PR の欠陥ではない。DB スキーマと migration の変更は 0件。`AppDatabase.kt` は差分に無く、足したのは `@Query("DELETE ...")` 2 つ（`Daos.kt:15-17`, `:141-143`）だけ
- **design（want 1 と固まる経路）**: 門は `AppContainer.restoreGate`（`CalendarAlarmApp.kt:44`）にあり、`repository` を作る lazy の中でコンストラクタに渡す（`:98`）。`container` も lazy（`:110`）なので、`onCreate` より前に `SyncWorker` が `container.repository` に触れても、その時点で門が付いている。want 1 は解消。固まる経路は見つからなかった。調べた範囲は次のとおり
  - リセット自身の resync: `runIfRestored` の `finally { onCleared() }` で門を開けてから呼ぶ（`:72-76`）
  - `launchAtStart`: 取り消しは投げ直し、失敗は記録し、`finally` で門を開ける（`:89-97`）
  - `SyncWorker.kt:35` と `BootReceiver` は `repository.resync` を通るので門を待つだけ。開ける側には関わらない
  - resync の呼び出し元を全部 grep した。`db.withTransaction` の中から resync を呼ぶ箇所は無い（`withTransaction` は `AlarmRepository.kt:1117` と `RestoreReset.kt:67` の 2 か所だけで、どちらも中で resync を呼ばない）。resync を `runBlocking` で待つ箇所も無い（`main` の `runBlocking` は Migration5To6 の設定読みだけ）。なので、門を待つ resync が、開ける側の DB や main スレッドを握ることは無い
  - `AlarmRepository(` を作る箇所は本体では `CalendarAlarmApp.kt:77` の 1 か所だけ。試験の 14 か所は `RestoreGateResyncTest` を除いて `restoreGate` を渡さず、既定の `null` で素通りする。既存の試験が固まることは無い
- **design（nit 4、withTransaction）**: `withContext(Dispatchers.IO) { db.withTransaction { 2 つの suspend DAO } }`。room-ktx 2.7.1 の `withTransaction` は自分のトランザクション用ディスパッチャへ移るので、外側の IO は無くても動くが害も無い。同じ API は `AlarmRepository.kt:1117` で既に使われている。使い方は正しい
- **scope**: 0件。PR の差分は Manifest の 2 属性、本体の Kotlin 4 ファイル、試験 2 ファイル。`shared/` と `iosApp/` に差分は無い。`cc3d012` は master の取り込みで、PR の差分には出ない。前の `holdResyncUntil` は消えていて、参照も 0件
- **tests（`RestoreGateResyncTest`）**
  - 1 件目: 門は `CoroutineStart.LAZY` の `async` で、`await` されて初めて動き、`first.complete("gate")` を呼ぶ。読み取りの差込口 `afterResyncReadForTest`（`AlarmRepository.kt:728`）は `first.complete("read")` を呼ぶ。`:717` の `restoreGate?.await()` を消すと、resync は門を起動しないまま `markMissed` と `all()` を通って `"read"` が先に入る。門はほかに誰も await しないので `"gate"` は入らない。結果、`:180` が必ず落ちる。待ちがある時は、門が終わるまで読み取りに進めない。どちらの場合も順番が決まっていて、sleep も時間の窓も無い（`withTimeout` は失敗時の上限にすぎない）
  - 門が開いた後の 2 回の resync はどちらも削除の後に読む。なので、指定 0件・古い行が無い・missed 0 は決定的に成り立つ
  - 2 件目: `assertSame(container.restoreGate, container.repository.restoreGate)` は、`:98` の配線を消すと `null` になって落ちる。`onCreate` の `startRestoreReset()` を消すと門が開かず、`withTimeout(10_000)` で落ちる（両方の試験クラスの setUp も落ちる）
  - `RestoreResetTest` は 7 件。setUp で本体の起動の後始末が門を開けるのを待ってから印を置くので、本体が試験の印を先に消費する競合は無い。本体は門を開けた後に印を触らない（`:63-73`）
  - 気づいた点（指摘にはしない）: `launchAtStart` の失敗の試験は resync の失敗で、門はその前に `onCleared` で開いている。削除の失敗の時に `launchAtStart` の `finally` が門を開けることは固定されていない。ただし `runIfRestored` 側の `finally` が同じことをするので、挙動の穴は無い
- **body vs diff**: 不一致 0件。「やったこと」6 項目、門を開く時（削除と印の消費の直後・印が無ければ判定の直後・失敗時も）、自分を待って固まらない理由、試験 7 件と 2 件の中身、試験の競合を避ける待ち、「`restoreGate?.await()` を消すと落ちる（経路を読んで確認）」は、どれもコードのとおり。「245 件・失敗 0」と変異の結果は e5818f8 についての記述で、私は回していないので確かめていない。`残り` に Smart Switch の経路と、同じ端末で入れ直した時の挙動が足されている
- **evidence**: 手元の head の worktree で `node scripts/hardcoded-ja/check.mjs` は OK（合計 309 個）で exit=0、`node scripts/sync-ai-rule.mjs --check` は in sync で exit=0（記録: `/tmp/claude-0/-home-user-calendar-alarm/4883ef84-55b1-5e04-8735-0a29b98f7ecb/scratchpad/localcheck-397.txt`）。インラインのレビュースレッドは 0件

## hosted check（head `3a81933`、05:44Z 時点）
- `build` success（05:36:14Z〜05:39:24Z）https://github.com/kouiso/calendar-alarm/actions/runs/43681786497/job/132713763069 。ログで `bash scripts/ci.sh all` の `:androidApp:testDebugUnitTest` が cache を使わずに実行され（05:38:02Z）、BUILD SUCCESSFUL だったことを確かめた。`ci.sh lint` は「Lint found no new issues」
- `i18n-check` success（05:36:21Z）
- `plan` success（05:36:16Z）
- `verify (control-3d106817)` success（05:42:04Z）
- `verify (fix)` と `verify (fix-minified)` は in_progress（05:36:19Z に開始）。**マージの前に完了を確かめること**
- `CodeRabbit` neutral（05:36:16Z）。bot の指摘は出ていない

## 前回の指摘（e5818f8、FAIL）の状態
- must 1（門の待ちと起動への配線にテストが無い）: 解消。`RestoreGateResyncTest` の 2 件で、上のとおり消すと落ちる
- want 1（門をリポジトリを作る時に渡す）: 解消。コンストラクタで渡す形になった
- want 2（印が置かれない移行経路と、入れ直した時の挙動を本文に書く）: 解消。本文の「残り」にある
- nit 1（KDoc がずれていた）: 解消。`AlarmRepository.kt:934-935` で `afterResyncReadForTest` の直上に戻った
- nit 2（`consume` が `delete` の戻り値を見ない）: 解消（`RestoreReset.kt:42-46`）
- nit 3（`runCatching` が取り消しを握りつぶし、失敗を記録しない）: 解消。起動側の `runCatching` は無くなり、`launchAtStart` は取り消しを投げ直して失敗を記録する。`onRestoreFinished` も失敗を記録する
- nit 4（2 つの削除が別のトランザクション）: 解消。`withTransaction` で 1 つにまとめた
- 前々回（3f401c3）の nit 3（#266 で控えも消す）: 本文には書いてある。#266 側に書いたかは確かめていない
