package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 通信シナリオ表(設計書 §5.2.8)S1〜S9, S11, S13〜S15 のうち VoiceSession が担う部分と、AC4/AC6/AC8 のエラー別。 */
class VoiceSessionTest {
    private companion object {
        /** 開始音の保護時間のあと、最初の PCM ブロックが届いてチャンクになる時刻 */
        const val FIRST_CHUNK_AT = 400L
    }

    // ---------------------------------------------------------------- S1 正常

    @Test
    fun s1_normalRoundTrip() {
        val r = Rig()
        r.start(StartReason.SWIPE, null, auto = false)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertTrue(r.audio.recording)
        assertEquals(1, r.listener.micOpenedAt.size)
        assertTrue("作成のあとでマイクを開く", r.log.indexOf("create") < r.log.indexOf("audio.start"))
        assertTrue(r.log.indexOf("audio.start") < r.log.indexOf("micOpened"))

        r.talk(1000)
        r.ackState = AckState.SPEECH
        r.talk(200)
        assertEquals(VoicePhase.HEARING, r.phase)
        r.ackState = AckState.ENDED
        r.talk(100)
        assertEquals(VoicePhase.WAITING, r.phase)
        assertFalse("ended を受けたら直ちにマイクを止める", r.audio.recording)

        r.transcript("今日の天気は")
        assertEquals("今日の天気は", r.last.transcript)
        r.reply("晴れです")
        assertEquals(VoicePhase.REPLYING, r.phase)
        assertEquals(1, r.player.plays)
        assertFalse("再生中はマイクを開かない(半二重)", r.audio.recording)
        r.done()
        assertEquals("done は再生完了ではない", VoicePhase.REPLYING, r.phase)
        r.player.finish(true)
        r.sim.settle()
        assertEquals(VoicePhase.DONE, r.phase)

        assertEquals(listOf(VoiceState.IDLE, VoiceState.LISTENING, VoiceState.HEARING, VoiceState.WAITING, VoiceState.REPLYING, VoiceState.IDLE), r.states())
        assertEquals("晴れです", r.last.reply)
        assertEquals("今日の天気は", r.last.transcript)
        assertNull(r.last.error)
        assertEquals("talking", r.last.noticePose)
        assertEquals(1, r.audio.startCount)
        assertEquals(listOf(false to "done"), r.listener.ended)
        assertEquals(listOf("s-1"), r.bridge.cancels)
        // 連番は 0 から連続し、本文は 20〜500 ms の範囲・2 の倍数
        assertEquals((0 until r.bridge.chunks.size).toList(), r.bridge.chunks.map { it.seq })
        r.bridge.chunks.forEach {
            assertTrue(it.pcm.size in VoiceLimits.CHUNK_MIN_BYTES..VoiceLimits.CHUNK_MAX_BYTES)
            assertEquals(0, it.pcm.size % 2)
        }
        val req = r.bridge.created.single()
        assertEquals(StartReason.SWIPE, req.reason)
        assertEquals("echo-show-5", req.clientId)
        assertNull(req.noticeId)
    }

    @Test
    fun startToneGuardDropsEarlyAudio() {
        val r = Rig()
        r.startAndListen()
        r.talk(400)
        assertTrue("開始音の間の録音は送らない", r.bridge.chunks.isEmpty())
        r.talk(100)
        assertEquals(5, r.bridge.chunks.size)
        assertEquals(5 * 640, r.bridge.chunks.sumOf { it.pcm.size })
    }

    @Test
    fun notificationStartCarriesReasonAndNoticeId() {
        val r = Rig()
        r.start(StartReason.NOTIFICATION, "n-20261108-0731", auto = true)
        val req = r.bridge.created.single()
        assertEquals(StartReason.NOTIFICATION, req.reason)
        assertEquals("n-20261108-0731", req.noticeId)
        r.cancel()
        assertEquals(listOf(true to "cancelled"), r.listener.ended)
    }

    @Test
    fun invalidNoticeIdIsNotSent() {
        val r = Rig()
        r.start(StartReason.NOTIFICATION, "bad id\n", auto = true)
        assertNull(r.bridge.created.single().noticeId)
    }

    @Test
    fun onlyOneSessionAtATime() {
        val r = Rig()
        r.start()
        r.start()
        r.start()
        assertEquals(1, r.bridge.created.size)
        assertEquals(1, r.audio.startCount)
    }

    // ---------------------------------------------------------------- S2 / 再送の規則

    @Test
    fun s2_lostAckIsResentOnceWithSameSeqAndBody() {
        val r = Rig()
        r.bridge.chunkReply = { c ->
            if (c.seq == 3 && c.attempt == 0) failWith(IOException("ack lost"), latencyMs = 50) else ok(ChunkAck(r.ackState, c.seq))
        }
        r.startAndListen()
        r.talk(1500)
        assertEquals(VoicePhase.LISTENING, r.phase)
        val threes = r.bridge.chunks.filter { it.seq == 3 }
        assertEquals(2, threes.size)
        assertTrue(threes[0].pcm.contentEquals(threes[1].pcm))
        assertEquals(listOf(0, 1), threes.map { it.attempt })
        val seqs = r.bridge.chunks.map { it.seq }
        assertEquals("再送の間に別の seq を送らない", seqs.indexOf(3) + 1, seqs.lastIndexOf(3))
        assertEquals(seqs.distinct().sorted(), seqs.distinct())
    }

    private fun assertSendFailure(failure: BridgeException, expected: VoiceErrorCode, sends: Int) {
        val r = Rig()
        r.bridge.chunkReply = { failWith(failure) }
        r.startAndListen()
        r.talk(600)
        assertEquals("$failure", VoicePhase.ERROR, r.phase)
        assertEquals("$failure", expected, r.last.error)
        assertEquals("$failure", sends, r.bridge.chunks.count { it.seq == 0 })
        assertFalse(r.audio.recording)
        assertEquals(listOf("s-1"), r.bridge.cancels)
    }

    @Test
    fun retryRules() {
        assertSendFailure(bridgeError(BridgeFailure.NETWORK), VoiceErrorCode.HOST_UNREACHABLE, 2)
        assertSendFailure(bridgeError(BridgeFailure.SERVER_ERROR, 502), VoiceErrorCode.HOST_UNREACHABLE, 2)
        assertSendFailure(bridgeError(BridgeFailure.SERVER_ERROR, 503), VoiceErrorCode.HOST_UNREACHABLE, 2)
        assertSendFailure(bridgeError(BridgeFailure.SERVER_ERROR, 504), VoiceErrorCode.HOST_UNREACHABLE, 2)
        assertSendFailure(bridgeError(BridgeFailure.SERVER_ERROR, 500), VoiceErrorCode.HOST_UNREACHABLE, 1)
        assertSendFailure(bridgeError(BridgeFailure.CLIENT_ERROR, 400), VoiceErrorCode.HOST_UNREACHABLE, 1)
        assertSendFailure(bridgeError(BridgeFailure.CLIENT_ERROR, 401), VoiceErrorCode.HOST_UNREACHABLE, 1)
        assertSendFailure(bridgeError(BridgeFailure.MALFORMED), VoiceErrorCode.HOST_UNREACHABLE, 1)
        assertSendFailure(bridgeError(BridgeFailure.EXPIRED, 410), VoiceErrorCode.EXPIRED, 1)
        assertSendFailure(bridgeError(BridgeFailure.NOT_FOUND, 404), VoiceErrorCode.SESSION_LOST, 1)
        assertSendFailure(bridgeError(BridgeFailure.OUT_OF_ORDER, 409), VoiceErrorCode.SESSION_LOST, 1)
        assertSendFailure(bridgeError(BridgeFailure.SEQ_CONFLICT, 409), VoiceErrorCode.SESSION_LOST, 1)
        assertSendFailure(bridgeError(BridgeFailure.ASR_UNAVAILABLE, 503, "asr_unavailable"), VoiceErrorCode.ASR_UNAVAILABLE, 2)
        assertSendFailure(bridgeError(BridgeFailure.CLIENT_ERROR, 429, "busy"), VoiceErrorCode.BUSY, 1)
    }

    @Test
    fun untypedExceptionIsTreatedAsNetworkFailure() {
        val r = Rig()
        r.bridge.chunkReply = { failWith(IllegalStateException("boom")) }
        r.startAndListen()
        r.talk(600)
        assertEquals(VoiceErrorCode.HOST_UNREACHABLE, r.last.error)
        assertEquals(2, r.bridge.chunks.count { it.seq == 0 })
    }

    @Test
    fun chunkWatchdogAbortsAndResendsOnce() {
        val r = Rig()
        r.bridge.chunkReply = { c -> if (c.seq == 0 && c.attempt == 0) hang() else ok(ChunkAck(r.ackState, c.seq)) }
        r.startAndListen()
        r.talk(420) // 最初のチャンクが 400 ms に出る
        val sentAt = r.bridge.chunks.first().atMs
        assertEquals(0, r.bridge.aborts)
        r.advance(sentAt + 4_000 - r.sim.now - 1)
        assertEquals("4 秒の手前では切らない", 0, r.bridge.aborts)
        r.advance(1)
        assertEquals(1, r.bridge.aborts)
        assertEquals("同じ seq を 1 回だけ再送", listOf(0, 0), r.bridge.chunks.map { it.seq }.take(2))
        assertEquals(VoicePhase.LISTENING, r.phase)
        // 切れた SSE は回数に数えず、すぐ張り直す(Last-Event-ID はまだ無い)
        assertEquals(2, r.bridge.eventOpens.size)
        r.talk(100)
        assertEquals(VoicePhase.LISTENING, r.phase)
    }

    @Test
    fun chunkWatchdogTwiceEndsAsHostUnreachable() {
        val r = Rig()
        r.bridge.chunkReply = { hang() }
        r.startAndListen()
        r.talk(420)
        val sentAt = r.bridge.chunks.first().atMs
        r.advance(sentAt + 8_000 - r.sim.now - 1)
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.advance(1)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.HOST_UNREACHABLE, r.last.error)
        assertFalse(r.audio.recording)
    }

    @Test
    fun staleResultAfterWatchdogIsDropped() {
        val r = Rig()
        // 最初の試行は 4 秒を超えて 5 秒後に成功(中断できない通信)。再送は成功
        r.bridge.chunkReply = { c -> if (c.seq == 0 && c.attempt == 0) ok(ChunkAck(AckState.ENDED, 0), 5_000, abortable = false) else ok(ChunkAck(AckState.LISTENING, c.seq)) }
        r.startAndListen()
        r.talk(420)
        r.advance(5_000)
        // 遅れて届いた古い結果(ended)は、再送のトークンと違うので捨てられる
        assertEquals(VoicePhase.LISTENING, r.phase)
    }

    // ---------------------------------------------------------------- S3 / S4 / S5

    @Test
    fun s3_conflictEndedIsNormalEnd() {
        val r = Rig()
        r.bridge.chunkReply = { c -> if (c.seq >= 2) failWith(bridgeError(BridgeFailure.ENDED, 409, "ended")) else ok(ChunkAck(AckState.SPEECH, c.seq)) }
        r.startAndListen()
        r.talk(600)
        assertEquals(VoicePhase.WAITING, r.phase)
        assertNull("エラーにしない", r.last.error)
        assertFalse(r.audio.recording)
        assertEquals("再送もしない", 1, r.bridge.chunks.count { it.seq == 2 })
        r.reply("はい", audio = null)
        assertEquals(VoicePhase.DONE, r.phase)
        assertNull(r.last.error)
    }

    @Test
    fun s4_waitingFromSseBeatsEndedAck() {
        val r = Rig()
        r.bridge.chunkReply = { c -> if (c.seq == 1) ok(ChunkAck(AckState.ENDED, 1), latencyMs = 500) else ok(ChunkAck(AckState.SPEECH, c.seq)) }
        r.startAndListen()
        r.talk(440) // seq 0 → speech、seq 1 が 500 ms の ack 待ち
        assertEquals(VoicePhase.HEARING, r.phase)
        r.waiting()
        assertEquals(VoicePhase.WAITING, r.phase)
        assertFalse(r.audio.recording)
        val stopsBefore = r.audio.stopCount
        val snapsBefore = r.listener.snaps.size
        r.advance(600) // 後着の ended の ack
        assertEquals(VoicePhase.WAITING, r.phase)
        assertEquals("後着の ack は無視(何も変わらない)", snapsBefore, r.listener.snaps.size)
        assertEquals(stopsBefore, r.audio.stopCount)
        // 応答待ちの期限は state=waiting を受けた時刻から数える
        r.advance(50_000 - 600 - 1)
        assertEquals(VoicePhase.WAITING, r.phase)
        r.advance(1)
        assertEquals(VoiceErrorCode.TIMEOUT, r.last.error)
    }

    @Test
    fun s5_lateSpeechAckAfterReplyDoesNotRegress() {
        val r = Rig()
        r.bridge.chunkReply = { c -> if (c.seq >= 1) ok(ChunkAck(AckState.SPEECH, c.seq), latencyMs = 3_000) else ok(ChunkAck(AckState.LISTENING, c.seq)) }
        r.startAndListen()
        r.talk(440)
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.reply("返事")
        assertEquals(VoicePhase.REPLYING, r.phase)
        assertFalse(r.audio.recording)
        r.advance(3_500) // 遅れて speech の ack が届く
        assertEquals(VoicePhase.REPLYING, r.phase)
        assertFalse("HEARING に戻らない", r.states().contains(VoiceState.HEARING))
    }

    @Test
    fun replyAloneIsAConfirmedStopSignal() {
        val r = Rig()
        r.startAndListen()
        r.talk(600)
        r.reply("早い返答")
        assertEquals(VoicePhase.REPLYING, r.phase)
        assertFalse(r.audio.recording)
        val sent = r.bridge.chunks.size
        r.talk(200)
        assertEquals("未送信は捨てる(これ以上送らない)", sent, r.bridge.chunks.size)
    }

    // ---------------------------------------------------------------- S6 SSE

    @Test
    fun s6_reconnectUsesLastEventIdAndIgnoresDuplicates() {
        val r = Rig()
        r.startAndListen()
        r.speech() // seq 0
        r.transcript("最初") // seq 1
        assertEquals(listOf<Int?>(null), r.bridge.eventOpens)
        r.bridge.endStreams() // 終端なしの EOF
        r.sim.settle()
        r.reply("返事", audio = null) // 切断中にホストが reply(seq 2)を出す。購読が無いので届かない
        assertEquals(VoicePhase.HEARING, r.phase)
        r.advance(499)
        assertEquals(1, r.bridge.eventOpens.size)
        r.advance(1) // バックオフ 500 ms
        assertEquals(listOf<Int?>(null, 1), r.bridge.eventOpens)
        assertEquals("再生された reply で完了", VoicePhase.DONE, r.phase)
        assertEquals("返事", r.last.reply)
    }

    @Test
    fun duplicateAndOldSeqEventsAreIgnored() {
        val r = Rig()
        r.startAndListen()
        r.transcript("first") // seq 0
        r.transcript("second") // seq 1
        r.bridge.emitRaw(BridgeEvent.Transcript(1, "dup", true))
        r.bridge.emitRaw(BridgeEvent.Transcript(0, "old", true))
        r.sim.settle()
        assertEquals("second", r.last.transcript)
        r.bridge.emitRaw(BridgeEvent.Transcript(-1, "neg", true))
        r.bridge.emitRaw(BridgeEvent.Transcript(1_000_000_000, "huge", true))
        r.sim.settle()
        assertEquals("second", r.last.transcript)
    }

    @Test
    fun sseReconnectsAtMostThreeTimes() {
        val r = Rig()
        r.startAndListen()
        val delays = listOf(500L, 1_000L, 2_000L)
        for (d in delays) {
            r.bridge.endStreams(Result.failure(IOException("cut")))
            r.sim.settle()
            assertEquals(VoicePhase.LISTENING, r.phase)
            r.talk(d.toInt())
        }
        assertEquals(4, r.bridge.eventOpens.size)
        r.bridge.endStreams()
        r.sim.settle()
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.SESSION_LOST, r.last.error)
        assertFalse(r.audio.recording)
    }

    @Test
    fun noReconnectAfterReplyOrTerminal() {
        val r = Rig()
        r.startAndListen()
        r.reply("返事") // REPLYING(再生中)
        r.bridge.endStreams()
        r.advance(5_000)
        assertEquals("返答を受けたあとは再接続しない", 1, r.bridge.eventOpens.size)
        assertEquals(VoicePhase.REPLYING, r.phase)
        assertNull(r.last.error)
    }

    @Test
    fun sseNotFoundAndExpiredAreNotRetried() {
        val a = Rig()
        a.startAndListen()
        a.bridge.endStreams(Result.failure(bridgeError(BridgeFailure.NOT_FOUND, 404)))
        a.sim.settle()
        assertEquals(VoiceErrorCode.SESSION_LOST, a.last.error)
        val b = Rig()
        b.startAndListen()
        b.bridge.endStreams(Result.failure(bridgeError(BridgeFailure.EXPIRED, 410)))
        b.sim.settle()
        assertEquals(VoiceErrorCode.EXPIRED, b.last.error)
        assertEquals(1, b.bridge.eventOpens.size)
    }

    // ---------------------------------------------------------------- S7 離脱後の作成応答

    @Test
    fun s7_lateCreateAfterLeavingDeletesWithoutRecording() {
        val r = Rig()
        r.bridge.createReply = { ok(SessionInfo("s-late", r.bridge.hostLimits), latencyMs = 2_000, abortable = false) }
        r.start()
        assertEquals(VoicePhase.PREPARING, r.phase)
        r.advance(100)
        r.cancel()
        assertEquals(VoicePhase.IDLE, r.phase)
        assertTrue("離脱で通信を切る", r.bridge.aborts >= 1)
        r.advance(3_000)
        assertEquals(0, r.audio.startCount)
        assertTrue(r.bridge.eventOpens.isEmpty())
        assertEquals("直ちにその session_id で DELETE", listOf("s-late"), r.bridge.cancels)
        assertEquals(VoicePhase.IDLE, r.phase)
    }

    @Test
    fun s7_lateCreateWithInvalidIdIsNotDeleted() {
        val r = Rig()
        r.bridge.createReply = { ok(SessionInfo("../evil", r.bridge.hostLimits), latencyMs = 2_000, abortable = false) }
        r.start()
        r.cancel()
        r.advance(3_000)
        assertTrue(r.bridge.cancels.isEmpty())
    }

    @Test
    fun invalidSessionIdFailsTheCreate() {
        val bad = listOf("", "a b", "x".repeat(65), "../x", "a/b", "日本語", "a\nb")
        for (id in bad) {
            val r = Rig()
            r.bridge.createReply = { ok(SessionInfo(id, r.bridge.hostLimits)) }
            r.start()
            assertEquals("'$id'", VoicePhase.ERROR, r.phase)
            assertEquals(VoiceErrorCode.HOST_UNREACHABLE, r.last.error)
            assertEquals(0, r.audio.startCount)
            assertTrue(r.bridge.cancels.isEmpty())
            assertTrue(r.bridge.eventOpens.isEmpty())
        }
        val r = Rig()
        r.bridge.createReply = { ok(SessionInfo("a".repeat(64), r.bridge.hostLimits)) }
        r.start()
        assertEquals(VoicePhase.LISTENING, r.phase)
    }

    @Test
    fun rapidStartCancelStartUsesGenerations() {
        val r = Rig()
        r.bridge.createReply = { n ->
            if (n == 1) ok(SessionInfo("s-1", r.bridge.hostLimits), 1_000, abortable = false) else ok(SessionInfo("s-$n", r.bridge.hostLimits), 100)
        }
        r.start()
        r.advance(10)
        r.cancel()
        r.start()
        r.advance(2_000)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(1, r.audio.startCount)
        assertEquals("古い作成の成功は DELETE だけ", listOf("s-1"), r.bridge.cancels)
        r.talk(600)
        assertTrue("新しいセッションに送る", r.bridge.chunks.isNotEmpty())
        assertEquals(1, r.bridge.eventOpens.size)
    }

    @Test
    fun manyRapidCyclesLeaveExactlyOneLiveSession() {
        val r = Rig()
        r.bridge.createReply = { n -> ok(SessionInfo("s-$n", r.bridge.hostLimits), latencyMs = (n % 4) * 50L, abortable = n % 2 == 0) }
        repeat(25) {
            r.session.start(StartReason.SWIPE, null, false)
            r.sim.advance(((it * 37) % 90).toLong())
            r.session.cancel()
            r.sim.advance(((it * 13) % 40).toLong())
        }
        r.start()
        r.advance(1_000)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertTrue("録音は同時に 1 つ", r.audio.recording)
        r.cancel()
        assertFalse(r.audio.recording)
        assertEquals(VoicePhase.IDLE, r.phase)
    }

    // ---------------------------------------------------------------- S8 / S9 期限

    @Test
    fun s8_silentHostAfterEndedTimesOutAtReplyTimeoutPlusFive() {
        val r = Rig()
        r.ackState = AckState.ENDED
        r.startAndListen()
        r.talk(440)
        assertEquals(VoicePhase.WAITING, r.phase)
        r.advance(FIRST_CHUNK_AT + 50_000 - r.sim.now - 1)
        assertEquals(VoicePhase.WAITING, r.phase)
        r.advance(1)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.TIMEOUT, r.last.error)
        assertEquals(listOf(false to "timeout"), r.listener.ended)
    }

    @Test
    fun s9_micStopsAt45SecondsAndSendsFinalChunk() {
        val r = Rig()
        r.ackState = AckState.SPEECH // ホストが ended を返さない
        r.startAndListen()
        r.talk(44_990)
        assertTrue(r.audio.recording)
        r.talk(10)
        assertFalse("45 秒でマイクを止める", r.audio.recording)
        assertEquals(listOf(45_000L), r.audio.stopTimes)
        assertEquals(VoicePhase.WAITING, r.phase)
        r.sim.settle()
        val finals = r.bridge.chunks.filter { it.final }
        assertEquals(1, finals.size)
        assertEquals("final は最後の 1 回", r.bridge.chunks.last(), finals.single())
        assertTrue(finals.single().pcm.size in VoiceLimits.CHUNK_MIN_BYTES..VoiceLimits.CHUNK_MAX_BYTES)
        // 録音した 45 秒から開始音の保護時間を引いた分以下しか送らない
        val sent = r.bridge.chunks.sumOf { it.pcm.size }
        assertTrue("sent=$sent", sent <= (45_000 - 400) * 32 + VoiceLimits.CHUNK_MIN_BYTES)
        // そのあとは応答待ちの期限で終わる
        r.advance(50_000)
        assertEquals(VoiceErrorCode.TIMEOUT, r.last.error)
    }

    @Test
    fun capFlushWithEmptyQueuePadsFinalChunkToMinimum() {
        val r = Rig(guardMs = 0)
        r.ackState = AckState.SPEECH
        r.startAndListen()
        r.talk(44_980) // 最後のブロックまで送り終えている(キューは空)
        r.advance(20)
        val final = r.bridge.chunks.single { it.final }
        assertEquals(VoiceLimits.CHUNK_MIN_BYTES, final.pcm.size)
    }

    @Test
    fun capFlushWaitsForInFlightChunkThenSendsRemainder() {
        val r = Rig(guardMs = 0)
        r.bridge.chunkReply = { c -> ok(ChunkAck(AckState.SPEECH, c.seq), latencyMs = 300) }
        r.startAndListen()
        r.talk(45_000)
        r.advance(2_000)
        assertEquals(1, r.bridge.chunks.count { it.final })
        assertEquals(1, r.bridge.maxConcurrentSends)
        assertEquals(VoicePhase.WAITING, r.phase)
        assertNull(r.last.error)
    }

    @Test
    fun totalSessionLimitCancelsWithTimeout() {
        val r = Rig(sessionMaxMs = 10_000)
        r.ackState = AckState.SPEECH
        r.startAndListen()
        r.talk(9_990)
        assertEquals(VoicePhase.HEARING, r.phase)
        r.talk(10)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.TIMEOUT, r.last.error)
        assertFalse(r.audio.recording)
        assertEquals(listOf("s-1"), r.bridge.cancels)
    }

    @Test
    fun totalSessionLimitWhileReplyingEndsAsDone() {
        val r = Rig(sessionMaxMs = 10_000)
        r.startAndListen()
        r.reply("長い返答", audio = ReplyAudioInfo("wav", 24_000, 2000, 120_000))
        assertEquals(VoicePhase.REPLYING, r.phase)
        r.advance(10_000)
        assertEquals(VoicePhase.DONE, r.phase)
        assertNull(r.last.error)
        assertEquals("長い返答", r.last.reply)
        assertTrue(r.player.stopCount >= 1)
    }

    // ---------------------------------------------------------------- S11 / S15 返答音声

    @Test
    fun s11_nullAudioShowsTextOnly() {
        val r = Rig()
        r.startAndListen()
        r.reply("音声なし", audio = null, audioError = "tts_failed")
        assertEquals(VoicePhase.DONE, r.phase)
        assertEquals("音声なし", r.last.reply)
        assertNull(r.last.error)
        assertNotNull(r.last.diagnostics)
        assertEquals(0, r.player.plays)
        assertTrue(r.bridge.fetches.isEmpty())
        assertEquals(listOf(false to "done"), r.listener.ended)
    }

    private fun assertReplyAudioFailure(setup: (Rig) -> Unit, finishPlayer: Boolean = false, advanceMs: Long = 0, info: ReplyAudioInfo? = ReplyAudioInfo("wav", 24_000, 2000, 4200)) {
        val r = Rig()
        setup(r)
        r.startAndListen()
        r.reply("テキストは残る", audio = info)
        if (finishPlayer) r.player.finish(false)
        r.advance(advanceMs)
        assertEquals(VoicePhase.DONE, r.phase)
        assertEquals(VoiceErrorCode.REPLY_AUDIO_FAILED, r.last.error)
        assertEquals("テキストは残る", r.last.reply)
        assertEquals(VoiceState.IDLE, r.last.state)
        assertEquals(listOf(false to "reply_audio_failed"), r.listener.ended)
    }

    @Test
    fun s15_replyAudioFailures() {
        // 取得の失敗
        assertReplyAudioFailure({ it.bridge.fetchReply = { failWith(bridgeError(BridgeFailure.MALFORMED)) } })
        // 取得が 20 秒を超える(中断されるまで戻らない)
        assertReplyAudioFailure({ it.bridge.fetchReply = { hang() } }, advanceMs = 20_000)
        // 10 MB 超
        assertReplyAudioFailure({ it.bridge.fetchReply = { ok(ByteArray(VoiceLimits.REPLY_AUDIO_MAX_BYTES + 1)) } })
        // 空
        assertReplyAudioFailure({ it.bridge.fetchReply = { ok(ByteArray(0)) } })
        // 再生の失敗
        assertReplyAudioFailure({ }, finishPlayer = true)
        assertReplyAudioFailure({ it.player.throwOnPlay = IllegalStateException("no") })
        // 宣言が上限超え(取得しない)・形式違い
        assertReplyAudioFailure({ }, info = ReplyAudioInfo("wav", 24_000, 11L * 1024 * 1024, 4200))
        assertReplyAudioFailure({ }, info = ReplyAudioInfo("wav", 24_000, 2000, 120_001))
        assertReplyAudioFailure({ }, info = ReplyAudioInfo("mp3", 24_000, 2000, 4200))
        assertReplyAudioFailure({ }, info = ReplyAudioInfo("wav", 24_000, 0, 4200))
    }

    @Test
    fun fetchTimeoutIsExactlyTwentySeconds() {
        val r = Rig()
        r.bridge.fetchReply = { hang() }
        r.startAndListen()
        r.reply()
        r.advance(19_999)
        assertEquals(VoicePhase.REPLYING, r.phase)
        r.advance(1)
        assertEquals(VoicePhase.DONE, r.phase)
    }

    @Test
    fun declaredBadAudioIsNotFetched() {
        val r = Rig()
        r.startAndListen()
        r.reply(audio = ReplyAudioInfo("wav", 24_000, 2000, 120_001))
        assertTrue(r.bridge.fetches.isEmpty())
        assertEquals(0, r.player.plays)
    }

    @Test
    fun playbackTimeoutIsDurationPlusFiveSeconds() {
        val r = Rig()
        r.startAndListen()
        r.reply(audio = ReplyAudioInfo("wav", 24_000, 2000, 4_200))
        assertEquals(VoicePhase.REPLYING, r.phase)
        r.advance(9_199)
        assertEquals(VoicePhase.REPLYING, r.phase)
        r.advance(1)
        assertEquals(VoicePhase.DONE, r.phase)
        assertNull("再生の時間切れは失敗ではなく、停止して DONE", r.last.error)
        assertTrue(r.player.stopCount >= 1)
    }

    @Test
    fun playbackIsCappedAt120Seconds() {
        val r = Rig()
        r.startAndListen()
        r.reply(audio = ReplyAudioInfo("wav", 24_000, 2000, 119_000))
        r.advance(119_999)
        assertEquals(VoicePhase.REPLYING, r.phase)
        r.advance(1)
        assertEquals(VoicePhase.DONE, r.phase)
    }

    @Test
    fun playbackCompletionBeforeTimerIsDone() {
        val r = Rig()
        r.startAndListen()
        r.reply()
        r.advance(1_000)
        r.player.finish(true)
        r.sim.settle()
        assertEquals(VoicePhase.DONE, r.phase)
        assertNull(r.last.error)
        r.advance(60_000)
        assertEquals("その後は何も起きない", 1, r.listener.ended.size)
    }

    @Test
    fun replyIndexOtherThanZeroAndSecondReplyAreIgnored() {
        val r = Rig()
        r.startAndListen()
        r.reply("別の index", index = 1)
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.reply("最初")
        assertEquals("最初", r.last.reply)
        r.reply("二つ目")
        assertEquals("最初", r.last.reply)
        assertEquals(1, r.player.plays)
    }

    // ---------------------------------------------------------------- S13 / S14 キュー

    @Test
    fun s13_slowAcksAreCaughtUpByBatching() {
        val r = Rig(guardMs = 0)
        r.bridge.chunkReply = { c -> ok(ChunkAck(AckState.LISTENING, c.seq), latencyMs = 300) }
        r.startAndListen()
        r.talk(5_000)
        r.advance(1_000)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertNull(r.last.error)
        assertEquals("同時に 1 リクエストだけ", 1, r.bridge.maxConcurrentSends)
        r.bridge.chunks.forEach {
            assertTrue("size=${it.pcm.size}", it.pcm.size in VoiceLimits.CHUNK_MIN_BYTES..VoiceLimits.CHUNK_MAX_BYTES)
        }
        assertTrue("まとめ送りで 20 ms より大きい", r.bridge.chunks.drop(1).any { it.pcm.size > 640 })
        val fed = 5_000 * 32
        val sent = r.bridge.chunks.sumOf { it.pcm.size }
        assertTrue("sent=$sent fed=$fed", sent in (fed - VoiceLimits.CHUNK_MIN_BYTES + 1)..fed)
        // 内容も順序どおり連続している(20 ms 刻みの矩形波なので、全部の長さだけ確かめる)
        assertEquals((0 until r.bridge.chunks.size).toList(), r.bridge.chunks.map { it.seq })
    }

    @Test
    fun s14_queueOverTwoSecondsEndsAsHostUnreachable() {
        val r = Rig()
        r.bridge.chunkReply = { hang() }
        r.startAndListen()
        r.talk(400)
        r.talk(20) // 最初のチャンク(640 バイト)が出て、応答待ち
        assertEquals(1, r.bridge.chunks.size)
        r.talk(2_000) // キューにちょうど 64,000 バイト
        assertEquals("ちょうど 2 秒分(64,000 バイト)まではよい", VoicePhase.LISTENING, r.phase)
        assertTrue(r.audio.recording)
        r.talk(20)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.HOST_UNREACHABLE, r.last.error)
        assertFalse("マイクを止める", r.audio.recording)
        assertTrue(r.bridge.aborts >= 1)
        assertEquals(listOf("s-1"), r.bridge.cancels)
        assertEquals(64_000, VoiceLimits.QUEUE_MAX_BYTES)
    }

    @Test
    fun oddSizedPcmBlocksKeepChunksEven() {
        val r = Rig(guardMs = 0)
        r.startAndListen()
        repeat(40) {
            r.audio.feedBytes(641)
            r.sim.advance(20)
        }
        r.bridge.chunks.forEach { assertEquals(0, it.pcm.size % 2) }
        assertTrue(r.bridge.chunks.isNotEmpty())
    }

    // ---------------------------------------------------------------- AC8 エラー別

    @Test
    fun createFailuresMapToErrors() {
        fun case(resp: Resp<SessionInfo>, expected: VoiceErrorCode) {
            val r = Rig()
            r.bridge.createReply = { resp }
            r.start()
            assertEquals(VoicePhase.ERROR, r.phase)
            assertEquals(expected, r.last.error)
            assertEquals(0, r.audio.startCount)
            assertFalse(r.audio.recording)
        }
        case(failWith(bridgeError(BridgeFailure.NETWORK)), VoiceErrorCode.HOST_UNREACHABLE)
        case(failWith(bridgeError(BridgeFailure.ASR_UNAVAILABLE, 503, "asr_unavailable", "ASR is loading")), VoiceErrorCode.ASR_UNAVAILABLE)
        case(failWith(bridgeError(BridgeFailure.SERVER_ERROR, 503, "daidai_unavailable")), VoiceErrorCode.DAIDAI_UNAVAILABLE)
        case(failWith(bridgeError(BridgeFailure.CLIENT_ERROR, 429, "busy")), VoiceErrorCode.BUSY)
        case(failWith(bridgeError(BridgeFailure.CLIENT_ERROR, 401)), VoiceErrorCode.HOST_UNREACHABLE)
        case(failWith(bridgeError(BridgeFailure.SERVER_ERROR, 500)), VoiceErrorCode.HOST_UNREACHABLE)
    }

    @Test
    fun createWatchdogIsFiveSeconds() {
        val r = Rig()
        r.bridge.createReply = { hang() }
        r.start()
        r.advance(4_999)
        assertEquals(VoicePhase.PREPARING, r.phase)
        r.advance(1)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.HOST_UNREACHABLE, r.last.error)
        assertTrue(r.bridge.aborts >= 1)
        assertEquals(0, r.audio.startCount)
    }

    @Test
    fun hostErrorEventsMapToCodes() {
        val table = mapOf(
            "no_speech" to VoiceErrorCode.NO_SPEECH, "asr_failed" to VoiceErrorCode.ASR_FAILED, "timeout" to VoiceErrorCode.TIMEOUT,
            "daidai_unavailable" to VoiceErrorCode.DAIDAI_UNAVAILABLE, "expired" to VoiceErrorCode.EXPIRED, "busy" to VoiceErrorCode.BUSY,
            "asr_unavailable" to VoiceErrorCode.ASR_UNAVAILABLE, "tts_failed" to VoiceErrorCode.REPLY_AUDIO_FAILED,
        )
        for ((code, expected) in table) {
            val r = Rig()
            r.startAndListen()
            r.talk(500)
            r.hostError(code, "host said something long")
            assertEquals(code, VoicePhase.ERROR, r.phase)
            assertEquals(code, expected, r.last.error)
            assertEquals(VoiceState.ERROR, r.last.state)
            assertFalse(r.audio.recording)
            assertEquals(listOf(false to expected.code), r.listener.ended)
            assertTrue("host message は診断の 1 行にだけ", r.last.diagnostics!!.contains("host said"))
        }
    }

    @Test
    fun unknownHostCodeIsGenericErrorWithNullCode() {
        for (code in listOf("weird_code", "mic_silent", "host_unreachable", "MIC_PERMISSION", "")) {
            val r = Rig()
            r.startAndListen()
            r.hostError(code, "msg")
            assertEquals(VoicePhase.ERROR, r.phase)
            assertNull("code='$code'", r.last.error)
            assertEquals(VoiceState.ERROR, r.last.state)
            val reason = r.listener.ended.single().second
            assertTrue(reason, reason.matches(Regex("host_[a-z0-9_]{1,32}")))
            assertNull(VoiceErrorCode.fromCode(reason))
        }
    }

    @Test
    fun hostMessageNeverBecomesUserFacingText() {
        val r = Rig()
        r.startAndListen()
        r.hostError("asr_failed", "<script>alert(1)</script>\u0007\n秘密")
        assertEquals(DaidaiText.errorMessage(VoiceErrorCode.ASR_FAILED), DaidaiText.errorMessage(r.last.error))
        val diag = r.last.diagnostics!!
        assertFalse(diag.contains('\n') || diag.contains('\u0007'))
        assertTrue(diag.length <= VoiceLimits.DIAGNOSTICS_MAX_CHARS)
        assertEquals("", r.last.reply)
    }

    @Test
    fun panelNoSpeechTimerStartsAtFirstAckNotAtMicOpen() {
        // 最初のチャンクは 400 ms に出る。ack は 2 秒後
        val r = Rig()
        r.bridge.chunkReply = { c -> ok(ChunkAck(AckState.LISTENING, c.seq), latencyMs = if (c.seq == 0) 1_500 else 0) }
        r.startAndListen()
        r.talk(400)
        r.talk(20) // 最初のチャンクを送る
        val firstAckAt = r.bridge.chunks.first().atMs + 1_500
        r.talk(1_480)
        // no_speech_s(10)+3 秒 = 13 秒を ack から数える
        r.advance(firstAckAt + 13_000 - r.sim.now - 1)
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.advance(1)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.NO_SPEECH, r.last.error)
        assertEquals(listOf(false to "no_speech"), r.listener.ended)
        assertFalse(r.audio.recording)
    }

    @Test
    fun speechClearsPanelNoSpeechTimer() {
        val r = Rig()
        r.startAndListen()
        r.talk(1_000)
        r.speech()
        assertEquals(VoicePhase.HEARING, r.phase)
        r.talk(20_000)
        assertEquals(VoicePhase.HEARING, r.phase)
        assertNull(r.last.error)
    }

    @Test
    fun micSilentAfterThreeSecondsOfZeros() {
        val r = Rig()
        r.startAndListen()
        r.talk(3_399, amp = 0)
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.talk(1, amp = 0)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.MIC_SILENT, r.last.error)
        assertFalse(r.audio.recording)
        assertEquals(listOf("s-1"), r.bridge.cancels)
    }

    @Test
    fun soundBeforeThreeSecondsPreventsMicSilent() {
        val r = Rig()
        r.startAndListen()
        r.talk(2_000, amp = 0)
        r.talk(20, amp = 500)
        r.talk(5_000, amp = 0)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertNull(r.last.error)
    }

    @Test
    fun noPcmAtAllAlsoCountsAsSilent() {
        val r = Rig()
        r.startAndListen()
        r.advance(3_400)
        assertEquals(VoiceErrorCode.MIC_SILENT, r.last.error)
    }

    @Test
    fun tinyNoiseBelowThresholdIsSilent() {
        val r = Rig()
        r.startAndListen()
        r.talk(3_400, amp = 1)
        assertEquals(VoiceErrorCode.MIC_SILENT, r.last.error)
        val r2 = Rig()
        r2.startAndListen()
        r2.talk(3_400, amp = 10)
        assertEquals(VoicePhase.LISTENING, r2.phase)
    }

    @Test
    fun micPermissionAndUnavailable() {
        fun case(setup: (FakeAudio) -> Unit, expected: VoiceErrorCode) {
            val r = Rig()
            setup(r.audio)
            r.start()
            assertEquals(VoicePhase.ERROR, r.phase)
            assertEquals(expected, r.last.error)
            assertFalse(r.audio.recording)
            assertFalse(r.last.micActive)
            assertEquals("作成済みのセッションは削除する", listOf("s-1"), r.bridge.cancels)
            assertTrue(r.listener.micOpenedAt.isEmpty())
        }
        case({ it.startError = VoiceErrorCode.MIC_PERMISSION }, VoiceErrorCode.MIC_PERMISSION)
        case({ it.startError = VoiceErrorCode.MIC_UNAVAILABLE }, VoiceErrorCode.MIC_UNAVAILABLE)
        case({ it.startThrows = SecurityException("no") }, VoiceErrorCode.MIC_PERMISSION)
        case({ it.startThrows = IllegalStateException("busy") }, VoiceErrorCode.MIC_UNAVAILABLE)
        case({ it.silentFailure = true }, VoiceErrorCode.MIC_UNAVAILABLE)
    }

    @Test
    fun micErrorMidSessionEndsButIsIgnoredAfterMicClosed() {
        val r = Rig()
        r.startAndListen()
        r.talk(200)
        r.audio.onError!!(VoiceErrorCode.MIC_UNAVAILABLE)
        r.sim.settle()
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, r.last.error)

        val r2 = Rig()
        r2.startAndListen()
        r2.waiting()
        r2.audio.onError!!(VoiceErrorCode.MIC_UNAVAILABLE)
        r2.sim.settle()
        assertEquals(VoicePhase.WAITING, r2.phase)
    }

    @Test
    fun doneBeforeReplyIsSessionLost() {
        val r = Rig()
        r.startAndListen()
        r.waiting()
        r.done()
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.SESSION_LOST, r.last.error)
    }

    @Test
    fun errorEventAfterReplyIsIgnored() {
        val r = Rig()
        r.startAndListen()
        r.reply("もらった")
        r.hostError("expired")
        assertEquals(VoicePhase.REPLYING, r.phase)
        assertNull(r.last.error)
    }

    @Test
    fun transcriptAfterReplyDoesNotChangeTheDisplay() {
        val r = Rig()
        r.startAndListen()
        r.transcript("前")
        r.reply("返事")
        r.transcript("後")
        assertEquals("前", r.last.transcript)
    }

    // ---------------------------------------------------------------- ERROR → IDLE / 再試行 / onEnded

    @Test
    fun errorShowsEightSecondsThenIdle() {
        val r = Rig()
        r.startAndListen()
        r.hostError("asr_failed")
        assertEquals(VoiceState.ERROR, r.last.state)
        r.advance(7_999)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceState.ERROR, r.last.state)
        r.advance(1)
        assertEquals(VoicePhase.IDLE, r.phase)
        assertEquals(VoiceSnapshot(), r.last)
        assertEquals("ERROR から IDLE へ戻るときは onEnded を呼ばない", 1, r.listener.ended.size)
    }

    @Test
    fun retryStartsANewSessionWithTheSameOriginButAsUserAction() {
        val r = Rig()
        r.start(StartReason.NOTIFICATION, "n-1", auto = true)
        r.hostError("busy")
        assertEquals(listOf(true to "busy"), r.listener.ended)
        r.advance(3_000)
        r.retry()
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(2, r.bridge.created.size)
        assertEquals(StartReason.NOTIFICATION, r.bridge.created[1].reason)
        assertEquals("n-1", r.bridge.created[1].noticeId)
        assertEquals(VoiceState.LISTENING, r.last.state)
        assertNull(r.last.error)
        r.cancel()
        assertEquals("再試行は利用者の操作", listOf(true to "busy", false to "cancelled"), r.listener.ended)
        assertEquals("古い ERROR の表示タイマーは効かない", VoiceState.IDLE, run { r.advance(10_000); r.last.state })
    }

    @Test
    fun retryIsIgnoredOutsideError() {
        val r = Rig()
        r.retry()
        assertEquals(0, r.bridge.created.size)
        r.startAndListen()
        r.retry()
        assertEquals(1, r.bridge.created.size)
    }

    @Test
    fun startFromErrorOrDoneBeginsFresh() {
        val r = Rig()
        r.startAndListen()
        r.reply("一回目", audio = null)
        assertEquals(VoicePhase.DONE, r.phase)
        r.start()
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals("", r.last.reply)
        assertEquals(2, r.bridge.created.size)
        assertEquals("新しいセッションは SSE の seq を 0 から数え直す", listOf<Int?>(null, null), r.bridge.eventOpens)
    }

    // ---------------------------------------------------------------- 絶対上限

    @Test
    fun hostLimitsAreClampedByPanelLimits() {
        val r = Rig()
        val huge = HostLimits(100_000, 100_000, 1_000_000_000_000L, 100_000, 100_000)
        r.bridge.createReply = { ok(SessionInfo("s-1", huge)) }
        r.startAndListen()
        r.talk(1_000)
        // 無発話: ack(400 ms)から 10 + 3 秒で終わる(100,000 秒ではない)
        r.talk(12_500)
        assertEquals(VoiceErrorCode.NO_SPEECH, r.last.error)

        val r2 = Rig()
        r2.bridge.createReply = { ok(SessionInfo("s-1", huge)) }
        r2.ackState = AckState.ENDED
        r2.startAndListen()
        r2.talk(440)
        r2.advance(FIRST_CHUNK_AT + 65_000 - r2.sim.now - 1) // 応答待ちの上限は 60 + 5 秒
        assertEquals(VoicePhase.WAITING, r2.phase)
        r2.advance(1)
        assertEquals(VoiceErrorCode.TIMEOUT, r2.last.error)
    }

    @Test
    fun smallerHostLimitsAreHonored() {
        val r = Rig()
        r.bridge.createReply = { ok(SessionInfo("s-1", HostLimits(noSpeechS = 2, replyTimeoutS = 3))) }
        r.startAndListen()
        r.talk(440) // 最初のチャンクは 400 ms に出る
        r.talk(5_000 - 20)
        assertEquals(VoiceErrorCode.NO_SPEECH, r.last.error)
    }

    @Test
    fun replyTextIsCappedWithoutSplittingGraphemes() {
        val r = Rig()
        r.startAndListen()
        val family = "👨‍👩‍👧" // 👨‍👩‍👧
        r.reply("a".repeat(1_999) + family + "tail", audio = null)
        assertEquals("a".repeat(1_999), r.last.reply)
        val r2 = Rig()
        r2.startAndListen()
        r2.reply("b".repeat(5_000), audio = null)
        assertEquals(VoiceLimits.REPLY_TEXT_MAX_CHARS, r2.last.reply.length)
    }

    @Test
    fun textsAreCleanedAndTranscriptCapped() {
        val r = Rig()
        r.startAndListen()
        r.transcript("a\u0007b‮c" + "x".repeat(1_000))
        assertEquals(DaidaiText.MAX_TRANSCRIPT, r.last.transcript.length)
        assertFalse(r.last.transcript.contains('\u0007'))
        assertFalse(r.last.transcript.contains('‮'))
    }

    @Test
    fun poseIsValidated() {
        val r = Rig()
        r.startAndListen()
        r.reply("a", audio = null, pose = "<b>x</b>")
        assertNull(r.last.noticePose)
        val r2 = Rig()
        r2.startAndListen()
        r2.reply("a", audio = null, pose = "working")
        assertEquals("working", r2.last.noticePose)
    }

    // ---------------------------------------------------------------- 表示と micActive

    @Test
    fun micActiveFollowsTheActualRecordingState() {
        val r = Rig()
        r.start()
        assertTrue(r.listener.snaps.first().let { !it.micActive })
        assertTrue(r.last.micActive)
        assertEquals(VoiceState.LISTENING, r.last.state)
        r.ackState = AckState.ENDED
        r.talk(440)
        assertFalse(r.last.micActive)
        assertEquals(VoiceState.WAITING, r.last.state)
    }

    @Test
    fun snapshotsAreNotRepeated() {
        val r = Rig()
        r.startAndListen()
        r.talk(2_000)
        val zipped = r.listener.snaps.zipWithNext()
        assertTrue(zipped.all { (a, b) -> a != b })
    }

    @Test
    fun preparingIsShownAsIdleAndDoneAsIdle() {
        val r = Rig()
        r.bridge.createReply = { ok(SessionInfo("s-1", r.bridge.hostLimits), 500) }
        r.start()
        assertEquals(VoicePhase.PREPARING, r.phase)
        assertTrue(r.listener.snaps.all { it.state == VoiceState.IDLE })
    }

    @Test
    fun toStringsDoNotLeakText() {
        val secret = "ひみつの認識結果"
        val snap = VoiceSnapshot(VoiceState.REPLYING, transcript = secret, reply = secret)
        assertFalse(snap.toString().contains(secret))
        assertFalse(BridgeEvent.Transcript(1, secret, true).toString().contains(secret))
        assertFalse(BridgeEvent.Reply(1, 0, secret, null, null, true, null).toString().contains(secret))
        assertFalse(BridgeEvent.Error(1, "code", secret).toString().contains(secret))
        val ex = BridgeException(BridgeFailure.CLIENT_ERROR, 400, "bad", secret)
        assertFalse(ex.message!!.contains(secret))
        assertFalse(SessionInfo("s-secret-id", HostLimits()).toString().contains("s-secret-id"))
    }

    @Test
    fun onMicOpenedFiresOncePerSessionAfterMicIsRecording() {
        val r = Rig()
        r.bridge.createReply = { n -> ok(SessionInfo("s-$n", r.bridge.hostLimits), 700) }
        r.start()
        assertTrue(r.listener.micOpenedAt.isEmpty())
        r.advance(700)
        assertEquals(listOf(700L), r.listener.micOpenedAt)
        assertTrue(r.audio.recording)
    }

    // ---------------------------------------------------------------- 録音を止めたあとはチャンクの期限を持ち越さない

    @Test
    fun chunkWatchdogDoesNotOutliveTheMicPhase() {
        // 最後のチャンクの応答が来ないまま、SSE の waiting と reply が先に届く。4 秒後の期限で通信を切ってはいけない
        val r = Rig()
        r.bridge.chunkReply = { hang() }
        r.bridge.fetchReply = { ok(ByteArray(2000), latencyMs = 5_000) }
        r.startAndListen()
        r.talk(440)
        r.waiting()
        r.reply()
        assertEquals(VoicePhase.REPLYING, r.phase)
        r.advance(4_500) // 最初のチャンクの 4 秒を過ぎる
        assertEquals("録音を止めたあとにウォッチドッグで切らない", 0, r.bridge.aborts)
        assertEquals("返答音声の取得を巻き込まない", VoicePhase.REPLYING, r.phase)
        r.advance(1_000)
        assertEquals(1, r.player.plays)
        assertNull(r.last.error)
        assertEquals(1, r.bridge.eventOpens.size)
    }

    @Test
    fun chunkWatchdogDoesNotCutSseWhileWaiting() {
        val r = Rig()
        r.bridge.chunkReply = { hang() }
        r.startAndListen()
        r.talk(440)
        r.waiting()
        r.advance(10_000)
        assertEquals(0, r.bridge.aborts)
        assertEquals(1, r.bridge.eventOpens.size)
        assertEquals(VoicePhase.WAITING, r.phase)
    }
}
