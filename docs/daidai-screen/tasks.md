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
| T1 | 土台: `Settings` のデータクラス化＋daidai_* キーと検証(URL・トークン・範囲)、`KioskStatus.parse` の `daidai` ブロック＋fixtures、`SseParser` の `id:` 保持と行・イベント上限 | AC11, AC12(parse), AC20(設定側) | T0 | full | 完了 | 3e4d41f | 39bdf9e, b852d31 | レビュー(Sol): 必須1(SSE 16KB 上限がコメント・CRLF を数えない)・推奨2(SseEvent.toString が本文を含む、URL ポート -1)をすべて採用して修正。確認は diff を自分で読んで実施。テスト 144 件全通過。設計からの変更: URL 検証を厳格化(空白・制御文字・`\`・ホスト文字種・ポート)、不正な通知は通知ごと捨てる、旧 save() は保存済み daidai トークンとの衝突も拒否 |
| T2 | 判定部(純 Kotlin): `PresenceGate` / `AmbientLightGate` / `LuxDarkTracker` / `NoticeTracker`(rev・基準・age_s・low) / `AutoSwitchPolicy`(マイク歯止め含む) | AC9(単体), AC10, AC12, AC18, AC19 | T1 | full | 完了 | b852d31 | 78d80b8 | レビュー(Sol): 必須なし、推奨2(AutoSwitchPolicyTest: 候補の置き換えを確かめるテスト、DEFER→onSettled 経由のマイク歯止めのテスト)は**T5 で対応**。テスト 206 件全通過。設計からの変更は報告 10 点を Sol が妥当と判断(暗闇の数え直し、daidai==null では基準を取らない 等) |
| T3 | ページ: `SwipeDecision` / `PageController` / `PagerLayout`、`PanelView.idleSuspended`・`gestureSwallowed`、`PanelActivity` の載せ替え、空の B。A の回帰を最優先 | AC1, AC2, AC3 | T1 | full | 完了 | 78d80b8 | 239fa40 | レビュー(Sol): 指摘なし。テスト 275 件全通過。設計からの変更: アニメーションは注入時計の時間ベース、端の引っ張りでも奪う、アニメーション中の DOWN は UP まで無視 等。**実機は未確認**(実機のアプリと署名が違い debug APK を入れられない → 利用者判断待ち)。R-3(システムジェスチャ除外)・スワイプの感触は実機要確認 |
| T4 | B の画面: `DaidaiLayout` / `DaidaiView` / `DaidaiUiState` / `AvatarAssets`、未設定の案内、通知カード、状態チップ、マイク使用中表示 | AC6(描画), AC13(表示) | T3 | full | 完了 | 239fa40 | 8b8b5cd, 84c24ce | レビュー(Sol): 必須1(権限付与の adb コマンドが途中で改行されていた)・推奨2(絵文字の途中分断、再描画抑止テストの実体)・参考1(VoiceState は表示用と明記)をすべて採用して修正。テスト 335 件全通過。書記素クラスタは java.text.BreakIterator が JDK 17 で国旗・肌色・ZWJ を分けるため純 Kotlin の Graphemes.kt で実装(最終の全体レビューで再確認する)。アバターはプレースホルダ(Canvas 描画)、本物の絵は利用者の用意待ち(O-6)|
| T5 | 自動切替の結線: `DaidaiController`(KioskSync→NoticeTracker→AutoSwitchPolicy→PageController)、時計中の `wake()`、設定画面の「だいだい連携」節と診断表示、照度購読条件、接続テストの枠 | AC9, AC11, AC12, AC13(接続テスト) | T2, T4 | full | 完了 | 84c24ce | 5379b04, f3a1ddd | レビュー(Sol): 必須2(不正なトークンで例外が漏れ秘密が logcat に出る可能性、接続テストの全体時間ガードがヘッダ待ちに効かない)をどちらも採用して修正。T2 の推奨2件も実施済み。テスト 425 件全通過(自分でも再実行して確認)。設計からの変更: PageListener.onIdle 追加、照度購読が既定で有効(daidai_auto_switch 既定 true)、トークンは可視 ASCII に限定、エンドポイント変更で NoticeTracker 作り直し。実機で要確認: TYPE_LIGHT の初回イベント(O-13)、時計中の切替とバックライト |
| T6 | 音声コア: `VoiceSession` 状態機械(単一スレッド・世代番号・単調性・期限・停止順序)と interface(`AudioSource` / `BridgeApi` / `ReplyPlayer` / `Clock`)＋フェイクによる単体テスト | AC4, AC6, AC8 | T5 | full | 完了 | f3a1ddd | 55b148d, 48d35c0, 954096e | レビュー(Sol): 必須3(録音中のリスナー例外を握りつぶして録音が続く、最初の ack より先に SSE speech が届くと無発話タイマーが再設定され発話中に no_speech、返答音声失敗が画面に出ない/tts_failed がコード化されない)・推奨1(AudioSource.stop の契約)をすべて採用して修正。テスト 561 件全通過。設計からの判断: 開始音後 400ms の録音を捨てる、キュー 64,000 バイト、reply_timeout 上限 60 秒・既定 45 秒、未知のホストコードは汎用表示+診断のみ、DELETE は終了時すべてで送る。**T8 向け注意**: MicAudioSource は stop が失敗しても finally で release、stop 失敗時の release をそのテストで確認 |
| T7 | `BridgeClient`(HTTP + SSE、`Last-Event-ID` 再生、冪等再送、URL は自前で組む・リダイレクト非追従・検証)と WAV 検証、MockWebServer による S1〜S15 の試験 | AC5, AC15, AC20 | T6 | full | 完了 | 954096e | 73bf4ad, 892c6a5 | レビュー(Sol): 必須2(接続登録後〜I/O 開始前の abort 競合、JDK の POST 暗黙再送と VoiceSession の再送の重複)・推奨5(S6/S13 の競合・負荷依存、再生期限が宣言値基準、WAV 120 秒の丸め、abort 後の再利用テスト)をすべて採用して修正。テスト 666 件全通過、競合テストは 3 回連続で全通過。設計からの変更: 再生期限は実データの長さ+5 秒、POST はストリーミング送信+Connection: close、abort 世代で取消済み呼び出しの接続を禁止。S1〜S15 は VoiceBridgeScenarioTest で Panel 側を自動確認(S12 のホスト側置換はホストの契約)。実機で未確認: Android(OkHttp 実装)での disconnect/connect の挙動 |
| T8 | Android 実体と結線: `MicAudioSource`(AudioRecord)、`WavPlayer`(AudioTrack、メモリ上)、開始音、`RECORD_AUDIO` 宣言、`install.sh`(権限付与・既存設定を消さない)、`DaidaiController` への音声結線 | AC4, AC14 | T7 | full | 完了 | 892c6a5 | fd8f8aa, 860bf57, 1fa5678 | レビュー(Sol)3 回: 1 回目 必須4(取消後にマイクが開く競合、WavPlayer 停止と資源作成の競合、自動マイク起動の記録が別セッション状態に依存、install.sh の書き込み途中失敗で既存設定が壊れる)+推奨1(破棄後の遅い作成成功が DELETE されない)→修正。2 回目(再レビュー)必須1(audio.start() 中に micLock を保持しメインの cancel が待たされ ANR の恐れ)+推奨1(DeleteOnCloseBridge の close と配送の間の DELETE 漏れ)→修正。修正上限に達したため 3 回目のレビューは行わず、最終の全体レビュー(PRD 突き合わせ+security-review)で再確認する。テスト 811 件全通過、競合テストは 3 回連続で全通過、install.sh は shellcheck ゼロ・test-install.sh 141 件通過。設計からの変更: 結線を新設 DaidaiVoice に置く、AudioSource.start に isCurrent を追加、取消が startRecording() 中に入ると戻るまで物理的に動く小窓が残る(AudioRecord.stop が同じネイティブロックを取るため。KDoc に明記)。実機で未確認: AudioRecord の warm-up、開始音との被り、スピーカー音量、LineageOS でのマイク可否(R-1/R-2) |
| T9 | 仕上げ: 未読チップ(R17)、文書(`DESIGN.md` / `USAGE.md` / `IDLE_CLOCK.md` / `README.md`) | AC16 | T8 | full | 完了 | 1fa5678 | 4b48b24, c805641 | 個別レビューは全体レビュー(PRD 突き合わせ)に統合し、必須1(新着の検出)を含む指摘を修正(1dcda96, 8a0d4a1)。未読チップは「だいだい N」(10 件以上は「9+」)、ヘッダーの WIFI 表示と時刻の間。ローカル既読は B を 2 秒表示したら。R18(ゲート待ちの再評価・AC17)は設計 O-9 の推奨どおり初版では見送り。R19 は初版範囲外。R20(AC18)は T2 に含めた |

## 実機確認(自動化できない AC)

AC7(1 往復の通し)、AC9 の実機項目、AC1/AC3/AC4/AC8 の実機項目は、Echo Show 5 へ届き、かつブリッジ実物がある場合のみ。到達可否は T0 で確認し、結果を最終報告に書く。

## 進行ログ

- T0: JDK 17(`17.0.20.1`)、adb、Android SDK を導入。ベースラインは 83 テスト全通過・assembleDebug 成功。Echo Show 5 の IP は依頼にも設計書にも記載がなく、`adb devices` / mDNS では見つからない。LAN の総当たりスキャンはしない。

## 全体レビューと最終確認

- 全体レビュー(PRD 突き合わせ、Sol): 必須2・推奨2 をすべて採用して修正(1dcda96, 8a0d4a1)。必須: 未読の既読処理が件数で判定され、窓が入れ替わると新着が 2 秒未満で既読になる(AC16)/install.sh が `--token-file` だけ指定でトークン衝突を保存する(AC20)。推奨: install.sh の URL 検証が Settings より緩い/AC19 の無発話抑止を実際の VoiceSession で通すテストが無い。あわせて時間依存テストの不安定さ(BridgeHealthTest ほか)を緩和。
- 再レビュー(Sol)2 回: 1 回目 必須1(既読期限が既読 ID の再出現で延び続ける)・推奨3 を修正(914828e)、2 回目(最終確認)は必須なし・推奨1(install.sh の URL 規則が Unicode の空白・制御文字で Settings と不一致)。推奨1は修正上限(再レビュー 2 回)に達したため**見送り**。理由: 食い違いは「スクリプトが受理するが Settings が拒否する」向きで、アプリは URL を空扱いにして音声機能を無効のままにするだけ(安全側)。USAGE に trim の差を記載済み。
- 全体セキュリティレビュー(Opus、`security-review`、c805641): `PREFLIGHT OK`、High/Medium/Low とも指摘なし。
- 最終確認(914828e、clean ビルド): `testDebugUnitTest assembleDebug` 856 件・失敗 0・エラー 0・スキップ 0。`scripts/test-install.sh` 332 件成功。`shellcheck` 警告 0。
- 見送り(意図的): R18(ゲート待ちの再評価、AC17)= 設計 O-9 の推奨どおり初版では行わない。R19(連続会話)= 初版範囲外。

## 残件(本タスクの範囲外・未実施)

1. ホスト側ブリッジ(local-model-lab)の実装と、Panel との実物の接続確認(AC7 の 1 往復、設計 §5.2 の契約の結合試験。ホスト側で S1〜S15 を使う)。
2. Miharashi 本体の panel API への `daidai` ブロック(設計 §5.3: rev/notices/ack/unseen/avatar_pose、1 秒以内の SSE、投入口の別資格情報とレート制限)の追加。
3. Echo Show 5 実機での確認: AC1(スワイプの感触・システムジェスチャ除外 R-3)、AC2(A の回帰)、AC4(マイク・開始音・録音インジケータ)、AC8、AC9(遮光した照度ゲート、TYPE_LIGHT の初回イベント O-13)、AC14(権限付与・録音ファイルが残らないこと)、時計中の自動切替とバックライト、AudioRecord の warm-up(開始音後 400ms の破棄で足りるか)、マイクオフスイッチの検出、スピーカー音量。**実機 192.168.0.36 は adb 接続できたが、端末のアプリとこちらの debug ビルドの署名が違い(INSTALL_FAILED_UPDATE_INCOMPATIBLE)入れられなかった。入れるにはアンインストールが必要で端末の設定(URL・トークン)が消えるため、利用者の判断待ちとして実施していない。**
4. アバターの本物の絵(現状は Canvas 描画のプレースホルダ。設計 O-6)と、#97 の `avatar_pose` 語彙の確定。
5. 設計の未決事項のうち、利用者判断が要るもの: O-4(転送方式 T1/T2。Panel は T1 を実装)、O-5(reply_timeout_s の実測)、O-11(平文 http で録音を流すことの許容)。
6. ネットワーク ADB は導入後に無効にする(USAGE.md に記載)。
