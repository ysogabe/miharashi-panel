#!/bin/sh
# Echo Show 5(LineageOS)へ入れて起動する。USB か `adb connect` でつないでおく。
#
#   ./scripts/install.sh                         # debug APK を入れて起動(マイクの権限も許可する)
#   ./scripts/install.sh --url http://192.168.0.132:8790 --token-file panel.token
#                                                # 操作盤(panel)の設定も書き込む(debug ビルドだけ。run-as を使う)
#   ... --sleep-minutes 3                        # 触らずに消灯するまでの分(0 = 消灯しない)
#   ... --daidai-url http://192.168.0.132:8800 --daidai-token-file daidai.token
#                                                # だいだい連携(ブリッジ)の接続先とトークン。panel のトークンとは別の値にする
#   ... --daidai-url ''                          # 音声機能を止める(daidai_url を空にする。マイクは開かなくなる)
#   ./scripts/install.sh --home                  # ランチャー(HOME)にも指定する
#   ./scripts/install.sh --dry-run ...           # 実行する adb コマンドを表示するだけ(端末には触れない。トークンは伏せ字)
#
# 設定の書き込みは**指定した項目だけ**差し替える。端末の shared_prefs/panel.xml を先に読み、指定していない既存の設定
# (url, token, sleep_minutes, daidai_* など)はそのまま残す。ファイルがなければ新しく作る。
# トークンはコマンド行に出さず、標準入力で渡す。
#
# マイクを止める方法(R16): ① 本体のマイクオフスイッチ ② `--daidai-url ''` で音声機能を止める
# ③ adb shell pm revoke io.github.ysogabe.miharashi.panel android.permission.RECORD_AUDIO
set -eu

PKG=io.github.ysogabe.miharashi.panel
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
PREFS=shared_prefs/panel.xml
URL=
TOKEN_FILE=
SLEEP=
DURL=
DURL_SET=0
DTOKEN_FILE=
HOME_APP=0
DRY=0

usage() {
    echo "usage: $0 [--url URL] [--token-file FILE] [--sleep-minutes N] [--daidai-url URL] [--daidai-token-file FILE] [--home] [--dry-run]" >&2
    exit 2
}

need_value() {
    [ "$1" -ge 2 ] || { echo "$2 には値が要る" >&2; exit 2; }
}

while [ $# -gt 0 ]; do
    case "$1" in
        --url) need_value $# "$1"; URL=$2; shift 2 ;;
        --token-file) need_value $# "$1"; TOKEN_FILE=$2; shift 2 ;;
        --sleep-minutes) need_value $# "$1"; SLEEP=$2; shift 2 ;;
        --daidai-url) need_value $# "$1"; DURL=$2; DURL_SET=1; shift 2 ;;
        --daidai-token-file) need_value $# "$1"; DTOKEN_FILE=$2; shift 2 ;;
        --home) HOME_APP=1; shift ;;
        --dry-run) DRY=1; shift ;;
        -h|--help) usage ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

cd "$(dirname "$0")/.."
if [ "$DRY" = 0 ]; then
    [ -f "$APK" ] || { echo "$APK がない。先に ./gradlew assembleDebug" >&2; exit 1; }
fi

# ---- 入力の検証(端末に触れる前に、すべて済ませる)

# ファイルの先頭行ではなく全体から改行を除き、前後の空白を落とす
read_token() {
    [ -f "$1" ] || { echo "$1 がない" >&2; exit 2; }
    tr -d '\r\n' < "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

reject_xml_chars() { # $1=名前 $2=値
    case "$2" in
        *[\"\<\>\&\']*) echo "$1 に XML の特殊文字(\" < > & ')がある" >&2; exit 2 ;;
    esac
}

TOKEN=
if [ -n "$TOKEN_FILE" ]; then
    TOKEN=$(read_token "$TOKEN_FILE")
    [ -n "$TOKEN" ] || { echo "--token-file が空" >&2; exit 2; }
    reject_xml_chars "トークン" "$TOKEN"
fi
if [ -n "$URL" ]; then
    reject_xml_chars "URL" "$URL"
fi

if [ -n "$SLEEP" ]; then
    case "$SLEEP" in
        *[!0-9]*) echo "--sleep-minutes は 0 以上の整数" >&2; exit 2 ;;
    esac
fi

DTOKEN=
if [ -n "$DTOKEN_FILE" ]; then
    DTOKEN=$(read_token "$DTOKEN_FILE")
    [ -n "$DTOKEN" ] || { echo "--daidai-token-file が空" >&2; exit 2; }
    reject_xml_chars "だいだいのトークン" "$DTOKEN"
    # 可視 ASCII(0x21〜0x7E)だけ。Authorization ヘッダにそのまま載る
    if printf '%s' "$DTOKEN" | LC_ALL=C grep -q '[^!-~]'; then
        echo "だいだいのトークンに使えるのは英数字と記号だけ(空白・日本語は不可)" >&2; exit 2
    fi
    [ "${#DTOKEN}" -ge 16 ] || { echo "だいだいのトークンは 16 文字以上" >&2; exit 2; }
    if [ -n "$TOKEN" ] && [ "$DTOKEN" = "$TOKEN" ]; then
        echo "だいだいのトークンは panel のトークンと別の値にする" >&2; exit 2
    fi
fi

if [ "$DURL_SET" = 1 ] && [ -n "$DURL" ]; then
    reject_xml_chars "だいだいの URL" "$DURL"
    while :; do
        case "$DURL" in */) DURL=${DURL%/} ;; *) break ;; esac
    done
    case "$DURL" in
        http://?*|https://?*) ;;
        *) echo "--daidai-url は http://192.168.0.132:8800 の形で入れる" >&2; exit 2 ;;
    esac
    if printf '%s' "$DURL" | LC_ALL=C grep -q '[[:space:][:cntrl:]\\]'; then
        echo "--daidai-url に空白・制御文字・バックスラッシュは使えない" >&2; exit 2
    fi
    case "$DURL" in
        *\?*|*\#*) echo "--daidai-url にクエリ・フラグメントは付けられない" >&2; exit 2 ;;
    esac
    authority=${DURL#*://}
    authority=${authority%%/*}
    case "$authority" in
        *@*) echo "--daidai-url にユーザー情報(@)は付けられない" >&2; exit 2 ;;
        '') echo "--daidai-url にホストが無い" >&2; exit 2 ;;
    esac
fi

# ---- adb の呼び出し(--dry-run では表示するだけ)

adb_run() {
    if [ "$DRY" = 1 ]; then
        printf 'adb'
        for a in "$@"; do printf ' %s' "$a"; done
        printf '\n'
    else
        adb "$@"
    fi
}

adb_run install -r "$APK"
# Android 10 以降、BOOT_COMPLETED から前面に出すにはこの許可が要る
adb_run shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
# だいだい連携画面のマイク。キオスクでは権限ダイアログが出せないので adb で許可する。失敗しても導入は続ける
adb_run shell pm grant "$PKG" android.permission.RECORD_AUDIO ||
    echo "警告: RECORD_AUDIO を許可できなかった。音声機能は『マイクの権限がありません』になる(adb shell pm grant $PKG android.permission.RECORD_AUDIO)" >&2

if [ -n "$URL" ] || [ -n "$TOKEN" ] || [ -n "$SLEEP" ] || [ "$DURL_SET" = 1 ] || [ -n "$DTOKEN" ]; then
    adb_run shell am force-stop "$PKG"

    existing=
    if [ "$DRY" = 1 ]; then
        adb_run shell "run-as $PKG cat $PREFS"
        echo "  (既存の $PREFS を読み、下の項目だけ差し替えて書き戻す。トークンは ***)"
    else
        if out=$(adb shell "run-as $PKG cat $PREFS" 2>&1); then rc=0; else rc=$?; fi
        existing=$(printf '%s\n' "$out" | tr -d '\r')
        if [ "$rc" != 0 ] && ! printf '%s\n' "$existing" | grep -q 'No such file'; then
            echo "端末の $PREFS を読めなかった(端末とつながっているか、debug ビルドか確認)。既存の設定を消さないよう、書き込まずに終わる" >&2
            exit 1
        fi
        if printf '%s\n' "$existing" | grep -q '<map'; then
            if ! printf '%s\n' "$existing" | grep -q -e '</map>' -e '<map */>'; then
                echo "端末の $PREFS が途中で切れているように見える。上書きしない" >&2; exit 1
            fi
        elif [ -z "$existing" ] || printf '%s\n' "$existing" | grep -q 'No such file'; then
            existing= # まだ無い: 新しく作る
        else
            echo "端末の $PREFS を読めなかった(端末とつながっているか、debug ビルドか確認)。既存の設定を消さないよう、書き込まずに終わる" >&2
            exit 1
        fi
    fi

    # panel のトークンと同じ値は、だいだいのトークンにできない(今回指定したものか、端末にある既存のもの)
    if [ -n "$DTOKEN" ] && [ -z "$TOKEN" ] && [ -n "$existing" ]; then
        current=$(printf '%s\n' "$existing" | sed -n 's/.*<string name="token">\(.*\)<\/string>.*/\1/p' | head -n 1)
        if [ -n "$current" ] && [ "$current" = "$DTOKEN" ]; then
            echo "だいだいのトークンは panel のトークン(端末の設定)と別の値にする" >&2; exit 2
        fi
    fi

    # panel のトークンだけ差し替えて、端末の既存の daidai_token と同じ値になる場合: アプリは安全側で未設定扱いにする(音声が止まる)
    if [ -n "$TOKEN" ] && [ -z "$DTOKEN" ] && [ -n "$existing" ] &&
        printf '%s\n' "$existing" | grep -qF "<string name=\"daidai_token\">$TOKEN</string>"; then
        echo "警告: 新しい panel のトークンが端末の daidai_token と同じ。アプリはだいだい連携を未設定として扱う(--daidai-token-file で別の値にする)" >&2
    fi

    # 既存のうち、今回差し替える項目の行と、枠(<?xml ...?> <map> </map>)を落とす
    body=$(printf '%s\n' "$existing" | sed \
        -e '/^[[:space:]]*<?xml/d' \
        -e '/^[[:space:]]*<map>[[:space:]]*$/d' \
        -e '/^[[:space:]]*<map[[:space:]]*\/>[[:space:]]*$/d' \
        -e '/^[[:space:]]*<\/map>[[:space:]]*$/d' \
        -e '/^[[:space:]]*$/d' |
        {
            drop=
            [ -z "$URL" ] || drop="$drop url"
            [ -z "$TOKEN" ] || drop="$drop token"
            [ -z "$SLEEP" ] || drop="$drop sleep_minutes"
            [ "$DURL_SET" = 0 ] || drop="$drop daidai_url"
            [ -z "$DTOKEN" ] || drop="$drop daidai_token"
            # 差し替える項目の行を落とす(キーは引用符つきで照合するので、url が daidai_url に当たらない)
            awk -v keys="$drop" 'BEGIN { n = split(keys, a, " "); for (i = 1; i <= n; i++) pat[i] = "name=\"" a[i] "\"" }
                { skip = 0; for (i = 1; i <= n; i++) if (index($0, pat[i])) skip = 1; if (!skip) print }'
        })

    emit_xml() { # $1 = mask なら値を伏せる
        printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>'
        printf '%s\n' '<map>'
        if [ -n "$body" ]; then printf '%s\n' "$body"; fi
        if [ -n "$URL" ]; then printf '    <string name="url">%s</string>\n' "$URL"; fi
        if [ -n "$TOKEN" ]; then
            if [ "${1:-}" = mask ]; then printf '    <string name="token">%s</string>\n' '***'
            else printf '    <string name="token">%s</string>\n' "$TOKEN"; fi
        fi
        if [ -n "$SLEEP" ]; then printf '    <int name="sleep_minutes" value="%s" />\n' "$SLEEP"; fi
        if [ "$DURL_SET" = 1 ]; then printf '    <string name="daidai_url">%s</string>\n' "$DURL"; fi
        if [ -n "$DTOKEN" ]; then
            if [ "${1:-}" = mask ]; then printf '    <string name="daidai_token">%s</string>\n' '***'
            else printf '    <string name="daidai_token">%s</string>\n' "$DTOKEN"; fi
        fi
        printf '%s\n' '</map>'
    }

    # 書き込みは同じディレクトリの一時ファイル(panel.xml.new)へ。行数と末尾の </map> を端末側で確かめてから mv で置き換える
    # (cat > panel.xml は先に既存のファイルを空にするので、ADB の切断・書き込みの失敗で設定が壊れる)。
    # 失敗したら一時ファイルを消して非ゼロで終わり、既存の panel.xml には触れない。
    XML_LINES=$(emit_xml mask | wc -l | tr -d ' ')
    WRITE_CMD="run-as $PKG sh -c 'mkdir -p shared_prefs && cat > $PREFS.new && [ \"\$(wc -l < $PREFS.new)\" -eq $XML_LINES ] && tail -n 1 $PREFS.new | grep -q \"</map>\" && mv $PREFS.new $PREFS || { rm -f $PREFS.new; exit 1; }'"

    if [ "$DRY" = 1 ]; then
        adb_run shell "$WRITE_CMD"
        echo "  (標準入力で渡す内容。既存の項目は端末で読んだものを残す。一時ファイルへ書き、行数と </map> を確かめてから置き換える:)"
        emit_xml mask | sed 's/^/    | /'
    else
        # トークンはコマンド行に出さず、標準入力で渡す
        if emit_xml | adb shell "$WRITE_CMD"; then
            echo "設定を書き込んだ(指定した項目だけ。ほかの既存の設定は残した)"
        else
            echo "端末の $PREFS への書き込みに失敗した(接続が切れたか、端末側の確認が通らなかった)。既存の設定は変えていない。もう一度実行する" >&2
            exit 1
        fi
    fi
fi

if [ "$HOME_APP" = 1 ]; then
    adb_run shell cmd package set-home-activity "$PKG/.PanelActivity"
fi

adb_run shell am start -n "$PKG/.PanelActivity"
