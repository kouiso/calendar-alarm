# calendar_alarm

## 環境構築手順

### 必要なツールのインストール

1. **brew のインストール**:
   [Brew のインストール方法](https://brew.sh/ja/)を参照してください。Windows の場合は WSL を利用して環境構築を行います。

2. **asdf のインストール**:

   ```bash
   brew install asdf
   ```

   asdf を使って複数のバージョンの言語ランタイムを管理します。

3. **asdf のパス設定**:
   asdf のパスをシステムの PATH に追加します。これにより、コマンドラインから asdf を利用できるようになります。

4. **Node.js と Yarn のインストール**:
   asdf を使用してプロジェクトで必要な Node.js と Yarn のバージョンをインストールします。

   ```bash
   asdf plugin add nodejs
   asdf install nodejs [適切なバージョン番号]

   asdf plugin add yarn
   asdf install yarn [適切なバージョン番号]
   ```

   もし以下のようなエラーが発生した場合、

   ```bash
   ⛔ Missing one or more of the following dependencies: tar, gpg
   ```

   以下をインストールしてください。

   ```bash
   brew install gpg
   ```

### Flutter 環境のセットアップ

1. **Flutter SDK のインストール**:
   Flutter の公式サイトから SDK をダウンロードし、インストールしてください。
   [Flutter のインストール](https://flutter.dev/docs/get-started/install)

2. **Flutter の環境変数設定**:
   Flutter SDK のパスを環境変数に追加します。

3. **Android Studio のインストール**:
   Android アプリを開発するためには Android Studio が必要です。
   [Android Studio のダウンロード](https://developer.android.com/studio)

4. **Flutter Doctor の実行**:
   Flutter 環境が正しく設定されているか確認するために、コマンドラインで`flutter doctor`を実行します。

5. **Keystore の生成**:
   リリースビルド用の Keystore を生成します。詳細な手順は前述のとおりです。

### clone 後の環境構築手順

1. **.env の編集**:
   `.env.development.example`をコピーして`.env.development`を作成します。

2. **依存関係のインストール**:
   プロジェクトのルートディレクトリで以下のコマンドを実行します。

   ```bash
   yarn install
   ```

3. **開発サーバーの起動**:

   ```bash
   yarn dev
   ```

4. **ブラウザでの確認**:
   `http://localhost:3000`にアクセスして、プロジェクトが正しく表示されるか確認します。

## エディターについて

1. **Visual Studio Code の使用**:
   [Visual Studio Code のダウンロード](https://azure.microsoft.com/ja-jp/products/visual-studio-code/)

2. **推奨の拡張機能**:
   `.vscode/extensions.json`に記載されている拡張機能をインストールしてください。
   [VSCode の拡張機能・設定共有について](https://qiita.com/otsuky/items/f46f5ee9eb11b3a9a4ba)
