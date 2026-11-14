REVIEW | reviewer: general-purpose, opus | authored no commit on this PR | head 3f401c37180b1d494446b86de8403a583146a880 | axes: pass line, design, scope, tests, body vs diff, evidence | verdict FAIL | must 2 want 2 nit 3

# PR #397 レビュー (issue #269)

- 対象: `/home/user/wt269`。HEAD が `3f401c37180b1d494446b86de8403a583146a880` であることを `git rev-parse HEAD` で確かめた。base は origin/master (`a37e822`)
- 差分: 5ファイル、+201 行
  - `AndroidManifest.xml` +2
  - `CalendarAlarmApp.kt` +8
  - `RestoreReset.kt` 新規 69 行
  - `Daos.kt` +8
  - `RestoreResetTest.kt` 新規 114 行
- 私はこの PR のコミットを書いていない。GitHub への書き込み、push、`/home/user/wt269` の追跡ファイルの編集はしていない (最後に `git status --short` が空なのを確かめた)

## 1. 合格線 (pass line)

| 項目 | 結果 | 根拠 |
|---|---|---|
| 根本原因の修正 | 概ね満たす (条件付き) | `onRestoreFinished` が全体復元の後に呼ばれる、という前提が成り立つ場合に限る。この前提は未確認。復元の印は `RestoreReset.kt:65-68` の `onRestoreFinished` だけが置く。印があれば `RestoreReset.kt:44-46` で2表を消し、resync する。移ってきた予約の行と予定ごとの指定は、次の起動で捨てられる |
| 挙動変更のテストが base で落ち、head で通る | 満たす (補足あり) | base ではコンパイルエラー (後述)。挙動として落ちることは、変異 M1 と M2 で示した |
| DB スキーマ・migration なし | 満たす | `AppDatabase.kt:112` は `version = 7` のまま、`exportSchema = false`。entity の差分は無い。足したのは `@Query("DELETE ...")` 2つだけ (`Daos.kt:15-17`, `Daos.kt:141-143`) |
| i18n / hardcoded-ja | 満たす | 文字列リソースの追加は無い。`node scripts/hardcoded-ja/check.mjs` は「OK: 日本語の文字列リテラルは増えていません (合計 309 個)」。CI の `i18n-check` は success |
| shared の signature / iOS | 対象外 | `shared/` と `iosApp/` に差分は無い |
| 価格・本番・FAD dispatch | 触っていない | 差分に含まれない |
| 作者以外の head 宛レビュー | 無い | `pulls/397/reviews` も `pulls/397/comments` も空 (この文書が最初) |
| 未返答のインラインコメント | 0件 | 同上 |
| issue を閉じる条件 | **満たさない (must 1)** | 下の must-1 を参照 |

確かめ方の判定: issue #269 の「確かめ方」は「Robolectric かビルドで、除外の指定が入っていることを確かめる」。この PR は除外の指定を置かず、方法を「復元後に捨てる」へ変えている (PR 本文「方法の変更」節に明記)。だから確かめ方は字面どおりには満たされず、`Closes #269` は合格線に反する。

## 2. 設計 (design)

### 良い点

- DB は1ファイルなので、表ごとの除外はできない。この判断は正しい。印を `noBackupFilesDir` に置くので、印そのものは別端末へ運ばれない (`RestoreReset.kt:23`)
- 判定に「印が無く、DB に行がある」を使わない (`RestoreReset.kt:17-18` のコメント)。だから、この版へ更新しただけの端末で指定が消える事故を避けている
- 処理の順は、行を消す → 印を消す → resync (`RestoreReset.kt:44-47`)。途中で落ちても、次の起動でやり直せる
- 保存の中身は変えていない、と PR は説明している。根拠は「`fullBackupOnly` 付きの独自 agent でも、`BackupAgent.onFullBackup` の既定の処理がファイル全体を保存する」という前提。私は一次資料で確かめていない（未確認）。`onBackup` / `onRestore` は Unit なので、key/value 経路では何もしない (`RestoreReset.kt:57-63`)

### 懸念

- 起動時の resync と競合する (want-1, 未確認)
  - リセットは `container.appScope.launch` で非同期に走る (`CalendarAlarmApp.kt:104-110`)
  - `MainActivity.kt:171` の `resync("app start")` との間に排他が無い。`AlarmRepository.kt:709` の `resync` に Mutex は無い
  - 先に始まった resync は、`AlarmRepository.kt:767` で復元直後の古い `event_overrides` を読む。リセットの deleteAll がその後に走っても、`applyOverrideCarry` が `AlarmRepository.kt:1111` で `byKey[old].copy(instanceKey = new)` を upsert して書き戻せる
  - 印はもう消費済みなので、書き戻された古い指定は残り続ける
  - リセットは onCreate の直後に始まり、"app start" は最初の composition の後に始まる。だから窓は狭いと見ているが、発生頻度は確かめていない
- `onRestoreFinished` が呼ばれる経路 (クラウド復元・端末間移行) を、PR 本文は断定している。私は一次資料でも実機でも確かめていない (want-2、未確認)

## 3. スコープ (scope)

- 差分は #269 の範囲 (2表と、その仕組み) に収まっている。関係の無い変更は無い
- #266 (端末保護領域の控え) との関係
  - head で `createDeviceProtectedStorageContext|deviceProtected|directBootAware` を grep したら 0件。まだ控えの実装は無い
  - #266 は open。この PR が #266 の分を取りこぼしているわけではない
  - ただし #266 を入れる時は、この「復元後に捨てる」側にも控えの消去を足す必要がある。これを #266 に書いておくとよい (nit-3)

## 4. テスト (tests)

実行したコマンドと結果 (Gradle はいずれも `LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew --max-workers=2 -q`):

| # | 場所 | 内容 | 結果 |
|---|---|---|---|
| T1 | `/home/user/wt269` (head) | `:androidApp:testDebugUnitTest --tests com.calendaralarm.RestoreResetTest` | tests=4 failures=0 |
| T2 | scratch worktree `rv397` (origin/master) に head のテストだけを写す | 同上 | **コンパイル失敗** (`Unresolved reference 'RestoreMarker'` / `'RestoreReset'` / `'RestoreMarkerAgent'`)。base で落ちはするが、挙動ではなくコンパイルで落ちている |
| M1 | scratch (head) で `runIfRestored` の先頭に `if (true) return false` を足す (base と同じ「何もしない」) | 同上 | 4件中2件失敗 (`restoreFinished_marksThenNextLaunchClearsRowsAndResyncs`, `firstInstall_emptyDb_isHarmless`)。戻した |
| M2 | scratch (head) で `onRestoreFinished` の `RestoreMarker.mark(this)` を消す | 同上 | 4件中1件失敗。戻した |
| M3 | scratch (head) の Manifest から `android:backupAgent=".RestoreMarkerAgent"` を消す | 既存4件 + 確認用プローブ | **既存4件はすべて通る**。プローブだけ失敗。戻した |
| M4 | scratch (head) の Manifest から `android:fullBackupOnly="true"` を消す | 既存4件 + プローブ (FLAG_FULL_BACKUP_ONLY も見る版) | **既存4件はすべて通る**。プローブだけ失敗。戻した |
| P0 | scratch (head)、変異なし | 既存4件 + プローブ | 5件すべて成功。Robolectric が `backupAgentName` と `FLAG_FULL_BACKUP_ONLY` を Manifest から埋めることを確かめた |
| F | scratch (head)、変異なし、結果のディレクトリを消してから実行 | `:androidApp:testDebugUnitTest` 全体 | 42 クラス、234 件、失敗 0 |

- lint は自分では回していない。CI の `build` / `verify (fix)` / `verify (control-...)` / `plan` / `i18n-check` が success であることを、head の check-runs (REST) で確かめた。CodeRabbit は neutral (レビュー上限に達しており、指摘は無い)
- Codex のレビューは完了していない。`issues/397/comments` の状態の印は `"has_any_findings":true,"has_unresolved_findings":true,"status":"failed"`。本文は「We couldn’t complete this review」、続くコメントは使用上限の通知。reviews とインラインコメントは空なので、この head に bot のレビューは実際には1件も走っていない。「指摘なし」と読んではいけない
- 評価
  - `RestoreReset` / `RestoreMarkerAgent` の挙動は M1・M2 で守られている
  - Manifest の配線は守られていない (M3・M4 が生き残る) → must-2
  - `fullBackupOnly` が無いと、独自 agent は key/value 経路になると見ている。その場合 `onBackup` は Unit なので、バックアップが空になるおそれがある（未確認。プラットフォームの挙動は一次資料でも実機でも確かめていない）。この1行は消されると被害が大きいかもしれないのに、テストが無い

後片付け: scratch worktree は残してある。場所は `/tmp/claude-0/-home-user/c98a3d37-2e8b-5f9f-bf50-a9de72bb8786/scratchpad/rv397`。head (`3f401c3`) に detached のまま、`git status` は clean。この worktree の中で消したもの:
- T2 で写したテストファイル (head へ checkout する前に消した)
- プローブ `RestoreWiringProbeTest.kt` (2回作って、2回とも消した)
- `androidApp/build/test-results/testDebugUnitTest` (ビルド成果物。集計し直すために消した)

`/home/user/wt269` の中では何も消していない。

## 5. 本文と差分の一致 (body vs diff)

| 本文の記述 | 差分 | 一致 |
|---|---|---|
| `RestoreMarkerAgent` が `onRestoreFinished` で印を置く | `RestoreReset.kt:56-69` | 一致 |
| `runIfRestored` が2表を全削除し、印を消して resync | `RestoreReset.kt:42-49`, `CalendarAlarmApp.kt:104-110` | 一致 |
| DAO の `deleteAll()` 2つ。entity / version / migration は変更なし | `Daos.kt` の +8 だけ | 一致 |
| Manifest に `backupAgent` と `fullBackupOnly` | `AndroidManifest.xml:36-37` | 一致 |
| テスト4件。変異で2件失敗 | M1 で再現した (2件失敗) | 一致 |
| 「クラウドバックアップも端末間移行も `onRestoreFinished` が呼ばれる」 | 実機での確認は無い。端末間移行は本文でも「未確認」と書いてあるが、クラウド側は印が無い | want-2 |
| `Closes #269` | 確かめ方 (除外の指定) を満たさない | must-1 |

## 6. 根拠 (evidence)

- PR 本文、issue #269・#266、head の check-runs、reviews、インラインコメント、issue コメントは、`gh api` (REST) で読んだ
- 上の T1〜F は、すべて私自身が実行した結果
- 未確認のもの
  - `MainActivity` の resync との競合の発生頻度
  - 実機・エミュレータでの `bmgr restore`
  - クラウド復元・端末間移行で `onRestoreFinished` が呼ばれるか
  - 復元中に独自 Application が起動しないこと (restricted mode)
  - `fullBackupOnly` と `onFullBackup` の既定の挙動 (Android の公式リファレンスは今回取得していない)

## must

1. **`Closes #269` を `Refs #269` に変える** (または、オーナーが issue #269 の確かめ方を「復元の印で2表を捨てること」に書き換えてから Closes にする)
   - 根拠: issue の確かめ方は「除外の指定が入っていることを確かめる」。この PR は除外の指定を置いていない (本文で方法の変更を明言している)
   - 合格線は「確かめ方を満たした時だけ閉じる、満たさなければ Refs」。方法の変更を受け入れるかどうかはオーナーが決めること
2. **Manifest の配線をテストで守る** (M3・M4 の変異が既存テストをすべて通り抜けた)
   - 案: `RestoreResetTest` にテストを1件足す。P0 で通り、M3・M4 で落ちることを確かめた
     ```kotlin
     @Test
     fun manifestWiresRestoreMarkerAgentWithFullBackupOnly() {
         val info = context.packageManager.getApplicationInfo(context.packageName, 0)
         assertEquals(RestoreMarkerAgent::class.java.name, info.backupAgentName)
         assertTrue((info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0)
         assertTrue((info.flags and ApplicationInfo.FLAG_FULL_BACKUP_ONLY) != 0)
     }
     ```
   - 本文の変異の記録に、「`backupAgent` / `fullBackupOnly` を消すとこのテストが落ちる」を足す

## want

1. **起動時の resync をリセットの完了まで待たせる** (`CalendarAlarmApp.kt:104-110` と `MainActivity.kt:171`、`AlarmRepository.kt:767` と `:1111`。未確認)
   - 注意: `runIfRestored` は自分の中で `resync` を呼ぶ (`RestoreReset.kt:47`)。だから「`resync` の先頭で、リセット全体の完了を待つ」形にすると、自分の呼び出し元を待つことになり、デッドロックする。kotlinx の `Mutex` は再入できないので、`runIfRestored` 全体と `resync` を同じ Mutex で包む形も同じく固まる
   - 案: 待つ対象を、2表の削除と印の消費 (`RestoreReset.kt:44-46`) だけにする
     - 例: `AppContainer` に `restoreCleared: CompletableDeferred<Unit>` を置く
     - `runIfRestored` は 44-46 行の直後 (印が無ければ 43 行の時点) に `complete` する。そのあとで自前の `resync()` を呼ぶ
     - `AlarmRepository.resync` は先頭で `restoreCleared.await()` する
   - リセットが失敗した時も `finally` で必ず `complete` する。そうしないと、以後の resync がすべて止まる
   - 「リセット中に別の resync が古い指定を書き戻さない」ことは、決定的なテストにできるなら足したい。既存の `afterResyncReadForTest` は指定を読む前に呼ばれるので、そのままでは再現できない
2. **PR 本文の「クラウドバックアップも ... `onRestoreFinished` が呼ばれる」に「（未確認）」を付ける** か、`BackupAgent.onRestoreFinished` の公式リファレンスの記述を引く。端末間移行と同じ扱いにする

## nit

1. `CalendarAlarmApp.kt:105` の `runCatching` は `CancellationException` も握りつぶし、失敗を記録しない。失敗時は `audit(...)` かログに1行残すと、復元後に指定が残った時に追える
2. バックアップの時点で FIRED / SNOOZED だった「1回のみ」の単発アラームが、復元後にもう一度鳴る
   - 理由: 行を消すと `consumedAlarmIds` (`AlarmRepository.kt:730-733`) から外れる。`disableConsumedOneShots` が OFF にするのは、DISMISSED (`AlarmRepository.kt:398-401`) と MISSED (`:580`) になった時だけ。FIRED (`:364`) では OFF にしない
   - issue の元案 (表を除外する) でも同じことが起きるので、挙動としては同じ。本文の「残り」に一言書いておくとよい
3. #266 を入れる時は、端末保護領域の控えもこの復元リセットで消す必要がある。#266 にその旨を1行書いておくとよい (今回の差分の範囲外)

---
_Generated by [Claude Code](https://claude.ai/code)_
