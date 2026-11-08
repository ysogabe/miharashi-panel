# だいだい連携画面 タスク

- PRD: [prd.md](prd.md) / 設計: [design.md](design.md)(§13 の実装の順番を元にタスクへ分けた)
- Issue: miharashi-panel #17
- ブランチ: `feat/17-daidai-screen`(設計書のコミット 7dccc0c の上に積む)
- マージ: **しない**(PR を出した時点で利用者の確認待ち。依頼の指定)
- 外部依存: ホスト側ブリッジ(local-model-lab)と Miharashi 本体の `daidai` ブロックは未実装。Panel は設計書 §5.2 / §5.3 の契約に対して実装し、テストはフェイク／MockWebServer で契約どおりの相手を再現する。実物との接続確認は範囲外(残件)。
- ビルド環境: JDK 17(apt)、Android SDK(`~/android-sdk`、platform 35 / build-tools 35.0.0)。`local.properties` は Git 管理外。`ANDROID_HOME=~/android-sdk ./gradlew testDebugUnitTest assembleDebug`

| ID | 内容 | AC | 依存 | 種類 | 状態 | 開始 SHA | コミット | メモ |
|---|---|---|---|---|---|---|---|---|
| T0 | Android ビルド環境の導入とベースライン確認、実機への到達確認 | AC2(前提) | - | 管理(自分で実行) | 完了 | 7dccc0c | (このコミット) | ベースライン: 83 テスト 0 失敗 0 スキップ、assembleDebug 成功(初回 3 分 27 秒)。実機: IP 不明のため未確認 |
| T1 | 土台: `Settings` のデータクラス化＋daidai_* キーと検証(URL・トークン・範囲)、`KioskStatus.parse` の `daidai` ブロック＋fixtures、`SseParser` の `id:` 保持と行・イベント上限 | AC11, AC12(parse), AC20(設定側) | T0 | full | 実装中 | 3e4d41f | | |
| T2 | 判定部(純 Kotlin): `PresenceGate` / `AmbientLightGate` / `LuxDarkTracker` / `NoticeTracker`(rev・基準・age_s・low) / `AutoSwitchPolicy`(マイク歯止め含む) | AC9(単体), AC10, AC12, AC18, AC19 | T1 | full | 未着手 | | | |
| T3 | ページ: `SwipeDecision` / `PageController` / `PagerLayout`、`PanelView.idleSuspended`・`gestureSwallowed`、`PanelActivity` の載せ替え、空の B。A の回帰を最優先 | AC1, AC2, AC3 | T1 | full | 未着手 | | | |
| T4 | B の画面: `DaidaiLayout` / `DaidaiView` / `DaidaiUiState` / `AvatarAssets`、未設定の案内、通知カード、状態チップ、マイク使用中表示 | AC6(描画), AC13(表示) | T3 | full | 未着手 | | | |
| T5 | 自動切替の結線: `DaidaiController`(KioskSync→NoticeTracker→AutoSwitchPolicy→PageController)、時計中の `wake()`、設定画面の「だいだい連携」節と診断表示、照度購読条件、接続テストの枠 | AC9, AC11, AC12, AC13(接続テスト) | T2, T4 | full | 未着手 | | | |
| T6 | 音声コア: `VoiceSession` 状態機械(単一スレッド・世代番号・単調性・期限・停止順序)と interface(`AudioSource` / `BridgeApi` / `ReplyPlayer` / `Clock`)＋フェイクによる単体テスト | AC4, AC6, AC8 | T5 | full | 未着手 | | | |
| T7 | `BridgeClient`(HTTP + SSE、`Last-Event-ID` 再生、冪等再送、URL は自前で組む・リダイレクト非追従・検証)と WAV 検証、MockWebServer による S1〜S15 の試験 | AC5, AC15, AC20 | T6 | full | 未着手 | | | |
| T8 | Android 実体と結線: `MicAudioSource`(AudioRecord)、`WavPlayer`(AudioTrack、メモリ上)、開始音、`RECORD_AUDIO` 宣言、`install.sh`(権限付与・既存設定を消さない)、`DaidaiController` への音声結線 | AC4, AC14 | T7 | full | 未着手 | | | |
| T9 | 仕上げ: 未読チップ(R17)、文書(`DESIGN.md` / `USAGE.md` / `IDLE_CLOCK.md` / `README.md`) | AC16 | T8 | full | 未着手 | | | R18(ゲート待ちの再評価・AC17)は設計 O-9 の推奨どおり初版では見送り。R19 は初版範囲外。R20(AC18)は T2 に含める |

## 実機確認(自動化できない AC)

AC7(1 往復の通し)、AC9 の実機項目、AC1/AC3/AC4/AC8 の実機項目は、Echo Show 5 へ届き、かつブリッジ実物がある場合のみ。到達可否は T0 で確認し、結果を最終報告に書く。

## 進行ログ

- T0: JDK 17(`17.0.20.1`)、adb、Android SDK を導入。ベースラインは 83 テスト全通過・assembleDebug 成功。Echo Show 5 の IP は依頼にも設計書にも記載がなく、`adb devices` / mDNS では見つからない。LAN の総当たりスキャンはしない。
