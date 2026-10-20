# 操作パネル LP の管理

Miharashi Panel の紹介ページは、このリポジトリでアプリと一緒に管理します。文書の一覧は [Docs](README.md)、アプリの操作は [使い方](USAGE.md) を参照してください。

- 公開先: https://ysogabe.github.io/miharashi-panel/
- 本文: [site/index.html](../site/index.html)
- 見た目: `site/styles.css`、`site/shots.css`、`site/panel.css`
- 画像: `site/images/panel-v020.png` (Echo Show 5 の実機、960×480、2026-10-16 撮影)
- 公開処理: [.github/workflows/pages.yml](../.github/workflows/pages.yml)

## 内容を更新する

アプリの画面構成や機能を変えたら、LP の説明、画像、バージョン、更新日も見直します。画面に応じたボタンの説明、メニューからの画面選択、自動巡回の固定・再開など、実装済みの機能と今後の予定を分けて書きます。

v0.2.0 の紹介文は Gemini Flash で校正・推敲済みです。文章を校正するときは、平易な日本語になるよう依頼し、機能、数値、対応条件、予定の意味が変わっていないか確認してください。

実機の画像は `adb exec-out screencap -p` で撮影できます。家族の写真、位置情報、トークン、個人情報が写っていないか確認してから追加します。現在の画像は、T3 AGENTS 画面で巡回を固定した状態です。

モニターの操作には Home Assistant との連携が必要です。巡回の固定・再開には、本体で自動巡回を有効にし、巡回操作に対応したバージョンを使う必要があります。説明を短くするときも、この条件を残してください。夜間の時刻に応じた減光は作らず、待機時計とバックライトの減光で代替する方針です。今後の開発予定として載せないでください。

## 手元で確認する

リポジトリのルートで次を実行し、http://127.0.0.1:18765/ を開きます。

```sh
python3 -m http.server 18765 --bind 127.0.0.1 --directory site
```

PC とスマートフォンの幅で、画像が読み込まれること、横にはみ出さないこと、ページ内リンクと FAQ が動くことを確認します。

画像と CSS は `site/` の中に置きます。本体 LP へのリンクは https://ysogabe.github.io/miharashi/ を使い、移動前のフォルダーを指す `../` に依存させません。外部フォント、CDN、アクセス解析は使いません。

## 公開する

GitHub のリポジトリ設定で、**Settings → Pages → Build and deployment → Source** を **GitHub Actions** にします。

`main` の `site/` または公開用ワークフローに変更が入ると、GitHub Actions が `site/` を GitHub Pages に公開します。Actions の「Deploy LP to GitHub Pages」から手動実行もできます。Android のビルドは不要です。

## 旧ページからの移行

移動前の公開先は https://ysogabe.github.io/miharashi/panel/ です。新しい公開先が表示できることを確認してから、`miharashi` 側で旧ページの削除とリンクの更新を行います。本体の LP (`miharashi/site/index.html`) はそのまま残します。

旧ページの削除とリンク更新: [miharashi #64](https://github.com/ysogabe/miharashi/issues/64)。

## 紹介動画を再生成する（Issue #13）

LP のヒーロー直後にある動画は **公開用の仮データ・操作デモ、音声なし**です。`site/images/panel-v020.png` は従来どおり実機の静止画です。動画の生成に実機、個人の設定、写真、API、Home Assistant は使いません。

### 仕組みと編集箇所

- 編集の原本: [`intro-video/edit.json`](intro-video/edit.json)。シーン順・秒数・見出し・補足、10 / 30 / 60 秒版、外枠の製品名・URL を集約。
- 公開用シナリオ: [`IntroVideoTest.kt`](../app/src/test/java/io/github/ysogabe/miharashi/panel/IntroVideoTest.kt)。`PanelActivity` を起動せず、`PanelView` / `PanelLayout` / `PanelState` / `FlipClock` の本物の描画を Robolectric native graphics で Bitmap に出力。別の HTML UI は作っていません。
- 合成・エンコード: [`generate.py`](../tools/intro-video/generate.py)。Pillow は外枠・文字・フェードと Bitmap の配置だけを担当。機能紹介のパネルは 1344×672、導入は 960×480 で、どちらも 2:1 を維持します。
- 実際の `MotionEvent` の DOWN / UP → `onAction` → 200 ms 後の仮応答・状態反映を使います。通常アプリは状態を即座に確定しません。前後の画面切替、メニューの一覧選択、写真 1→2→3→2 / 12、巡回の固定・再開、モニターの消灯・点灯・自動を順に見せます。操作前の画面も反映後の画面も本物の描画です。
- ホスト表記は公開用の `DEMO`。画面名などもコード中の架空データで、既存 fixture の個人の host 等は流用しません。KioskSync / KioskClient / Settings は生成経路に入りません。

キー説明は `github.com/ysogabe/miharashi@a65a89a64cc90a7a56696a0b5f990eed8c92a0d0` の `crates/miharashi-app/src/app/manager.rs:562`（メニュー中の前後は「上を選ぶ・下を選ぶ」）、`app/screens/status.rs:38`（「詳細・概要」）、`app/screens/photo.rs:221`（「情報を隠す・前の写真・次の写真」）と照合しました。`ghq-ref` で指定コミットを読み取り専用で参照し、外部コードは実行していません。

30 秒版は導入 2.5、画面選択 5、写真 5、巡回 5、モニター 4、待機時計 4.5、締め 4 秒。10 秒版は導入・画面選択・締めに絞り、60 秒版は全章をゆっくり見せます。

待機時計は初期設定の **無操作 3 分を維持**します。時計の章の直前だけ、壁時計と単調時計をともに 180,000 ms 進め、待ち時間を省略します。動画と LP にその旨を表示します。通常と同じ IdleTimer の判定を実行し、12:59→13:00 の分境界で本物のフリップを描画します。時計に触れた最初のジェスチャーは操作盤への復帰だけで、`onAction` が呼ばれないことをテストと生成時に確認します。バックライトの実制御は動画では行いません。

巡回操作には本体の自動巡回設定と対応 API、モニター操作には Home Assistant 連携が必要です。動画・LP ともに条件を表示しています。

### 依存と再現環境

2026-10-20 に Linux x86_64 / Debian 13 で生成・検証しました。

| 項目 | 使用バージョン |
| --- | --- |
| JDK | Eclipse Temurin 17.0.20.1+1 |
| Gradle / Android Gradle Plugin / Kotlin | 8.11.1 / 8.7.3 / 2.1.0（リポジトリで固定） |
| Android SDK | API 35 (Android 15)、platform revision 2、build-tools 34.0.0 |
| Robolectric / native runtime | 4.14.1 / nativeruntime-dist-compat 1.0.16 |
| 描画 SDK | `android-all-instrumented:15-robolectric-12650502-i7`、`@GraphicsMode(NATIVE)` |
| Python / Pillow | Python 3.13.5 / Pillow 11.1.0 |
| ffmpeg / ffprobe | 7.1.5-0+deb13u1、libx264、1 thread |
| 外枠のフォント | Noto Sans CJK JP Regular / Bold (2.004)、Noto Sans Mono Regular / Bold (2.006) |
| UI 内のフォント | 上記 Robolectric native runtime / Android SDK に含まれる Android 標準フォント（アプリの指定を維持） |
| TZ / データ | Asia/Tokyo、固定データ（seed 13、乱数は未使用） |

Python のバージョンは手元で `python3 --version`、フォントは `fc-scan` で確認できます。外枠は `/usr/share/fonts/opentype/noto/NotoSansCJK-{Regular,Bold}.ttc`（JP は index 0）と `/usr/share/fonts/truetype/noto/NotoSansMono-{Regular,Bold}.ttf` を使います。フォントは動画に焼き込むため、LP から外部フォントへアクセスしません。

フォントファイル SHA-256（順に CJK Regular / CJK Bold / Mono Regular / Mono Bold）:

```text
b76b0433203017ca80401b2ee0dd69350349871c4b19d504c34dbdd80541690a
faa5f3656a78b2e2d450d27fe8382c778bc2b6bb5ea29c986664a6a435056ceb
6b692c4b6d15ccf59f1c1fe8d11cb8a92f51960f3e9f1f523781755a3af7e29f
b22563d5f03b345a62115e78d8789c9ed99c2cfa8059fad6e7a5c4ced288e21e
```

JDK 17 / Android SDK 35 と build-tools 34.0.0、Pillow、ffmpeg（libx264 対応）、上記 Noto フォントを用意します。Pillow の依存は `tools/intro-video/requirements.txt` で固定しています。必要なら Python 仮想環境を作り、`python3 -m pip install -r tools/intro-video/requirements.txt` で導入してください。初回は Gradle / Maven からテスト用の依存と描画 SDK を取得する通信が必要です。これはアプリの API 通信ではありません。Robolectric の AndroidX テスト依存のため `android.useAndroidX=true` にしていますが、アプリ本体に AndroidX の実行時依存は追加していません。

壁時計は `2026-01-01T04:00:00Z - (時計章の開始秒 + 1.5秒) - 180秒` を開始時刻とし、各フレームで `frame * 1000 / 30` ms 進めます。単調時計は 1,000 ms から同じ幅で進めます。待機の省略以外は時間を飛ばさず、ステータスの鮮度と巡回カウントダウンにも同じ単調時計を使用します。時刻と乱数、実データの取得に生成実行時の日時を使いません。同一環境・入力・設定での再現を対象とし、異なる OS / フォント / 描画 SDK / ffmpeg でのバイト一致は保証しません。

### 生成・圧縮・ポスター

リポジトリのルートで実行します。必要に応じて `JAVA_HOME` と `ANDROID_HOME` を設定してください。

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

# 既存テストと Android debug APK
./gradlew testDebugUnitTest assembleDebug --no-daemon
python3 -m unittest discover -s tools/intro-video -p 'test_*.py' -v

# 10 / 30 / 60 秒をすべて実生成。既存ファイルは拒否する
python3 tools/intro-video/generate.py --version all --out-dir work/intro --poster

# 版の選択・明示的な上書き
python3 tools/intro-video/generate.py --version 30 --out-dir work/intro --poster --overwrite

# LP 用のファイルを直接再生成する場合
python3 tools/intro-video/generate.py --version 30 --out-dir site/videos --poster --overwrite
```

出力先ディレクトリは自動作成します。中間の UI PNG は一時ディレクトリに作り、成功・失敗・SIGINT / SIGTERM の終了時に削除します。エンコード・ffprobe 検証は出力先と同じディレクトリの一時ファイルで行い、成功したファイルだけを atomic に公開します。`--overwrite` なしでは既存ファイルを拒否し、並行して別プロセスが完成物を作った場合も上書きしません。失敗や中断では以前の完成物を維持します。`--version all` は版ごとの確定なので、後の版が失敗しても先に完成した版は残ります。ポスターも同じ保護を使います。エラーは標準エラーと非ゼロ終了で通知します。

完成動画は 1920×1080 / 30 fps / H.264 / yuv420p / BT.709（limited range）、音声なし、faststart です。6 フレームのフェードを使用。LP 用は生成時から **libx264 / preset slow / CRF 20** で圧縮しているため、再圧縮せずに掲載できます。色変換に BT.709 を指定し、x264 にも `colorprim=bt709:transfer=bt709:colormatrix=bt709` を明示しています。`--ffmpeg` でエンコーダーの実行ファイルを指定できます。引数は shell 文字列ではなく配列で渡します。

`--poster` は 30 秒版の実際の完成 MP4 の **1.8 秒**から 1280×720 JPEG（quality 4）を抽出します。既に生成済みの動画からポスターだけを作り直す場合:

```sh
python3 - <<'PY'
import importlib.util
from pathlib import Path
spec = importlib.util.spec_from_file_location('intro', 'tools/intro-video/generate.py')
intro = importlib.util.module_from_spec(spec)
spec.loader.exec_module(intro)
intro.make_poster(Path('site/videos/miharashi-panel-30s.mp4'),
                  Path('site/videos/miharashi-panel-30s-poster.jpg'), overwrite=True)
PY
```

コミット対象は `site/videos/miharashi-panel-30s.mp4` と `site/videos/miharashi-panel-30s-poster.jpg` のみ。`work/intro/` と、それ以外の `site/videos/` の生成物は ignore します。

### 確認結果と公開後の確認

2026-10-20 の実生成結果（すべて ffprobe で映像のみ、長さ・フレーム数・解像度・fps・codec・pixel format・BT.709 を検証し、moov が mdat より前にあることを確認）:

| 版 | フレーム数 | サイズ (bytes) | SHA-256 |
| --- | ---: | ---: | --- |
| 10 秒 | 300 | 403880 | `eb6a1ecff5c2af14a27c4a04703e5fe5cc6db3069caa448cfc309dba2246021d` |
| 30 秒 / LP | 900 | 1019164 | `9edce779538478d42ec4bd975630f138d9fd768335521b16a5dd82d9aafe4034` |
| 60 秒 | 1800 | 1406368 | `5dcc54a83bb1006b16cef09570b80b6c0a3a39794a818e6d6023ad70841bbb89` |
| ポスター | 1280×720 | 54330 | `4498a0130545c289b17536ed870e4a8c38d107986bc95a6a5d831dd91ff54415` |

最終ソースで `testDebugUnitTest`（既存分を含む JUnit 65 件、失敗 0）と `assembleDebug` が成功し、Python の出力保護テスト 8 件も成功しました。APK は `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

10 秒版を実描画から 2 回生成し、`cmp` と SHA-256 で MP4 全体のバイト一致を確認しました。自動テストでも固定時刻の native 描画の一致、固定画像のエンコードの一致、実タッチから遅延応答までの動作、写真枚数・キー説明、巡回・モニター状態、カウントダウン、wake の入力抑止、失敗・上書き・中断時の完成物保護を検証します。30 / 60 秒版の生成時には本物の PanelView が分境界で flip したことと wake が操作を送らないことも assert します。

導入、一覧選択、写真、青い固定カード、モニター消灯、時計の flip、復帰、締めの抽出フレームを目視確認しました。実 CLI に SIGTERM を送り、終了コード 130、既存完成物の維持、一時出力の削除を確認しました。Python の出力保護テストでは存在しないエンコーダー、終了コード 1、実 ffmpeg の途中中断、既存ファイル保護、ポスター失敗、並行生成時の競合を確認します。

再確認の例:

```sh
ffprobe -v error -show_streams -show_format -of json site/videos/miharashi-panel-30s.mp4
sha256sum site/videos/miharashi-panel-30s.mp4 site/videos/miharashi-panel-30s-poster.jpg
python3 -m http.server 18765 --bind 127.0.0.1 --directory site
```

LP は `controls muted loop playsinline preload="metadata"`、poster、フォールバック、常時表示のダウンロードリンクを備え、自動再生しません。PC / スマートフォンで再生・ポスター表示・横はみ出し・ダウンロードを確認してください。この実装段階のブラウザー最終確認は親タスクで行います。

マージ後の Pages 公開・公開 URL の確認も親タスクで実施します。まだ今回の動画を公開したとはみなしません。公開後は次の HEAD リクエストで LP / MP4 が HTTP 200、MP4 が `Content-Type: video/mp4`、poster が画像として配信されることを確認し、実際にページから再生してください。

```sh
curl -I https://ysogabe.github.io/miharashi-panel/
curl -I https://ysogabe.github.io/miharashi-panel/videos/miharashi-panel-30s.mp4
curl -I https://ysogabe.github.io/miharashi-panel/videos/miharashi-panel-30s-poster.jpg
```
