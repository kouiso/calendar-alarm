# calendar_alarm

## 環境構築手順

### 必要なツールのインストール

1. **Android Studio のインストール**:
   Android アプリを開発するためには Android Studio が必要です。
   [Android Studio のダウンロード](https://developer.android.com/studio)

1. **XCode があるか確認**:
   windows は除外
   XCode がインストールされているか確認してください。

   [XCode のダウンロード](https://developer.apple.com/jp/xcode/)

1. **brew のインストール**:
   [Brew のインストール方法](https://brew.sh/ja/)を参照してください。Windows の場合は WSL を利用して環境構築を行います。

1. **asdf のインストール**:

```bash
brew install asdf
```

asdf を使って複数のバージョンの言語ランタイムを管理します。

1. **asdf のパス設定**:
   asdf のパスをシステムの PATH に追加します。これにより、コマンドラインから asdf を利用できるようになります。

最低限以下を.zshrc or .bash_profile に追加

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export JAVA_HOME="$(asdf where java adoptopenjdk-8.0.282+8)"
export PATH=$JAVA_HOME/bin:$PATH
export GEM_HOME=$HOME/.gem #windowsは除外
export PATH=$GEM_HOME/bin:$PATH #windowsは除外
export PATH=$HOME/.asdf/shims:$PATH
export PATH=$ANDROID_HOME/tools/bin:$PATH
```

1. **asdf を使ったインストール**:
   asdf を使用してプロジェクトでインストールします。

```bash
asdf plugin add flutter dart ruby #windowsはrubyを除外
asdf plugin add java https://github.com/halcyon/asdf-java.git
```

もし以下のようなエラーが発生した場合、

```bash
⛔ Missing one or more of the following dependencies: tar, gpg
```

以下をインストールしてください。

```bash
brew install gpg
```

1. **cocoapods の install**:
   windows は除外

```bash
gem install cocoapods
pod
```

1. ** android licence の確認 **

全て y を入力

```bash
flutter doctor --android-licenses
```

1. ** sdkmanager の確認 **

```bash
sdkmanager
```

1. **Keystore の生成**:
   できるかどうかだけ確認しておくこと
   sha の値が出れば OK

```bash
cd android
./gradlew signingReport
```

1. **pod install**:
   windows は除外

```bash
cd ios
pod install
```

1. **flutter device の確認**

```bash
flutter devices
```

1. **flutter doctor の実行**:
   windows のみ cocoapod の設定は x で良い

```bash
flutter doctor
```

## エディターについて

1. **Visual Studio Code の使用**:
   [Visual Studio Code のダウンロード](https://azure.microsoft.com/ja-jp/products/visual-studio-code/)

2. **推奨の拡張機能**:
   `.vscode/extensions.json`に記載されている拡張機能をインストールしてください。
   [VSCode の拡張機能・設定共有について](https://qiita.com/otsuky/items/f46f5ee9eb11b3a9a4ba)
