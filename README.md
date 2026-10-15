# miharashi-panel

[Miharashi](https://github.com/ysogabe/miharashi)(Linux キオスク・ダッシュボード)を手元で操作する Android アプリです。Android 化した Echo Show 5(LineageOS 18.1、960×480)で動かすことを想定しています。

キオスクの panel API(LAN 内の HTTP、Bearer トークン)に、キーボードと同じキー操作を送ります。画面の切り替えやメニューの論理はキオスク側にあり、このアプリは状態を持ちません。表示はすべて `/api/v1/status` と SSE(`/api/v1/events`)から組み立てます。

設計は miharashi の [docs/panel/DESIGN.md](https://github.com/ysogabe/miharashi/blob/main/docs/panel/DESIGN.md) にあります。このリポジトリは、その §6 の P2(最小版)にあたります。

## できること(v0.2.0)

- 中央の「◀ 前の画面 / ● 決定 / ▶ 次の画面」と、右の「▲ 前の項目 / ▼ 次の項目 / ☰ メニュー」のボタン。キオスクのテンキーの 4 / 5 / 6 / 8 / 2 / 0 と同じ `prev` / `select` / `next` / `up` / `down` / `menu` を送ります。数字は出さず、アイコンと「今押すと何が起きるか」のラベルで出します。ラベルはキオスクが `/status` の `keys` で返すもので(miharashi #51)、画面ごとに変わります(写真の画面なら「前の写真 / 次の写真」、メニュー中なら「上を選ぶ / 移動 / 閉じる」)。今の画面で何もしないキーは灰色で、押しても送りません。`keys` を返さない古いキオスクでは「前の項目 / 次の項目」などの汎用のラベルで出します。押すと即 `POST /api/v1/input` を送り、204 で押下音と触覚を返します。失敗したらボタンが赤く点滅し、再送はしません。
- キオスクのモニターの「消灯 / 点灯 / 自動」。押すと `POST /api/v1/display` を送り、キオスクが Home Assistant の手動オーバーライド(input_select の force_off / force_on / auto)を切り替えます。HA と表示は常に一致します。キオスク側に HA 連携が無ければ灰色です。
- 中央に、表示中の画面名、ページ、写真の n/N、feed の状態。
- キオスクのメニュー(miharashi #49)。メニューを開くと、中央に画面の一覧を出し、選んでいる行を強調します(表示中の画面には「表示中」)。行をタップすると、その画面へ直接移ります(`POST /api/v1/screen`。キオスクはメニューも閉じます)。
- キオスクの自動巡回(miharashi #45)。中央に「自動巡回中」(緑)か「巡回停止中 あと 4:12 で再開」(黄)。キーや画面の選択で止まった巡回が戻るまでの残りを、パネルが毎秒数え下げます。巡回が無効なキオスクや、`rotation` を返さない古いキオスクでは出しません。
- ヘッダーの `API` の点灯。直近 5 秒以内に取れたら緑、15 秒までは `STALE`(黄)、それより古いと `OFFLINE`(赤)。OFFLINE のあいだも、最後の状態を薄く残して出します。
- 起動時と 5 秒ごとに `/status` を読み、並行して SSE を受けます。SSE が切れたら 1、2、4 … 30 秒の間隔で再接続します。
- 端末の起動時とアプリの更新時に自動で前面に出ます。スワイプだけのロック画面より手前に出ます。
- 全画面(immersive)、横固定。触らずに 3 分(設定で変えられる。0 で無効)たつと、画面を真っ黒にしてバックライトを最小にします(焼き付き・残像を避けるため)。触ると点き、その最初のタッチではボタンを押しません。

夜間の減光は P3 です。

## ビルド

JDK 17 と Android SDK(platform 35、build-tools 35)が必要です。`local.properties` に `sdk.dir=...` を書いてから実行します。

```sh
./gradlew testDebugUnitTest assembleDebug
```

単体テストは JVM で動きます。`app/src/test/resources/fixtures/` には、miharashi の実機の `/api/v1/status` をそのまま写した JSON を置いています。

## 端末に入れる

USB か `adb connect` で端末をつないでから実行します。

```sh
# 入れて起動する。「他のアプリの上に表示」の許可(起動時に前面へ出すため)も adb で与える
./scripts/install.sh

# 設定(キオスクの URL とトークン)も書き込む。debug ビルドだけ(run-as を使う)
./scripts/install.sh --url http://192.168.0.132:8790 --token-file panel.token

# ランチャー(HOME)にも指定する。Home を押しても操作盤に戻る
./scripts/install.sh --home
```

端末の上で設定するときは、左下の「設定」をタップします。長いトークンは、入力欄を選んでから `adb shell input text '<token>'` で入れると楽です。

## キオスク側の準備

miharashi の `/etc/miharashi/config.toml` に `[panel]` を書きます。待ち受けは LAN のインターフェースの IP だけにします。

```toml
[panel]
enabled = true
listen = "192.168.0.132:8790"
token_file = "/etc/miharashi/panel.token"
```

## 構成

| 部品 | 役割 |
|---|---|
| `PanelActivity` | 全画面、常時点灯、横固定。onResume で同期を始め、onPause で止める |
| `PanelView` / `PanelLayout` | Canvas での描画とタップの判定。960×480 を基準に拡大・縮小する |
| `KioskClient` | `/status`、`/events`(SSE)、`/input`。`HttpURLConnection` だけを使う |
| `KioskSync` | 5 秒ごとの `/status` と SSE の再接続。結果を UI スレッドへ渡す |
| `PanelState` | `/status` を写した不変オブジェクトと鮮度 |
| `Settings` / `SettingsActivity` | ホストの URL とトークン(SharedPreferences) |
| `BootReceiver` | `BOOT_COMPLETED` と `MY_PACKAGE_REPLACED` で前面に出す |

実行時の依存ライブラリはありません(Kotlin 標準ライブラリだけ)。
