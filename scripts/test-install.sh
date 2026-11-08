#!/bin/sh
# scripts/install.sh の試験。偽の adb を PATH の先頭に置き、呼び出しと標準入力を記録して確かめる(実機は要らない)。
#   ./scripts/test-install.sh
# 確かめること: 指定していない既存の設定が消えない・片方だけの指定・XML の特殊文字や @ の拒否・トークンの条件・
#   printf の書式文字列に値を埋め込まない・トークンがコマンド行に出ない・--dry-run が端末に触れない・pm grant の失敗で続行。
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
INSTALL="$HERE/install.sh"
PKG=io.github.ysogabe.miharashi.panel
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir "$WORK/bin"
cat > "$WORK/bin/adb" <<'EOF'
#!/bin/sh
# 偽の adb。FAKE_DIR に記録する: calls.log=呼び出し(引数)、written.xml=書き込みが成功したあとの端末の panel.xml
# FAKE_PANEL_XML があればそれを run-as cat の結果にする(なければ「ファイルがない」)。FAKE_GRANT_FAIL=1 で pm grant を失敗、
# FAKE_READ_FAIL=1 で読み出しを(ファイルがない以外の理由で)失敗させる。
# 書き込み(run-as sh -c '...')は、端末の代わりの $FAKE_DIR/dev/shared_prefs の中で、渡されたスクリプトを本当に実行する
# (一時ファイルへの書き込み・行数と </map> の確認・mv)。FAKE_WRITE_FAIL で書き込みの失敗を再現する:
#   partial = 標準入力を 3 行だけ読んで切れる(adb は非ゼロ)、dropmid = 途中の 1 行が欠けて末尾の </map> だけ届く(adb は成功を返す)、
#   kill    = 一時ファイルに 3 行書いたところで接続が落ち、端末側の後始末も走らない(adb は非ゼロ)
echo "adb $*" >> "$FAKE_DIR/calls.log"
case "$*" in
    *"cat > shared_prefs/panel.xml.new"*)
        dev="$FAKE_DIR/dev"
        mkdir -p "$dev/shared_prefs"
        if [ ! -f "$dev/shared_prefs/panel.xml" ] && [ -n "${FAKE_PANEL_XML:-}" ] && [ -f "$FAKE_PANEL_XML" ]; then
            cp "$FAKE_PANEL_XML" "$dev/shared_prefs/panel.xml"
        fi
        inner=${2#run-as * }
        case "${FAKE_WRITE_FAIL:-}" in
            partial) head -n 3 | (cd "$dev" && eval "$inner"); exit 1 ;;
            dropmid) sed '4d' | (cd "$dev" && eval "$inner") || exit 1 ;;
            kill) head -n 3 > "$dev/shared_prefs/panel.xml.new"; exit 1 ;;
            *) (cd "$dev" && eval "$inner") || exit 1 ;;
        esac
        cp "$dev/shared_prefs/panel.xml" "$FAKE_DIR/written.xml" ;;
    *"cat shared_prefs/panel.xml"*)
        if [ "${FAKE_READ_FAIL:-0}" = 1 ]; then echo "error: device offline"; exit 1; fi
        if [ -n "${FAKE_PANEL_XML:-}" ] && [ -f "$FAKE_PANEL_XML" ]; then cat "$FAKE_PANEL_XML"
        else echo "cat: shared_prefs/panel.xml: No such file or directory"; exit 1; fi ;;
    *"pm grant"*) if [ "${FAKE_GRANT_FAIL:-0}" = 1 ]; then echo "Operation not allowed"; exit 1; fi ;;
esac
exit 0
EOF
chmod +x "$WORK/bin/adb"
echo apk > "$WORK/app.apk"

PANEL_TOKEN=panel-token-0123456789abcdef
DAIDAI_TOKEN=daidai-token-0123456789abcdef
printf '%s\n' "$PANEL_TOKEN" > "$WORK/panel.token"
printf '%s\n' "$DAIDAI_TOKEN" > "$WORK/daidai.token"

cat > "$WORK/existing.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="url">http://old-panel.example:8790</string>
    <string name="token">$PANEL_TOKEN</string>
    <int name="sleep_minutes" value="7" />
    <boolean name="auto_brightness" value="false" />
    <float name="dark_lux" value="2.5" />
    <string name="daidai_url">http://old-bridge.example:8800</string>
    <string name="daidai_token">old-daidai-token-0123456789</string>
    <boolean name="daidai_auto_listen" value="false" />
    <int name="daidai_idle_seconds" value="90" />
</map>
EOF

PASS=0
FAIL=0
CASE=
STATUS=0

# run NAME [FAKE_ENV=...]... -- install.sh の引数
# 偽の adb で install.sh を実行する。結果は $WORK/out(標準出力)、$WORK/err(標準エラー)、STATUS(終了コード)
run() {
    CASE=$1
    shift
    rm -rf "$WORK/fake"
    mkdir "$WORK/fake"
    : > "$WORK/fake/calls.log"
    envs=
    while [ $# -gt 0 ] && [ "$1" != -- ]; do envs="$envs $1"; shift; done
    shift
    STATUS=0
    # shellcheck disable=SC2086
    env PATH="$WORK/bin:$PATH" FAKE_DIR="$WORK/fake" APK="$WORK/app.apk" $envs "$INSTALL" "$@" > "$WORK/out" 2> "$WORK/err" || STATUS=$?
}

ok() { PASS=$((PASS + 1)); echo "ok   $CASE: $1"; }
ng() { FAIL=$((FAIL + 1)); echo "FAIL $CASE: $1" >&2; }

expect_status() { if [ "$STATUS" = "$1" ]; then ok "終了コード $1"; else ng "終了コードが $STATUS(期待 $1)"; fi; }
written() { cat "$WORK/fake/written.xml"; }
expect_written_has() { if written | grep -qF -- "$1"; then ok "書き込みに $1"; else ng "書き込みに無い: $1"; fi; }
expect_written_lacks() { if written | grep -qF -- "$1"; then ng "書き込みに有る: $1"; else ok "書き込みに無い(正): $1"; fi; }
expect_count() { # $1=パターン $2=個数
    n=$(written | grep -cF -- "$1" || true)
    if [ "$n" = "$2" ]; then ok "$1 は $2 件"; else ng "$1 が $n 件(期待 $2)"; fi
}
expect_not_written() { if [ -e "$WORK/fake/written.xml" ]; then ng "書き込みが行われた"; else ok "書き込まない"; fi; }
expect_no_adb() { if [ ! -s "$WORK/fake/calls.log" ]; then ok "adb を一度も呼ばない"; else ng "adb が呼ばれた: $(cat "$WORK/fake/calls.log")"; fi; }
expect_stderr_has() { if grep -qF -- "$1" "$WORK/err"; then ok "標準エラーに $1"; else ng "標準エラーに無い: $1($(cat "$WORK/err"))"; fi; }
expect_calls_lack() { if grep -qF -- "$1" "$WORK/fake/calls.log"; then ng "コマンド行に有る: $1"; else ok "コマンド行に出ない: $1"; fi; }
expect_calls_has() { if grep -qF -- "$1" "$WORK/fake/calls.log"; then ok "呼び出しに $1"; else ng "呼び出しに無い: $1"; fi; }

# ---- 1. 設定なしの実行: APK・appops・pm grant・起動。書き込まない
run "設定なし" --
expect_status 0
expect_calls_has "install -r $WORK/app.apk"
expect_calls_has "pm grant $PKG android.permission.RECORD_AUDIO"
expect_calls_has "am start -n $PKG/.PanelActivity"
expect_not_written

# ---- 2. --daidai-url だけ: 既存の url / token / sleep_minutes / daidai_token などが残る
run "daidai-url だけ" FAKE_PANEL_XML="$WORK/existing.xml" -- --daidai-url http://192.168.0.132:8800/
expect_status 0
expect_written_has "<string name=\"daidai_url\">http://192.168.0.132:8800</string>"
expect_written_has "<string name=\"url\">http://old-panel.example:8790</string>"
expect_written_has "<string name=\"token\">$PANEL_TOKEN</string>"
expect_written_has "<int name=\"sleep_minutes\" value=\"7\" />"
expect_written_has "<string name=\"daidai_token\">old-daidai-token-0123456789</string>"
expect_written_has "<boolean name=\"daidai_auto_listen\" value=\"false\" />"
expect_written_has "<int name=\"daidai_idle_seconds\" value=\"90\" />"
expect_written_has "<boolean name=\"auto_brightness\" value=\"false\" />"
expect_written_has "<float name=\"dark_lux\" value=\"2.5\" />"
expect_written_lacks "old-bridge.example"
expect_count "name=\"daidai_url\"" 1
expect_count "<map>" 1
expect_count "</map>" 1

# ---- 3. --daidai-token-file だけ
run "daidai-token だけ" FAKE_PANEL_XML="$WORK/existing.xml" -- --daidai-token-file "$WORK/daidai.token"
expect_status 0
expect_written_has "<string name=\"daidai_token\">$DAIDAI_TOKEN</string>"
expect_written_has "<string name=\"daidai_url\">http://old-bridge.example:8800</string>"
expect_written_has "<string name=\"url\">http://old-panel.example:8790</string>"
expect_written_lacks "old-daidai-token"
expect_count "name=\"daidai_token\"" 1
expect_calls_lack "$DAIDAI_TOKEN"

# ---- 3b. panel のトークンだけ差し替えて、端末の既存の daidai_token と同じになる場合は拒否(逆方向と同じ。何も書かない)
printf '%s\n' old-daidai-token-0123456789 > "$WORK/same-as-daidai.token"
run "panel token が既存の daidai_token と同じ" FAKE_PANEL_XML="$WORK/existing.xml" -- --token-file "$WORK/same-as-daidai.token"
expect_status 2
expect_not_written
expect_stderr_has "同じ値"
# 同時に別のだいだいのトークンも指定するなら、合成後は別の値なので通る
run "panel token が既存の daidai_token と同じでも、同時に別の値へ替えるなら通る" FAKE_PANEL_XML="$WORK/existing.xml" -- --token-file "$WORK/same-as-daidai.token" --daidai-token-file "$WORK/daidai.token"
expect_status 0
expect_written_has "<string name=\"token\">old-daidai-token-0123456789</string>"
expect_written_has "<string name=\"daidai_token\">$DAIDAI_TOKEN</string>"
# 端末の設定がない(新規)なら比べる相手がいないので通る
run "新規で panel token だけ" -- --token-file "$WORK/same-as-daidai.token"
expect_status 0

# ---- 4. --url と --token-file だけ(panel): daidai_* と sleep_minutes が残る
run "panel の url/token だけ" FAKE_PANEL_XML="$WORK/existing.xml" -- --url http://192.168.0.132:8790 --token-file "$WORK/panel.token"
expect_status 0
expect_written_has "<string name=\"url\">http://192.168.0.132:8790</string>"
expect_written_has "<string name=\"daidai_url\">http://old-bridge.example:8800</string>"
expect_written_has "<string name=\"daidai_token\">old-daidai-token-0123456789</string>"
expect_written_has "<int name=\"sleep_minutes\" value=\"7\" />"
expect_count "name=\"url\"" 1
expect_count "name=\"token\"" 1

# ---- 5. --sleep-minutes だけ(昔は url/token が両方要った)
run "sleep だけ" FAKE_PANEL_XML="$WORK/existing.xml" -- --sleep-minutes 0
expect_status 0
expect_written_has "<int name=\"sleep_minutes\" value=\"0\" />"
expect_written_lacks "value=\"7\""
expect_written_has "<string name=\"daidai_token\">old-daidai-token-0123456789</string>"
expect_written_has "<string name=\"url\">http://old-panel.example:8790</string>"

# ---- 6. 既存の設定がない: 新しく作る
run "新規作成" -- --daidai-url http://192.168.0.132:8800 --daidai-token-file "$WORK/daidai.token" --url http://192.168.0.132:8790 --token-file "$WORK/panel.token" --sleep-minutes 3
expect_status 0
expect_written_has "<string name=\"daidai_url\">http://192.168.0.132:8800</string>"
expect_written_has "<string name=\"daidai_token\">$DAIDAI_TOKEN</string>"
expect_written_has "<string name=\"url\">http://192.168.0.132:8790</string>"
expect_written_has "<int name=\"sleep_minutes\" value=\"3\" />"
expect_written_has "<map>"
expect_calls_lack "$DAIDAI_TOKEN"
expect_calls_lack "$PANEL_TOKEN"

# ---- 7. 空の <map /> の既存
printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n" > "$WORK/emptymap.xml"
run "空の map" FAKE_PANEL_XML="$WORK/emptymap.xml" -- --daidai-url http://h.example:8800
expect_status 0
expect_written_has "<string name=\"daidai_url\">http://h.example:8800</string>"
expect_count "<map>" 1
expect_count "</map>" 1

# ---- 8. --daidai-url '' で音声機能を止める(ほかは残る)
run "daidai-url を空に" FAKE_PANEL_XML="$WORK/existing.xml" -- --daidai-url ''
expect_status 0
expect_written_has "<string name=\"daidai_url\"></string>"
expect_written_lacks "old-bridge.example"
expect_written_has "<string name=\"daidai_token\">old-daidai-token-0123456789</string>"

# ---- 9. printf の書式文字列に値を埋め込まない(% を含む URL がそのまま入る)
run "書式文字列" -- --daidai-url 'http://h.example:8800/a%s%d%n'
expect_status 0
expect_written_has 'http://h.example:8800/a%s%d%n'

# ---- 10. XML の特殊文字の拒否(端末には何もしない)
for bad in '"' '<' '>' '&' "'"; do
    run "特殊文字 daidai-url $bad" -- --daidai-url "http://h.example:8800/x${bad}y"
    expect_status 2
    expect_no_adb
done
for bad in '"' '<' '>' '&' "'"; do
    run "特殊文字 url $bad" -- --url "http://h.example:8790/x${bad}y" --token-file "$WORK/panel.token"
    expect_status 2
    expect_no_adb
done
printf 'daidai-token-0123456789&abc\n' > "$WORK/bad-amp.token"
run "特殊文字 daidai token" -- --daidai-token-file "$WORK/bad-amp.token"
expect_status 2
expect_no_adb
printf 'panel-token-0123456789"abc\n' > "$WORK/bad-quote.token"
run "特殊文字 panel token" -- --token-file "$WORK/bad-quote.token"
expect_status 2
expect_no_adb

# ---- 11. URL の条件
run "ユーザー情報 @" -- --daidai-url http://user:pass@192.168.0.132:8800
expect_status 2
expect_no_adb
run "@ がパスにある" -- --daidai-url http://192.168.0.132:8800/@x
expect_status 0 # パスの @ は許す(ユーザー情報ではない)
run "クエリ" -- --daidai-url 'http://192.168.0.132:8800/?a=1'
expect_status 2
run "フラグメント" -- --daidai-url 'http://192.168.0.132:8800/#x'
expect_status 2
run "スキームなし" -- --daidai-url 192.168.0.132:8800
expect_status 2
run "ホストなし" -- --daidai-url http://
expect_status 2
run "空白" -- --daidai-url 'http://192.168.0.132 :8800'
expect_status 2
run "ftp" -- --daidai-url ftp://192.168.0.132
expect_status 2

# ポート(Settings.validateDaidaiUrl と同じ規則): 未指定 OK、1〜65535 の数字だけ OK
for good in http://bridge.example http://bridge.example:1 http://bridge.example:80 http://bridge.example:65535 https://bridge.example:443/ \
    'http://[::1]:8800' 'http://[fe80::1]' HTTP://Bridge.Example:8800 http://my_host-1.local:00080; do
    run "ポートとホストの許可 $good" -- --dry-run --daidai-url "$good"
    expect_status 0
done
for bad in http://bridge.example:0 http://bridge.example:65536 http://bridge.example:70000 http://bridge.example:abc 'http://bridge.example:' \
    http://bridge.example:-1 http://bridge.example:+80 http://bridge.example:80a http://bridge.example:123456 http://bridge.example:1:2 \
    'http://[::1]:0' 'http://[::1]:70000' 'http://[::1]:' 'http://[::1]x' 'http://[::1' http://:8800 'http://bridge.example:0000'; do
    run "ポートの拒否 $bad" -- --dry-run --daidai-url "$bad"
    expect_status 2
    expect_no_adb
done
# ホストの文字種
for bad in 'http://bri dge.example' http://bridge!.example http://bridge.example%41 'http://br*dge' 'http://日本語.example' 'http://[zz::1]' 'http://[]'; do
    run "ホストの文字種 $bad" -- --dry-run --daidai-url "$bad"
    expect_status 2
    expect_no_adb
done
run "バックスラッシュ" -- --dry-run --daidai-url 'http://bridge.example:8800\x'
expect_status 2
run "末尾の / は正規化" -- --dry-run --daidai-url http://bridge.example:8800//
expect_status 0
if grep -qF '<string name="daidai_url">http://bridge.example:8800</string>' "$WORK/out"; then ok "末尾の / を落とす"; else ng "正規化されていない: $(cat "$WORK/out")"; fi

# ---- 12. だいだいのトークンの条件
printf 'short-token\n' > "$WORK/short.token"
run "短いトークン" -- --daidai-token-file "$WORK/short.token"
expect_status 2
expect_no_adb
printf 'daidai token with spaces 0123456789\n' > "$WORK/space.token"
run "空白入りトークン" -- --daidai-token-file "$WORK/space.token"
expect_status 2
printf 'だいだいのとーくん0123456789abcdef\n' > "$WORK/jp.token"
run "日本語のトークン" -- --daidai-token-file "$WORK/jp.token"
expect_status 2
run "panel と同じトークン(両方指定)" -- --token-file "$WORK/panel.token" --daidai-token-file "$WORK/panel.token"
expect_status 2
expect_no_adb
run "panel と同じトークン(端末の既存の値)" FAKE_PANEL_XML="$WORK/existing.xml" -- --daidai-token-file "$WORK/panel.token"
expect_status 2
expect_not_written
: > "$WORK/empty.token"
run "空のトークンファイル" -- --daidai-token-file "$WORK/empty.token"
expect_status 2
run "ないトークンファイル" -- --daidai-token-file "$WORK/nothing.token"
expect_status 2
printf '  daidai-token-0123456789abcdef  \n' > "$WORK/pad.token"
run "前後の空白は落とす" -- --daidai-token-file "$WORK/pad.token"
expect_status 0
expect_written_has "<string name=\"daidai_token\">daidai-token-0123456789abcdef</string>"

# ---- 13. --sleep-minutes の条件
run "sleep が数字でない" -- --sleep-minutes 3x
expect_status 2
expect_no_adb

# ---- 14. pm grant が失敗しても導入は続く(警告が出る)
run "pm grant 失敗" FAKE_GRANT_FAIL=1 -- --daidai-url http://h.example:8800
expect_status 0
expect_stderr_has "RECORD_AUDIO"
expect_calls_has "am start -n $PKG/.PanelActivity"
expect_written_has "<string name=\"daidai_url\">http://h.example:8800</string>"

# ---- 15. 既存の設定を読めない(ファイルがない以外)ときは、消さないために書き込まない
run "読み出しに失敗" FAKE_READ_FAIL=1 -- --daidai-url http://h.example:8800
expect_status 1
expect_not_written

# ---- 16. 途中で切れた既存のファイルは上書きしない
printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"url\">x</string>\n" > "$WORK/truncated.xml"
run "切れたファイル" FAKE_PANEL_XML="$WORK/truncated.xml" -- --daidai-url http://h.example:8800
expect_status 1
expect_not_written

# ---- 16b. 書き込みの途中で失敗しても、端末の既存の設定は変わらない(一時ファイルへ書いて、確認してから mv)
expect_device_unchanged() { # 端末の panel.xml が、読んだ元のファイルのまま
    if cmp -s "$WORK/existing.xml" "$WORK/fake/dev/shared_prefs/panel.xml"; then ok "端末の panel.xml は元のまま"; else ng "端末の panel.xml が変わった"; fi
}
expect_device_lacks_tmp() { if [ -e "$WORK/fake/dev/shared_prefs/panel.xml.new" ]; then ng "一時ファイルが残った"; else ok "一時ファイルを残さない"; fi; }
run "書き込みの途中で切れる" FAKE_PANEL_XML="$WORK/existing.xml" FAKE_WRITE_FAIL=partial -- --daidai-url http://h.example:8800
expect_status 1
expect_not_written
expect_device_unchanged
expect_device_lacks_tmp
expect_stderr_has "書き込みに失敗"
run "途中の行が欠けて末尾だけ届く" FAKE_PANEL_XML="$WORK/existing.xml" FAKE_WRITE_FAIL=dropmid -- --daidai-url http://h.example:8800
expect_status 1
expect_not_written
expect_device_unchanged
expect_device_lacks_tmp
run "接続が落ちて端末の後始末も走らない" FAKE_PANEL_XML="$WORK/existing.xml" FAKE_WRITE_FAIL=kill -- --daidai-url http://h.example:8800
expect_status 1
expect_not_written
expect_device_unchanged
run "書き込みが成功すると置き換わる" FAKE_PANEL_XML="$WORK/existing.xml" -- --daidai-url http://h.example:8800
expect_status 0
expect_device_lacks_tmp
if cmp -s "$WORK/existing.xml" "$WORK/fake/dev/shared_prefs/panel.xml"; then ng "置き換わっていない"; else ok "端末の panel.xml が新しい内容に置き換わった"; fi
expect_written_has "<string name=\"daidai_url\">http://h.example:8800</string>"

# ---- 17. --dry-run: 端末に触れず、実行するコマンドを表示する。トークンは伏せる
run "dry-run" FAKE_PANEL_XML="$WORK/existing.xml" -- --dry-run --url http://192.168.0.132:8790 --token-file "$WORK/panel.token" --daidai-url http://192.168.0.132:8800 --daidai-token-file "$WORK/daidai.token" --sleep-minutes 3 --home
expect_status 0
expect_no_adb
expect_not_written
if grep -qF "adb install -r" "$WORK/out" && grep -qF "pm grant $PKG android.permission.RECORD_AUDIO" "$WORK/out" &&
    grep -qF "am start -n $PKG/.PanelActivity" "$WORK/out" && grep -qF "set-home-activity" "$WORK/out"; then
    ok "dry-run が adb コマンドを表示する"
else
    ng "dry-run の表示が足りない: $(cat "$WORK/out")"
fi
if grep -qF -e "$PANEL_TOKEN" -e "$DAIDAI_TOKEN" "$WORK/out" "$WORK/err"; then ng "dry-run がトークンを表示した"; else ok "dry-run はトークンを表示しない"; fi
if grep -qF '***' "$WORK/out"; then ok "dry-run はトークンを伏せ字にする"; else ng "伏せ字がない"; fi
run "dry-run は APK がなくてもよい" APK="$WORK/nothing.apk" -- --dry-run
expect_status 0
expect_no_adb
run "dry-run でも検証はする" -- --dry-run --daidai-url 'http://u@h.example'
expect_status 2

# ---- 18. 知らないオプション・値なし
run "知らないオプション" -- --nope
expect_status 2
run "値なし" -- --daidai-url
expect_status 2
expect_no_adb

echo
echo "合計: $((PASS + FAIL)) 件、成功 $PASS、失敗 $FAIL"
[ "$FAIL" = 0 ]
