#!/bin/sh
# Echo Show 5(LineageOS)へ入れて起動する。USB か `adb connect` でつないでおく。
#
#   ./scripts/install.sh                         # debug APK を入れて起動
#   ./scripts/install.sh --url http://192.168.0.132:8790 --token-file panel.token
#                                                # 設定も書き込む(debug ビルドだけ。run-as を使う)
#   ... --sleep-minutes 3                        # 触らずに消灯するまでの分(0 = 消灯しない。設定と一緒に書く)
#   ./scripts/install.sh --home                  # ランチャー(HOME)にも指定する
set -eu

PKG=io.github.ysogabe.miharashi.panel
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
URL=
TOKEN_FILE=
SLEEP=
HOME_APP=0

while [ $# -gt 0 ]; do
    case "$1" in
        --url) URL=$2; shift 2 ;;
        --token-file) TOKEN_FILE=$2; shift 2 ;;
        --sleep-minutes) SLEEP=$2; shift 2 ;;
        --home) HOME_APP=1; shift ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

cd "$(dirname "$0")/.."
[ -f "$APK" ] || { echo "$APK がない。先に ./gradlew assembleDebug" >&2; exit 1; }

adb install -r "$APK"
# Android 10 以降、BOOT_COMPLETED から前面に出すにはこの許可が要る
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow

if [ -n "$URL" ] || [ -n "$TOKEN_FILE" ]; then
    [ -n "$URL" ] && [ -n "$TOKEN_FILE" ] || { echo "--url と --token-file は両方指定する" >&2; exit 2; }
    token=$(tr -d '\r\n' < "$TOKEN_FILE")
    case "$token$URL" in
        *[\"\<\>\&\']*) echo "URL かトークンに XML の特殊文字がある" >&2; exit 2 ;;
    esac
    sleep_xml=
    if [ -n "$SLEEP" ]; then
        case "$SLEEP" in
            ''|*[!0-9]*) echo "--sleep-minutes は 0 以上の整数" >&2; exit 2 ;;
        esac
        sleep_xml="    <int name=\"sleep_minutes\" value=\"$SLEEP\" />\n"
    fi
    adb shell am force-stop "$PKG"
    # トークンはコマンド行に出さず、標準入力で渡す
    printf "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n<map>\n    <string name=\"url\">%s</string>\n    <string name=\"token\">%s</string>\n$sleep_xml</map>\n" "$URL" "$token" |
        adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/panel.xml'"
    echo "設定を書き込んだ: $URL"
fi

if [ "$HOME_APP" = 1 ]; then
    adb shell cmd package set-home-activity "$PKG/.PanelActivity"
fi

adb shell am start -n "$PKG/.PanelActivity"
