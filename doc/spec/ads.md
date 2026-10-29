# 広告表示の仕様 (#140)

Android と iOS の両方に、アラーム操作を妨げない広告を搭載する。
この文書が採用理由と表示仕様の記録 (受入条件の「採用理由と表示仕様が記録されている」)。

## 広告サービスの比較と選定

| 候補 | 両OSの公式SDK | 同意管理 | 保守負担 | 導入/運用費用 | 判定 |
|---|---|---|---|---|---|
| **AdMob (Google Mobile Ads)** | play-services-ads 24.8.0 / Google-Mobile-Ads-SDK 13.x (SPM) | UMP SDK が GDPR/EEA 同意と iOS ATT を内蔵 | Google が SDK を継続保守。バナー1枠なら追加運用ほぼ無し | 無料 (収益分配のみ)。アカウント費0 | **採用** |
| Meta Audience Network | 両OSありだがiOSはCocoaPods中心 | 同意は自前構築が必要 | 在庫とSDK更新頻度がAdMobより不安定 | 無料 | 不採用 |
| AppLovin MAX | 両OSあり (メディエーション) | UMP相当は別SDKの組合せ | ネットワーク複数運用で保守が重い | 無料だが設定が重い | 不採用 (今回の規模に過剰) |
| Unity Ads | 両OSあり | ゲーム向け。同意は自前 | バナーの在庫が薄い | 無料 | 不採用 |

**採用理由**: 唯一両OSに公式SDKと同意管理 (UMP) の両方が揃い、費用がゼロ、
バナーの在庫が最も安定しているため。競合 (Calendar Alarm Clock Reminder) も
AdMob系のバナーを採っている。

## 表示仕様

- **形式**: 標準バナー (320×50pt/dp) 1枠。全画面型・動画・ネイティブは使わない
- **位置**: 「予定」タブ・一覧ビューの**末尾** (最後の日付カードの下)
- **頻度**: 一覧に1枠のみ。SDK の既定リフレッシュ (~60秒) に任せ、自前のローテーションはしない
- **CLS対策**: 末尾への追記だけなので既存の行・ボタン・ナビゲーションの位置は一切動かない。
  読込中・通信失敗・在庫なし・オフラインでは枠自体を出さない
- **出さない画面**: 鳴動画面 (RingingActivity / RingingView)、ロック画面、停止・スヌーズ操作、
  権限取得シート、設定・アラーム・タイマー各タブ、月/3日/タイムラインの各ビュー
- **アラーム優先**: 広告はビュー描画だけ。広告表示中にアラームが起きても
  鳴動・停止は OS 側の既存経路 (FSI / RingingView) がそのまま優先される
- **広告視聴を利用条件にしない**: 同意拒否・広告なしでも全機能が使える

## 同意・権限・ストア申告

- **同意**: UMP SDK で GDPR/EEA/UK の同意を確認し、要る地域だけフォームを出す。
  iOS の ATT も UMP 経由 (`NSUserTrackingUsageDescription` は UMP 用に追加)。
  同意を拒否してもアラームは使える (canRequestAds=false → 広告を出さないだけ)。
  **同意フォームも広告扱い**: 鳴動中は表示を待ち、表示中に鳴動が始まったら閉じて
  停止・スヌーズ操作を優先させる (両OS)
- **権限**: Android は `com.google.android.gms.permission.AD_ID` を manifest に宣言
  (アプリ内ダイアログは出さない)。iOS は ATT の説明文を Info.plist に追加
- **ストア申告 (本番配信時に要対応)**: Play のデータセーフティに広告SDKの項目を追加、
  App Store 側のプライバシー栄養表示と IDFA 使用の申告、
  `app-ads.txt` の公開 (サイトが無いので先に要検討)
- **データ非送信**: `GADRequest`/`AdRequest` にキーワード・内容連動を一切渡さない。
  予定のタイトル・内容は広告 SDK へ送らない

## テスト広告と本番設定の分離

開発・自動テストは Google 公式のテストIDのみを使う:

- Android アプリID: `ca-app-pub-3940256099942544~3347511713` (manifest placeholder `admobAppId`)
- Android バナー: `ca-app-pub-3940256099942544/6300978111` (BuildConfig `ADMOB_BANNER_UNIT_ID`)
- iOS アプリID: `ca-app-pub-3940256099942544~1458002511` (build setting `ADMOB_APP_ID` → `GADApplicationIdentifier`)
- iOS バナー: `ca-app-pub-3940256099942544/2934735716` (build setting `ADMOB_BANNER_UNIT_ID` → Info.plist `AdMobBannerUnitID`)

本番差し替え方法:

- Android: `./gradlew -PadmobAppId=<実ID> -PadmobBannerUnitId=<実ID>` (または同名の環境変数)
- iOS: `xcodebuild ADMOB_APP_ID=<実ID> ADMOB_BANNER_UNIT_ID=<実ID>` (または project.yml の settings.base 差し替え)

## 本番配信に不足している項目 (未完了として追跡)

1. **AdMob アカウントの開設** とアプリ登録 (Android / iOS それぞれ)
2. **本番のアプリID・広告ユニットID** (両OS)
3. AdMob 管理画面での **GDPR/EEA 同意メッセージの作成・公開** (UMP フォーム内容)
4. AdMob 管理画面での **iOS ATT メッセージの設定** (必要なら)
5. **app-ads.txt** の公開先 (配布用サイトが無い)
6. Play Console の**データセーフティ**申告への広告項目追加
7. App Store Connect の**プライバシー (IDFA使用) 申告**
8. SKAdNetworkItems は最小構成のため、収益最大化には Google 公表の全リスト反映を検討

## 対象機種

- iPhone: 調査着手時点 (2026-10-25) の最新モデル **iPhone 18 系 / iOS 27** を対象に固定
  (iOS 26+ の AlarmKit 前提は既存仕様どおり。deployment target 17.0 のまま)
- Android: minSdk 26 / compileSdk 35 のまま (GMA SDK 25.x の最低は API 24)
