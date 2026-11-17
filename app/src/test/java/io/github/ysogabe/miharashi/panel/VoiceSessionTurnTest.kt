package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 段階 2a-P(T1)の 1 往復の契約: 往復の結果([TurnOutcome])、開始の許可を呼び出し側で控える順序、会話のハンドルの受け渡しと古い 201 の後始末、
 * 続きの往復の無発話、作成の本文の `no_speech_s`。設計書(段階 2)§5.2〜§5.4, §6.1。
 */
class VoiceSessionTurnTest {
    private val handle = "Yp0vAbCdEfGhIjKlMnOp-_" // 22 文字

    private fun request(
        follow: Boolean = false,
        conversation: ConversationParam? = null,
        noSpeechS: Long = VoiceLimits.NO_SPEECH_DEFAULT_S,
        tag: TurnTag = TurnTag.NONE,
        auto: Boolean = false,
    ) = TurnRequest(StartReason.SWIPE, null, auto, conversation, noSpeechS, follow, tag)

    private fun Rig.startReply(auto: Boolean = false) {
        startAndListen(auto = auto)
        talk(440)
    }

    private fun Rig.outcome(): TurnOutcome = listener.outcomes.single()

    // ---------------------------------------------------------------- AC1: 往復の結果(8 種のうち TAPPED 以外の 7 種)

    @Test
    fun outcome_playedWhenPlaybackCompletes() {
        val r = Rig()
        r.startReply()
        r.reply()
        r.player.finish(true)
        r.sim.settle()
        assertEquals(TurnOutcome.PLAYED, r.outcome())
        assertEquals(listOf(false to "done"), r.listener.ended)
    }

    @Test
    fun outcome_textOnlyWhenAudioIsNull_withAndWithoutAudioError() {
        val a = Rig()
        a.startReply()
        a.reply(audio = null)
        assertEquals(TurnOutcome.TEXT_ONLY, a.outcome())
        assertEquals(listOf(false to "done"), a.listener.ended)

        val b = Rig()
        b.startReply()
        b.reply(audio = null, audioError = "tts_failed")
        assertEquals(TurnOutcome.TEXT_ONLY, b.outcome())
        assertEquals(listOf(false to "reply_audio_failed"), b.listener.ended) // 理由の文字列は今のまま
    }

    @Test
    fun outcome_playbackFailedOnEveryAudioFailurePath() {
        // 宣言の拒否(長すぎる)
        val rejected = Rig()
        rejected.startReply()
        rejected.reply(audio = ReplyAudioInfo("wav", 24_000, 2000, 120_001))
        assertEquals(TurnOutcome.PLAYBACK_FAILED, rejected.outcome())

        // 取得の失敗
        val fetchFail = Rig()
        fetchFail.bridge.fetchReply = { failWith(IOException("down")) }
        fetchFail.startReply()
        fetchFail.reply()
        assertEquals(TurnOutcome.PLAYBACK_FAILED, fetchFail.outcome())

        // 取得の期限
        val fetchTimeout = Rig()
        fetchTimeout.bridge.fetchReply = { hang() }
        fetchTimeout.startReply()
        fetchTimeout.reply()
        fetchTimeout.advance(VoiceLimits.REPLY_AUDIO_FETCH_TIMEOUT_MS)
        assertEquals(TurnOutcome.PLAYBACK_FAILED, fetchTimeout.outcome())

        // 大きさ(空)
        val size = Rig()
        size.bridge.fetchReply = { ok(ByteArray(0)) }
        size.startReply()
        size.reply()
        assertEquals(TurnOutcome.PLAYBACK_FAILED, size.outcome())

        // 再生の失敗
        val playFail = Rig()
        playFail.startReply()
        playFail.reply()
        playFail.player.finish(false)
        playFail.sim.settle()
        assertEquals(TurnOutcome.PLAYBACK_FAILED, playFail.outcome())

        // play の例外
        val playThrows = Rig()
        playThrows.player.throwOnPlay = IllegalStateException("no audio")
        playThrows.startReply()
        playThrows.reply()
        assertEquals(TurnOutcome.PLAYBACK_FAILED, playThrows.outcome())
    }

    @Test
    fun outcome_playbackTimeoutWhenThePlaybackDeadlinePasses() {
        val r = Rig()
        r.startReply()
        r.reply(audio = ReplyAudioInfo("wav", 24_000, 2000, 4_200))
        r.advance(9_200) // duration + 5 秒
        assertEquals(VoicePhase.DONE, r.phase)
        assertEquals(TurnOutcome.PLAYBACK_TIMEOUT, r.outcome())
        assertEquals(listOf(false to "done"), r.listener.ended)
    }

    @Test
    fun outcome_sessionTimeoutWhenTheTotalLimitPassesWhileReplying() {
        val r = Rig(sessionMaxMs = 10_000)
        r.startAndListen()
        r.reply("長い返答", audio = ReplyAudioInfo("wav", 24_000, 2000, 120_000))
        r.advance(10_000)
        assertEquals(VoicePhase.DONE, r.phase)
        assertEquals(TurnOutcome.SESSION_TIMEOUT, r.outcome())
    }

    @Test
    fun outcome_errorOnCreateFailureNoSpeechUnknownHostCodeAndTotalTimeoutWhileListening() {
        val create = Rig()
        create.bridge.createReply = { failWith(bridgeError(BridgeFailure.SERVER_ERROR, 500)) }
        create.start()
        assertEquals(TurnOutcome.ERROR, create.outcome())

        val noSpeech = Rig()
        noSpeech.startReply()
        noSpeech.hostError("no_speech")
        assertEquals(TurnOutcome.ERROR, noSpeech.outcome())
        assertEquals(listOf(false to "no_speech"), noSpeech.listener.ended)

        val unknown = Rig()
        unknown.startReply()
        unknown.hostError("weird_code")
        assertEquals(TurnOutcome.ERROR, unknown.outcome())
        assertEquals(listOf(false to "host_weird_code"), unknown.listener.ended)

        val total = Rig(sessionMaxMs = 10_000)
        total.ackState = AckState.SPEECH
        total.startAndListen()
        total.talk(10_000)
        assertEquals(TurnOutcome.ERROR, total.outcome())
    }

    @Test
    fun outcome_cancelledOnCancelInEveryStageAndBeforeOpeningTheMic() {
        val listening = Rig()
        listening.startAndListen()
        listening.cancel()
        assertEquals(TurnOutcome.CANCELLED, listening.outcome())
        assertEquals(listOf(false to "cancelled"), listening.listener.ended)

        val replying = Rig()
        replying.startReply()
        replying.reply()
        replying.cancel()
        assertEquals(TurnOutcome.CANCELLED, replying.outcome())

        // マイクを開く前(作成の結果が積まれたまま)の取消
        val beforeMic = Rig()
        beforeMic.session.start(request())
        beforeMic.sim.step() // doStart
        beforeMic.sim.step() // 作成の完了
        beforeMic.session.cancel()
        beforeMic.sim.settle()
        assertEquals(0, beforeMic.audio.startCount)
        assertEquals(TurnOutcome.CANCELLED, beforeMic.outcome())
    }

    @Test
    fun canContinueIsTrueOnlyForPlayedAndTapped() { // 8 種すべて
        val expected = mapOf(
            TurnOutcome.PLAYED to true, TurnOutcome.TAPPED to true, TurnOutcome.TEXT_ONLY to false, TurnOutcome.PLAYBACK_FAILED to false,
            TurnOutcome.PLAYBACK_TIMEOUT to false, TurnOutcome.SESSION_TIMEOUT to false, TurnOutcome.ERROR to false, TurnOutcome.CANCELLED to false,
        )
        assertEquals(TurnOutcome.values().toSet(), expected.keys)
        for ((o, c) in expected) assertEquals("$o", c, o.canContinue)
    }

    @Test
    fun theSessionNeverProducesTappedInThisStage() {
        val r = Rig()
        r.startReply()
        r.reply()
        r.player.finish(true)
        r.sim.settle()
        assertTrue(r.listener.outcomes.none { it == TurnOutcome.TAPPED })
    }

    @Test
    fun endedAndMicOpenedCarryTheTagAndFollowAsOfThatSession() {
        val r = Rig()
        val tag = TurnTag(conversationGen = 7, turn = 2)
        r.startTurn(request(follow = true, tag = tag, auto = true))
        assertEquals(listOf(true), r.listener.micOpenedAuto)
        assertEquals(listOf(true), r.listener.micOpenedFollow)
        assertEquals(listOf(tag), r.listener.micOpenedTags)
        r.talk(440)
        r.reply()
        r.player.finish(true)
        r.sim.settle()
        assertEquals(listOf(tag), r.listener.endedTags)
        assertEquals(listOf(true to "done"), r.listener.ended)
        assertEquals(TurnOutcome.PLAYED, r.outcome())
    }

    @Test
    fun theOldStartSignatureStillWorksWithDefaults() {
        val r = Rig()
        r.start(StartReason.NOTIFICATION, "n1", auto = true)
        assertEquals(VoicePhase.LISTENING, r.phase)
        val req = r.bridge.created.single()
        assertEquals(StartReason.NOTIFICATION, req.reason)
        assertEquals("n1", req.noticeId)
        assertNull("会話を持たない呼び方は本文に conversation を書かない", req.conversation)
        assertEquals(VoiceLimits.NO_SPEECH_DEFAULT_S, req.noSpeechS)
        assertEquals(listOf(false), r.listener.micOpenedFollow)
        assertEquals(listOf(TurnTag.NONE), r.listener.micOpenedTags)
    }

    // ---------------------------------------------------------------- 開始の許可は呼び出しのスレッドで控える

    @Test
    fun startThenCancelCreatesNothingAndNeverRecords() {
        val r = Rig()
        r.session.start(request())
        r.session.cancel() // どちらもまだキューの中
        r.sim.settle()
        assertEquals(0, r.bridge.created.size)
        assertEquals(0, r.audio.startCount)
        assertEquals(VoicePhase.IDLE, r.phase)
        assertTrue(r.listener.ended.isEmpty())
    }

    @Test
    fun startThenStopMicNowCreatesNothing() {
        val r = Rig()
        r.session.start(request())
        r.session.stopMicNow()
        r.sim.settle()
        assertEquals(0, r.bridge.created.size)
        assertEquals(0, r.audio.startCount)
    }

    @Test
    fun cancelThenStartCreatesOnce() { // DaidaiVoice.startSession の順序
        val r = Rig()
        r.session.cancel()
        r.session.start(request())
        r.sim.settle()
        assertEquals(1, r.bridge.created.size)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(1, r.audio.startCount)
    }

    @Test
    fun startThenCancelThenStartCreatesOnce() {
        val r = Rig()
        r.session.start(request()) // 取り消される
        r.session.cancel()
        r.session.start(request()) // 新しい許可
        r.sim.settle()
        assertEquals(1, r.bridge.created.size)
        assertEquals(VoicePhase.LISTENING, r.phase)
    }

    @Test
    fun aStaleStartDoesNotTouchTheStateOrTheErrorDisplay() {
        val r = Rig()
        r.bridge.createReply = { failWith(bridgeError(BridgeFailure.SERVER_ERROR, 500)) }
        r.start()
        assertEquals(VoicePhase.ERROR, r.phase)
        val snaps = r.listener.snaps.size
        r.session.start(request())
        r.session.stopMicNow() // 許可を取り消す。cancel() と違い、ERROR の表示は触らない
        r.sim.settle()
        assertEquals("作成の要求は出ない", 1, r.bridge.created.size)
        assertEquals("状態を変えない", VoicePhase.ERROR, r.phase)
        assertEquals("通知もしない", snaps, r.listener.snaps.size)
        r.advance(VoiceLimits.ERROR_DISPLAY_MS) // ERROR の表示のタイマーはそのまま効く
        assertEquals(VoicePhase.IDLE, r.phase)
    }

    @Test
    fun retryWithRequestOnlyStartsFromErrorAndAlsoCapturesThePermit() {
        val r = Rig()
        r.bridge.createReply = { n -> if (n == 1) failWith(bridgeError(BridgeFailure.SERVER_ERROR, 500)) else ok(SessionInfo("s-$n", r.bridge.hostLimits)) }
        r.session.retry(request()) // ERROR ではない: 何もしない
        r.sim.settle()
        assertEquals(0, r.bridge.created.size)
        r.start()
        assertEquals(VoicePhase.ERROR, r.phase)

        r.session.retry(request(noSpeechS = 6))
        r.session.cancel() // 取消が先に来る: 作成しない
        r.sim.settle()
        assertEquals(1, r.bridge.created.size)

        r.start() // 2 回目は成功
        r.cancel()
        r.start()
        assertEquals(VoicePhase.LISTENING, r.phase)
    }

    @Test
    fun retryWithRequestSendsTheRequestedBody() {
        val r = Rig()
        r.bridge.createReply = { n -> if (n == 1) failWith(bridgeError(BridgeFailure.SERVER_ERROR, 500)) else ok(SessionInfo("s-$n", r.bridge.hostLimits)) }
        r.start()
        assertEquals(VoicePhase.ERROR, r.phase)
        r.session.retry(request(conversation = ConversationParam(), noSpeechS = 6, tag = TurnTag(3, 1)))
        r.sim.settle()
        val second = r.bridge.created[1]
        assertEquals(ConversationParam(), second.conversation)
        assertEquals(6L, second.noSpeechS)
        assertEquals(listOf(TurnTag(3, 1)), r.listener.micOpenedTags)
    }

    // ---------------------------------------------------------------- 作成の本文

    @Test
    fun theRequestBodyCarriesNoSpeechSecondsAndTheConversationParam() {
        val r = Rig()
        r.startTurn(request(noSpeechS = VoiceLimits.FOLLOW_NO_SPEECH_S, conversation = ConversationParam(handle)))
        val req = r.bridge.created.single()
        assertEquals(6L, req.noSpeechS)
        assertEquals(ConversationParam(handle), req.conversation)
        assertEquals(6L, VoiceLimits.FOLLOW_NO_SPEECH_S)
    }

    // ---------------------------------------------------------------- ハンドルの受け渡し

    private fun withConversation(r: Rig, h: String? = handle, context: Boolean? = true, cont: Boolean = true) {
        r.bridge.createReply = { n -> ok(SessionInfo("s-$n", r.bridge.hostLimits, ConversationInfo(h, context, cont))) }
    }

    @Test
    fun theHandleOfTheCurrentGenerationReachesTheListenerOnceBeforeSseAndMic() {
        val r = Rig()
        withConversation(r)
        val tag = TurnTag(4, 1)
        r.startTurn(request(conversation = ConversationParam(), tag = tag))
        assertEquals(1, r.listener.conversations.size)
        val (t, info) = r.listener.conversations.single()
        assertEquals(tag, t)
        assertEquals(handle, info.handle)
        assertEquals(true, info.context)
        assertTrue(info.continueAllowed)
        assertTrue("onConversation は startSse と openMic より前", r.log.indexOf("conversation") < r.log.indexOf("events"))
        assertTrue(r.log.indexOf("conversation") < r.log.indexOf("audio.start"))
        assertTrue("現在の世代のハンドルでは会話の終わりを送らない", r.bridge.conversationEnds.isEmpty())
    }

    @Test
    fun theHandleIsDeliveredEvenWhenACancelStopsTheMicOpen() {
        val r = Rig()
        withConversation(r)
        r.session.start(request(tag = TurnTag(1, 1)))
        r.sim.step() // doStart
        r.sim.step() // 作成の完了(onCreated は配送待ち)
        r.session.cancel()
        r.sim.settle()
        assertEquals(1, r.listener.conversations.size)
        assertEquals(0, r.audio.startCount)
        assertEquals(TurnOutcome.CANCELLED, r.outcome())
        assertEquals(listOf("s-1"), r.bridge.cancels)
    }

    @Test
    fun noConversationInTheResponseMeansNoNotification() {
        val r = Rig()
        r.startTurn(request())
        assertTrue(r.listener.conversations.isEmpty())
    }

    @Test
    fun aListenerExceptionOnConversationIsSwallowed() {
        val r = Rig()
        withConversation(r)
        r.listener.throwOnConversation = true
        r.startTurn(request())
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(1, r.audio.startCount)
    }

    @Test
    fun aLateCreateOfAStaleGenerationDeletesAndEndsTheConversationWithoutRecording() {
        val r = Rig()
        r.bridge.createReply = { n ->
            ok(SessionInfo("s-late", r.bridge.hostLimits, ConversationInfo(handle, true, true)), latencyMs = 2_000, abortable = false)
        }
        r.startTurn(request(conversation = ConversationParam()))
        assertEquals(VoicePhase.PREPARING, r.phase)
        r.advance(100)
        r.cancel() // 作成の要求を送った後の取消
        r.advance(3_000)
        assertEquals(0, r.audio.startCount)
        assertTrue(r.bridge.eventOpens.isEmpty())
        assertEquals("遅れて届いた 201 を直ちに DELETE", listOf("s-late"), r.bridge.cancels)
        assertEquals("ハンドルの終わりも 1 回", listOf(handle), r.bridge.conversationEnds)
        assertTrue("古い世代のハンドルは持ち主へ渡さない", r.listener.conversations.isEmpty())
    }

    @Test
    fun aLateCreateWithoutAHandleDoesNotSendAConversationEnd() {
        val r = Rig()
        r.bridge.createReply = { ok(SessionInfo("s-late", r.bridge.hostLimits, ConversationInfo(null, null, true)), latencyMs = 2_000, abortable = false) }
        r.startTurn(request())
        r.cancel()
        r.advance(3_000)
        assertEquals(listOf("s-late"), r.bridge.cancels)
        assertTrue(r.bridge.conversationEnds.isEmpty())
    }

    @Test
    fun aFailingConversationEndDoesNotDisturbTheSession() {
        val r = Rig()
        r.bridge.endConversationReply = failWith(IOException("down"))
        r.bridge.createReply = { ok(SessionInfo("s-late", r.bridge.hostLimits, ConversationInfo(handle, true, true)), latencyMs = 2_000, abortable = false) }
        r.startTurn(request())
        r.cancel()
        r.advance(3_000)
        assertEquals(listOf(handle), r.bridge.conversationEnds)
        assertEquals(VoicePhase.IDLE, r.phase)
        r.startTurn(request()) // 次の往復は普通に始まる
        assertEquals(2, r.bridge.created.size)
    }

    // ---------------------------------------------------------------- 続きの往復の無発話

    @Test
    fun followNoSpeechFromTheHostEndsAtIdleWithoutAnError() {
        val r = Rig()
        r.startTurn(request(follow = true, tag = TurnTag(2, 2)))
        r.talk(440)
        r.hostError("no_speech")
        assertEquals(VoicePhase.IDLE, r.phase)
        assertEquals(VoiceState.IDLE, r.last.state)
        assertNull(r.last.error)
        assertFalse("ERROR を表示しない", r.states().contains(VoiceState.ERROR))
        assertEquals(listOf(false to "no_speech"), r.listener.ended)
        assertEquals(TurnOutcome.ERROR, r.outcome())
        assertEquals(listOf(TurnTag(2, 2)), r.listener.endedTags)
        assertFalse(r.audio.recording)
        assertEquals(listOf("s-1"), r.bridge.cancels)
        val snaps = r.listener.snaps.size
        r.advance(VoiceLimits.ERROR_DISPLAY_MS * 2) // ERROR の表示のタイマーは張られていない
        assertEquals(snaps, r.listener.snaps.size)
        assertEquals(1, r.listener.ended.size)
    }

    @Test
    fun followNoSpeechFromThePanelTimerEndsAtIdleWithoutAnError() {
        val r = Rig()
        r.bridge.createReply = { ok(SessionInfo("s-1", HostLimits(noSpeechS = 2, replyTimeoutS = 3))) }
        r.startTurn(request(follow = true, noSpeechS = 6))
        r.talk(440)
        r.talk(5_000 - 20) // no_speech_s(2)+3 秒
        assertEquals(VoicePhase.IDLE, r.phase)
        assertNull(r.last.error)
        assertFalse(r.states().contains(VoiceState.ERROR))
        assertEquals(listOf(false to "no_speech"), r.listener.ended)
        assertEquals(TurnOutcome.ERROR, r.outcome())
        assertFalse(r.audio.recording)
    }

    @Test
    fun theFirstTurnNoSpeechStillShowsTheError() {
        val host = Rig()
        host.startTurn(request(follow = false))
        host.talk(440)
        host.hostError("no_speech")
        assertEquals(VoicePhase.ERROR, host.phase)
        assertEquals(VoiceErrorCode.NO_SPEECH, host.last.error)

        val panel = Rig()
        panel.bridge.createReply = { ok(SessionInfo("s-1", HostLimits(noSpeechS = 2, replyTimeoutS = 3))) }
        panel.startTurn(request(follow = false))
        panel.talk(440)
        panel.talk(5_000 - 20)
        assertEquals(VoicePhase.ERROR, panel.phase)
        assertEquals(VoiceErrorCode.NO_SPEECH, panel.last.error)
    }

    @Test
    fun otherErrorsOfAFollowTurnStillShowTheError() {
        val silent = Rig() // mic_silent
        silent.startTurn(request(follow = true))
        silent.talk(3_400, amp = 0)
        assertEquals(VoicePhase.ERROR, silent.phase)
        assertEquals(VoiceErrorCode.MIC_SILENT, silent.last.error)

        val host = Rig()
        host.startTurn(request(follow = true))
        host.talk(440)
        host.hostError("asr_failed")
        assertEquals(VoicePhase.ERROR, host.phase)
        assertEquals(VoiceErrorCode.ASR_FAILED, host.last.error)
        assertEquals(TurnOutcome.ERROR, host.outcome())
    }

    // ---------------------------------------------------------------- AC4: 半二重と開始音の保護(続きの往復も同じ)

    @Test
    fun aFollowTurnDoesNotOpenTheMicWhilePlayingAndDropsTheFirst400Ms() {
        val r = Rig()
        r.startTurn(request(follow = true))
        assertEquals(1, r.audio.startCount)
        r.talk(380) // 開いた直後の 400 ms は捨てる
        assertTrue("保護時間の録音は送らない", r.bridge.chunks.isEmpty())
        r.talk(60)
        assertTrue(r.bridge.chunks.isNotEmpty())
        assertTrue(r.bridge.chunks.all { it.atMs >= 400L })
        r.waiting()
        r.reply()
        assertEquals(VoicePhase.REPLYING, r.phase)
        assertTrue(r.player.playing)
        r.advance(3_000) // 再生中
        assertEquals("再生中はマイクを開かない", 1, r.audio.startCount)
        assertFalse(r.audio.recording)
        r.player.finish(true)
        r.sim.settle()
        assertEquals(1, r.audio.startCount)
    }

    // ---------------------------------------------------------------- toString にハンドルを出さない

    @Test
    fun noNewTypeLeaksTheHandleInToString() {
        val conv = ConversationParam(handle)
        val all = listOf(
            conv.toString(),
            ConversationInfo(handle, true, true).toString(),
            SessionInfo("s-1", HostLimits(), ConversationInfo(handle, true, true)).toString(),
            SessionRequest("echo-show-5", "0.4.0", StartReason.SWIPE, null, conversation = conv).toString(),
            request(conversation = conv).toString(),
        )
        for (s in all) assertFalse(s, s.contains(handle))
        assertTrue(ConversationInfo(handle, true, true).toString().contains("handle=set"))
        assertTrue(ConversationParam().toString().contains("handle=none"))
    }
}
