# 段階 2a-P(画面 B の複数往復の会話) タスク

- Issue: miharashi-panel #21(親: local-model-lab #135、関連: miharashi-panel #17)
- PRD / 設計: local-model-lab の PR #134 のブランチ `design/panel-stage2-multiturn` の `docs/plans/panel-stage2-prd.md` と `docs/plans/panel-stage2-design.md`(main には無い。`git -C /home/hermes/work/local-model-lab show origin/design/panel-stage2-multiturn:docs/plans/<ファイル>` で読む)。以下「設計書」。Panel の既存の設計は `docs/daidai-screen/{prd,design}.md`(以下「Panel 設計」)
- ブランチ: `t3/panel-stage2-multi-turn`
- マージ: **しない**(PR まで。`Refs #21`)
- 決定済みの値: S2-U2(続きの待ち 6 秒・最大 5 往復・受付の期限 5 分)、S2-U3(割り込みなし B0。タップは 2a-T = #22 で対象外)、S2-U9(在席ゲートは往復ごと。照度が取れないときはスワイプの会話だけ続ける)、S2-U10(続きの開始音は短い別の音)、S2-U14(上限で終わった自動起動の会話は次の見送りに数える)
- 範囲外: 文脈の保持(2a-H、local-model-lab #136)、挨拶(段 G、S2-U18 は未決)、声の固定(PR #142)、DaidaiBrain(2b)、実機の入れ替え(承認が要る)
- ユーザーの決定(2026-11-17): (1) エラーのあとのタップでの再試行の会話はスワイプと同じ扱い(手で始めた会話の規則。照度が取れなくても続け、上限で終わっても次の自動起動の見送りに数えない。ホストへの `reason` は前の値の `notification` のまま)。(2) 自動起動の会話がホストの `conversation.continue = false` で終わったときも S2-U14 の見送りに数える。(3) 残響の待ち 300 ms はコードの定数(設定画面には出さない)
- ベースライン: `d206a3f` で `testDebugUnitTest` 888 件・失敗 0・スキップ 0

| ID | 内容 | AC | 依存 | 種類 | 状態 | 開始 SHA | コミット | メモ |
|---|---|---|---|---|---|---|---|---|
| T1 | 契約と 1 往復の層: `SessionRequest`/`SessionInfo` の `conversation`、`BridgeApi.endConversation`(`POST /v1/voice/conversations/end`)、`DeleteOnCloseBridge` の後始末と重複の抑止、`VoiceSession` の往復の要求(`TurnRequest`)・往復の結果(8 種)・呼び出し側のスレッドでの開始の許可・続きの `no_speech` を静かに終える・古い 201 のハンドルの後始末、`VoiceRuntime.endConversation` | AC1, AC4, AC3(一部) | - | full | 完了 | 64e6bc4 | b8160b5, 220f9f8, 714b6f8, 43f0b77 | 実装 Sonnet、レビュー Sol 3 回(必須: r1 作成をワーカーに積んだあとの取消で作成が出る → 714b6f8、r2 通信層が世代を取り直す窓 → 43f0b77、推奨: 対照試験の空振り → 43f0b77)。**見送り 1 件**(r3 必須「最後の照合と送信を共通の同期で順序付けよ」): 本文の直前の照合(`AtomicInteger` の読み取り)が送信の確定点で、それより後の取消は「送ると決めたあとの取消」。取消側(メイン)が送信の完了を待つ形にしない限り窓は消えず、それは既存の設計(取消は通信を待たない)と ANR の恐れに反する。その窓は S7(遅れた 201 の DELETE と会話の終わり)で回収する。参考: `withDeadline` と競合すると取消の分類が `IOException` になりうる(世代が進んでいれば捨てられる)。テスト 959 件・失敗 0。既存の不安定な試験 `BridgeClientTest.slowAudioDownloadIsCutByTheOverallDeadline` は main(d206a3f)でも 6 回中 5 回落ちる(T1 の後退ではない) |
| T2 | 会話の制御: `DaidaiVoice` の会話(世代・往復番号・上限・受付の期限・在席ゲート・残響の待ち・続けるかの判定・終わりの通知・再試行は新しい会話)、`AutoSwitchPolicy` との関係(最初の往復だけ・S2-U14)、無操作タイマー、続きの開始音、`PanelActivity` の結線、`MockWebServer` の複数往復のシナリオと §5.4 の競合の試験 | AC2, AC3, AC5, AC6 | T1 | full | 完了 | b20e013 | 044715c, 93b8e3c, 8928bc3, 96001b9, f239a0e, aea236a, a3fccc8 | 実装 Sonnet、レビュー Sol 2 回。r1 必須 2(残響の待ちのあとに在席を確かめ直さない、続きの往復がドラッグ中にマイクを開く)・推奨 1(続きの開始音 300 ms が 400 ms の破棄に収まる保証がない → DTMF_1 100 ms)→ aea236a。r2 必須なし、推奨 2(試験の補強)→ a3fccc8(試験だけ。親が差分を確認)。自分で決めた点: 上限・期限・continue:false を在席より先に判定(同時なら S2-U14 に数える。Sol は妥当と判断)、保留中は受付の期限で終える予約を置く。テスト 1038 件・失敗 0。残件: 続きの開始音の可聴性・聞き分け・400 ms への収まりは実機で未確認、AC3 の新ホストの panelsim はホスト側(2a-H) |
| T3 | 文書: Panel 設計の R19・O-3・§5.2.1・§5.5 と PRD の R19 の更新、USAGE の会話の説明 | (AC14 の記録) | T2 | quick | 完了 | a55718e | 21d39a4 | 実装 Sonnet(USAGE は yomiyasu で推敲)。個別レビューは全体レビュー(PRD 突き合わせ)に統合(#17 の T9 と同じ)。PRD R19・AC18、Panel 設計 §1・§5.2.1・§5.5.1(新設)・§5.8・§7・O-3、USAGE 8.7・8.9、DESIGN §9 |

## 実機(AC14)

本番アプリの入れ替えには別途ユーザーの承認が要るので、この作業では行わない。残件として PR に書く(続きの開始音が聞こえるか、続きの最初に自分の返答の残響を拾わないか、離脱でマイクが閉じるか)。

## 進行ログ

## 全体レビューと最終確認

- 最終確認(331bf8a、clean ビルド): `testDebugUnitTest` 1038 件のうち、既存の不安定な `BridgeClientTest.slowAudioDownloadIsCutByTheOverallDeadline` だけが 1 回落ちた(そのクラスを 3 回流して 3 回通過。変更前の main d206a3f でも単独で 6 回中 5 回落ちる)。ほかは失敗 0。`assembleDebug` 成功。`scripts/test-install.sh` 332 件成功(scripts は変更なし)。
- PRD 突き合わせ(Sol): 必須なし。AC1〜AC6 は満たす(AC3 は Panel の範囲。新ホストの panelsim はホスト側)、AC14 は実機で未確認。推奨 2(USAGE の「必ず音が鳴る」と「会話が終わると捨てる」が実装より強い保証)→ 9740946 で文言を直した。T1 で見送った指摘を再掲する根拠は無いとの判断。
- セキュリティ(Opus、`security-review`、331bf8a): `PREFLIGHT OK`、High/Medium とも指摘なし。
- 全件の再実行(aef4708): 1 回目は同じ不安定な 1 件だけ落ち、2 回目は 1038 件すべて通過(失敗 0)。
- T1 の見送り 1 件(作成の取消の窓)は、ユーザーの確認待ち。AC3 の「送る前の取消で作成 0 回」は、「送る前」を本文の直前の照合より前と読む解釈での判定。
- USAGE の手直し(9740946)の 2 文は、yomiyasu で推敲した(lint 候補 0、diff の意味が変わる恐れ 0)。

## 残件(この PR の範囲外・未実施)

1. 実機(AC14。本番アプリの入れ替えはユーザーの承認が要る): スワイプ → 3 往復 → 続きの無発話で ERROR なしに待機、通知の自動起動から 2 往復、続きの開始音(DTMF「1」100 ms)が聞こえて最初の音と聞き分けられる、開始音とその尾が 400 ms の破棄に収まり返答の残響を拾わない、離脱 5 経路でマイクが閉じる(端末の表示か `dumpsys`)。
2. ホスト側(2a-H、local-model-lab #136): 作成の本文の `conversation` の検査とハンドルの発行、201 の `conversation`(`handle`・`context`・`continue`)、`POST /v1/voice/conversations/end`(知らないハンドルでも 204、形が違えば 400)、会話メモリ、AC7、AC3 のホスト側(新ホストの panelsim と S1〜S15)。Panel は今のホストでも文脈なしの複数往復として動く(未知の項目は無視され、終わりの通知は 404 を成功として扱う)。
3. 既存の不安定な試験 `BridgeClientTest.slowAudioDownloadIsCutByTheOverallDeadline`(変更前から。別に直す)。
4. 本物の `AndroidVoiceRuntime` を通す試験が無い: `endConversation`(スレッドプールで送る)と、close での回収(閉じる前に成功した作成のハンドルの終わりの通知)。背面の経路は偽の runtime と `DeleteOnCloseBridge` の単体試験でだけ確かめている。実機の確認(1)で、背面に回したあとホストに終わりの通知が届くことも見る。
5. タップでの割り込み(2a-T、miharashi-panel #22。`TurnOutcome.TAPPED` は値だけ)、直前の往復の表示(PRD R14、あると良い)、挨拶(段 G)。

---

# 段階 2b-2(脳の区分の表示と `brain_notice` の申告) タスク

- Issue: ysogabe/local-model-lab の Issue #139(段階 2b)の Panel 側。ホスト側は local-model-lab の PR #149(実装済み・未配備)
- 設計: 同じ設計書の §6.1(201 の `brain`)・§6.6(`brain_notice`)・§8(送る前の案内)、Panel 設計 §5.2.1「脳の区分の表示」・§5.5.1・§5.6、USAGE「外へ出る表示」
- ブランチ: `feat/panel-stage2b2-brain-notice`
- マージ: **しない**(push まで。PR は作らない)
- 目的: 作成の本文の `client.capabilities` に `"brain_notice"` を入れる(入れないと、2b-3 で脳をだいだいに替えたとき、スワイプの会話がすべて `daidai_unavailable` になる)。同時に、マイクが開いている間に「いまの脳がどれで、外へ出るか」を画面 B に出す(要求 R15)

| ID | 内容 | 状態 | メモ |
|---|---|---|---|
| 2b-2 | 申告(`BridgeClient.CLIENT_CAPABILITIES`)、201 の `brain` の読み取り(`BrainInfo`。無い・辞書でない = 旧ホストは null、`egress` が不明なら UNKNOWN、`name` は `daidai` だけ既知)、`VoiceSession` が 201 の時点で往復ごとに脳を持ち `VoiceSnapshot.brain` → `DaidaiUiState.brain` へ、表記(`DaidaiText.brainNotice`、返答の領域の最下段に `DaidaiView` が描く)、`daidai_unavailable` の通しの試験、文書 | 完了(push 済み) | `daidai_unavailable` は既存の ERROR の経路のままで足りた(コードの変更なし。試験で固定)。残件: 実機での見え方と、ホストの実物との結合(2b-3 の配備のあと) |
