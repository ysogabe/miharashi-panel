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
| T3 | 文書: Panel 設計の R19・O-3・§5.2.1・§5.5 と PRD の R19 の更新、USAGE の会話の説明 | (AC14 の記録) | T2 | quick | 実装中 | PLACEHOLDER | | |

## 実機(AC14)

本番アプリの入れ替えには別途ユーザーの承認が要るので、この作業では行わない。残件として PR に書く(続きの開始音が聞こえるか、続きの最初に自分の返答の残響を拾わないか、離脱でマイクが閉じるか)。

## 進行ログ
