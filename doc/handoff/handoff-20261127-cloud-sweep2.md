# 引き継ぎ 2026-11-27（クラウドの掃除 2 周目）

書き始めた時刻: 2026-11-27T23:20+09:00。終わったら下の表を最後の状態に直す。

## 目的（1行）

open の課題を全部読み、クラウドで確かめまで終わる物（`claude-cloud`）と、Android の unit テストまで確かめて実機の確認が1つ残る物（`claude-cloud-impl`）を貼り、その場で片付ける。

## 読み分けの結果（164 件）

- 済みで閉じた: #226 #228（行ごとの証拠を課題のコメントに書いた）
- `claude-cloud`: #268 #278 #304 #327 #332 #336
- `claude-cloud-impl`: #229 #255 #272 #286 #348 #350 #352 #384 #407 #408
- 否定のレビューで外した: #146 #148 #217 #224 #227 #231 #237 #266 #310 #340 #342 #367 #383 #416 #417
- 残り: iOS・実機・持ち主の判断・open の PR（#191 #422）・PBI などで対象外

## この環境の制約

- `scripts/setup-android-sdk.sh` が dl.google.com で proxy に 403 を返された（環境の許可リストに無い）。Android の unit テストと lint は PR #426 の GitHub Actions（`all_check.yml` が `ci.sh all` と lint を回す）で確かめる。Markdown だけの変更では all_check.yml は動かないので、コードが入るまでは未確認
- `node scripts/hardcoded-ja/check.mjs` と `node scripts/sync-ai-rule.mjs --check` は手元で回した

## 課題ごとの結果

（実装が終わったら埋める）
