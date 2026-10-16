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

モニターの操作には Home Assistant との連携が必要です。巡回の固定・再開には、本体で自動巡回を有効にし、巡回操作に対応したバージョンを使う必要があります。説明を短くするときも、この条件を残してください。夜間の時刻に応じた減光は作らず、自動消灯で代替する方針です。今後の開発予定として載せないでください。

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
