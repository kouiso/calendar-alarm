# R8 の追加ルール (#220)。Room / WorkManager / kotlinx.serialization / Compose / Glance は
# 各ライブラリが consumer rules を同梱しているのでここには書かない。
# AndroidManifest に書いた Activity / Service / Receiver は AAPT が keep を生成する。
# ここには「ライブラリの rules でも拾えない」ものだけを足す (足す前に R8 の missing_rules.txt で確かめる)。

# pdfbox-android: JPEG2000 (JPX) 用の任意依存 (jp2-android) を同梱していない。
# 今も同梱していないので、JPX 画像入りの PDF の挙動は R8 前後で変わらない
-dontwarn com.gemalto.jp2.JP2Decoder
