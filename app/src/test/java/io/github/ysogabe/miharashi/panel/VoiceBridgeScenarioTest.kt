package io.github.ysogabe.miharashi.panel

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.PriorityQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

/**
 * `VoiceSession` + `BridgeClient` + MockWebServer による通信シナリオ試験(設計書 §5.2.8 の S1〜S15、AC15)。
 *
 * 通信は**本物の HTTP**(ワーカースレッド上のブロッキング I/O)で、時間だけを仮想化する: セッションのスレッドはテストのスレッドで、
 * [LiveSim] の時刻は `advance` でしか進まない(45 秒・50 秒のタイマーも実時間では待たない)。
 *
 * MockWebServer 4.x は HTTP/1.1 の応答を時間をかけて流せないので、SSE の 1 本の接続を「区間」として扱う: [ScriptHost] は、
 * 流すべきイベントができるまで応答を保留し、できたら溜まった分を返して閉じる(= 終端なしの区間の終わりは VoiceSession の再接続になる)。
 * `Last-Event-ID` があればその次から再生する(S6)。
 */
class VoiceBridgeScenarioTest {
    private val envs = mutableListOf<Env>()
    private val others = mutableListOf<MockWebServer>()

    @After fun tearDown() {
        envs.forEach { it.close() }
        others.forEach { it.shutdown() }
    }

    private fun env(guardMs: Long = 0L, sessionMaxMs: Long = VoiceLimits.SESSION_MAX_MS, hold: Boolean = false, host: ScriptHost = ScriptHost()) =
        Env(host, guardMs, sessionMaxMs, hold).also { envs += it }

    // =========================================================================================
    // S1 正常: 作成 → チャンク → ended → reply → 音声 → done

    @Test fun s1_normalRoundTrip() {
        val e = env()
        val wav = WavFixtures.valid(ms = 500, rate = 24_000)
        e.host.replyWav = wav
        e.start()
        e.talk(200)
        e.host.ackState = "speech"
        e.talk(200)
        e.host.ackState = "ended"
        e.talkUntil("ended で応答待ちへ") { e.session.phase == VoicePhase.WAITING }
        assertFalse("ended でマイクを止める", e.audio.recording)
        e.host.emitAll(
            evt("state") { put("state", "speech") },
            evt("transcript") { put("text", "今日の天気は").put("final", true) },
            evt("state") { put("state", "waiting") },
            evt("reply") { replyJson(wav.size) },
            evt("done") { },
        )
        e.sim.await("音声を再生する") { e.player.plays == 1 }
        assertEquals("取得した WAV がそのまま再生に渡る", wav.toList(), e.player.lastWav!!.toList())
        assertEquals(VoiceState.REPLYING, e.listener.last.state)
        e.player.finish(true)
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        assertEquals(listOf(false to "done"), e.listener.ended)
        assertEquals(
            listOf(VoiceState.IDLE, VoiceState.LISTENING, VoiceState.HEARING, VoiceState.WAITING, VoiceState.REPLYING, VoiceState.IDLE),
            e.listener.states(),
        )
        assertEquals("今日の天気は", e.listener.snaps.first { it.transcript.isNotEmpty() }.transcript)
        assertEquals("こんにちは", e.listener.last.reply)
        assertNull(e.listener.last.error)
        // 終了時に DELETE(認識テキストを早く手放させる)
        e.sim.await("DELETE") { e.host.deletes.isNotEmpty() }
        assertEquals(listOf("/v1/voice/sessions/s-1"), e.host.deletes.toList())
        // チャンク: seq は 0 から連続、長さは 640〜16,000、final なし(ended はホストが決めた)
        val chunks = e.host.audioReqs.toList()
        assertTrue(chunks.size >= 2)
        assertEquals((0 until chunks.size).toList(), chunks.map { it.seq }.sorted().distinct())
        assertTrue(chunks.all { it.bytes in VoiceLimits.CHUNK_MIN_BYTES..VoiceLimits.CHUNK_MAX_BYTES })
        assertTrue(chunks.none { it.final })
        e.assertOnlyBridgeBearer()
        // 要求の順序: 作成 → (SSE とチャンク) → 音声取得 → DELETE
        val paths = e.host.requests.map { it.method + " " + it.path!!.substringBefore("?") }
        assertEquals("POST /v1/voice/sessions", paths.first())
        assertTrue(paths.contains("GET /v1/voice/sessions/s-1/events"))
        assertTrue(paths.indexOf("GET /v1/voice/sessions/s-1/replies/0/audio") < paths.indexOf("DELETE /v1/voice/sessions/s-1"))
    }

    // =========================================================================================
    // S2 最後のチャンクで ended を返したが ack が失われ、Panel が再送 → 同じ ack

    @Test fun s2_lostEndedAckIsResentAndTheSameAckComesBack() {
        val e = env()
        e.start()
        e.talk(200)
        e.host.ackState = "speech"
        e.talk(100)
        // 次のチャンクをホストは受理して ended と判定するが、応答は届かない。再送(同じ seq・同じ本文)には、同じ ack を返す
        val lostSeq = AtomicInteger(-1)
        val cache = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
        val mismatch = java.util.concurrent.atomic.AtomicBoolean(false)
        e.host.onChunk = { r ->
            val prev = cache[r.seq]
            if (prev == null) {
                cache[r.seq] = r.body
                lostSeq.compareAndSet(-1, r.seq)
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            } else {
                if (!prev.contentEquals(r.body)) mismatch.set(true)
                MockResponse().setBody("""{"state":"ended","accepted_seq":${r.seq}}""")
            }
        }
        e.talkUntil("再送を経て ended で応答待ちへ") { e.session.phase == VoicePhase.WAITING }
        val lost = lostSeq.get()
        // 送信 API の呼び出し回数と HTTP 要求数を区別する: JDK の暗黙の再送が無ければ、再送は VoiceSession の 1 回だけ(合計ちょうど 2 要求)
        assertEquals("送信 API の呼び出しは元の 1 回 + 再送の 1 回", 2, e.spy.sendCalls.count { it == lost })
        assertEquals("同じ seq の HTTP 要求は合計ちょうど 2 回(JDK の自動再送が重ならない)", 2, e.host.audioReqs.count { it.seq == lost })
        assertFalse("再送は同じ本文", mismatch.get())
        assertEquals("エラーにならない", null, e.listener.snaps.firstOrNull { it.state == VoiceState.ERROR })
        assertFalse(e.audio.recording)
        e.host.emitAll(evt("reply") { replyJson(e.host.replyWav.size) }, evt("done") { })
        e.sim.await("再生") { e.player.plays == 1 }
        e.player.finish(true)
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        assertEquals("done", e.listener.ended.single().second)
    }

    @Test fun s2_ifTheResendAlsoFailsTheSessionEndsWithoutAThirdRequest() {
        val e = env()
        e.start()
        e.talk(200)
        e.host.ackState = "speech"
        e.talk(100)
        val failSeq = AtomicInteger(-1)
        e.host.onChunk = { r ->
            failSeq.compareAndSet(-1, r.seq)
            if (r.seq == failSeq.get()) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else null
        }
        e.talkUntil("再送も失敗して終了") { e.listener.ended.isNotEmpty() }
        e.sim.pumpFor(300) // 遅れて 3 回目が出ていないことを見る
        val seq = failSeq.get()
        assertEquals("host_unreachable", e.listener.ended.single().second)
        assertEquals("送信 API の呼び出しは 2 回で打ち切る", 2, e.spy.sendCalls.count { it == seq })
        assertEquals("HTTP 要求も合計ちょうど 2 回(3 回目を送らない)", 2, e.host.audioReqs.count { it.seq == seq })
        assertFalse(e.audio.recording)
    }

    // =========================================================================================
    // S3 ended 後に新しい seq → 409 ended は正常終了

    @Test fun s3_conflictEndedOnANewSeqIsANormalEnd() {
        val e = env()
        e.start()
        e.talk(200)
        e.host.ackState = "speech"
        e.talk(100)
        e.host.onChunk = { MockResponse().setResponseCode(409).setBody("""{"error":{"code":"ended","message":"session already ended"}}""") }
        e.talkUntil("409 ended で応答待ちへ") { e.session.phase == VoicePhase.WAITING }
        val conflicted = e.host.audioReqs.last()
        assertEquals("409 は再送しない", 1, e.host.audioReqs.count { it.seq == conflicted.seq })
        assertTrue("エラー表示にならない", e.listener.snaps.none { it.state == VoiceState.ERROR })
        assertFalse(e.audio.recording)
        e.host.emitAll(evt("reply") { replyJson(e.host.replyWav.size) }, evt("done") { })
        e.sim.await("再生") { e.player.plays == 1 }
        e.player.finish(true)
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        assertEquals(listOf(false to "done"), e.listener.ended)
    }

    // =========================================================================================
    // S4 state=waiting(SSE)が ended の ack より先に届く

    @Test fun s4_sseWaitingBeforeTheEndedAck() {
        val e = env()
        e.start()
        e.talk(200)
        e.host.ackState = "speech"
        e.talk(100)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        e.host.onChunk = { r ->
            e.host.onChunk = null
            e.host.emitAll(evt("state") { put("state", "speech") }, evt("state") { put("state", "waiting") })
            arrived.countDown()
            e.host.waitFor(release)
            MockResponse().setBody("""{"state":"ended","accepted_seq":${r.seq}}""")
        }
        val retryTimersBefore = e.sim.postedDelays.count { it == 500L }
        e.talk(100, settle = false) // 次のチャンクをホストが受けて、SSE の waiting を先に流し、ack は保留する
        assertTrue(arrived.await(3, TimeUnit.SECONDS))
        e.sim.await("SSE の waiting で応答待ちへ(ack はまだ)") { e.session.phase == VoicePhase.WAITING }
        assertFalse("録音を止めた", e.audio.recording)
        release.countDown()
        e.sim.pumpFor(150) // 後着の ack は無視される(状態は WAITING のまま、エラーにもならない)
        assertEquals(VoicePhase.WAITING, e.session.phase)
        assertTrue(e.listener.snaps.none { it.state == VoiceState.ERROR })
        // SSE の区間は終端なしで閉じた → 再接続(Last-Event-ID は最後に見た seq)
        e.sim.awaitDelayedPost(500, "再接続のタイマー", retryTimersBefore) { }
        e.sim.advance(500)
        e.sim.await("再接続") { e.host.eventRequests.size >= 2 }
        assertEquals("1", e.host.eventRequests.last())
        e.host.emitAll(evt("reply") { replyJson(e.host.replyWav.size) }, evt("done") { })
        e.sim.await("再生") { e.player.plays == 1 }
        e.player.finish(true)
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        assertEquals("done", e.listener.ended.single().second)
    }

    // =========================================================================================
    // S5 reply 受信後に、遅れて speech の ack が届く → 後退しない

    @Test fun s5_lateSpeechAckAfterReplyIsIgnored() {
        val e = env()
        e.start()
        e.talk(200)
        e.host.ackState = "listening"
        val release = CountDownLatch(1)
        val arrived = CountDownLatch(1)
        e.host.onChunk = { r ->
            e.host.onChunk = null
            e.host.emitAll(
                evt("state") { put("state", "waiting") },
                evt("reply") { replyJson(e.host.replyWav.size) },
                evt("done") { },
            )
            arrived.countDown()
            e.host.waitFor(release)
            MockResponse().setBody("""{"state":"speech","accepted_seq":${r.seq}}""")
        }
        e.talk(100, settle = false)
        assertTrue(arrived.await(3, TimeUnit.SECONDS))
        e.sim.await("reply で再生へ") { e.session.phase == VoicePhase.REPLYING }
        e.sim.await("再生") { e.player.plays == 1 }
        val before = e.listener.snaps.size
        release.countDown()
        e.sim.pumpFor(150)
        assertEquals("後着の speech の ack で後退しない", VoicePhase.REPLYING, e.session.phase)
        assertTrue(e.listener.snaps.drop(before).none { it.state == VoiceState.HEARING || it.state == VoiceState.LISTENING })
        e.player.finish(true)
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        assertEquals("done", e.listener.ended.single().second)
        assertEquals(1, e.player.plays)
    }

    // =========================================================================================
    // S6 SSE の切断 → Last-Event-ID で再接続、重複は無視

    @Test fun s6_sseCutThenReconnectWithLastEventIdAndDuplicatesIgnored() {
        val e = env()
        e.start()
        e.talk(200)
        e.host.ackState = "ended"
        e.talkUntil("応答待ち") { e.session.phase == VoicePhase.WAITING }
        // 区間 1: transcript まで(reply の前で切れる)
        val retryTimersBefore = e.sim.postedDelays.count { it == 500L }
        e.host.emitAll(evt("state") { put("state", "speech") }, evt("transcript") { put("text", "やあ").put("final", true) })
        e.sim.await("transcript を受け取る") { e.listener.snaps.any { it.transcript == "やあ" } }
        // 区間 1 が閉じ、クライアントが EOF を処理して再接続のタイマー(最初の待ちは 500 ms)を登録し終えるのを待ってから、仮想時間を進める
        e.sim.awaitDelayedPost(500, "区間 1 が閉じて再接続のタイマーが登録される", retryTimersBefore) { }
        assertEquals(1, e.host.eventRequests.size)
        // ホストは reply を二重に記録する(重複の再現)
        e.host.emitAll(evt("reply") { replyJson(e.host.replyWav.size) })
        e.host.addDuplicateOfLast()
        e.host.emitAll(evt("done") { })
        e.sim.advance(500)
        e.sim.await("再生") { e.player.plays == 1 }
        assertEquals("Last-Event-ID は最後に見た seq(transcript の 1)", listOf<String?>(null, "1"), e.host.eventRequests.toList())
        e.player.finish(true)
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        e.sim.pumpFor(100)
        assertEquals("重複した reply で 2 回再生しない", 1, e.player.plays)
        assertEquals(listOf(false to "done"), e.listener.ended)
        assertEquals("音声の取得も 1 回", 1, e.host.requests.count { it.path!!.endsWith("/replies/0/audio") })
    }

    /**
     * reply を受け取ったあと、done の前に区間が閉じる。reply を受け取った後は再接続しない(返答はもう手元にあり、再生して DONE に至る)。
     * 後から done が来ても使わない。重複して二度再生したり、再接続のために DONE が遅れたりしない。
     */
    @Test fun s6_sseCutAfterReplyBeforeDoneDoesNotReconnectAndStillFinishes() {
        val e = env()
        e.start()
        e.talk(200)
        e.host.ackState = "ended"
        e.talkUntil("応答待ち") { e.session.phase == VoicePhase.WAITING }
        e.host.emitAll(evt("reply") { replyJson(e.host.replyWav.size) }) // done は無い。区間はここで閉じる
        e.sim.await("再生") { e.player.plays == 1 }
        e.sim.await("区間が閉じる") { e.host.segmentsClosed.get() >= 1 }
        e.sim.pumpFor(200)
        e.sim.advance(4_000) // 再接続の待ち(0.5 → 1 → 2 秒)をすべて過ぎても(再生の期限 5.5 秒の手前)
        e.sim.pumpFor(200)
        assertEquals("再接続しない", 1, e.host.eventRequests.size)
        assertEquals(0, e.sim.postedDelays.count { it == 500L })
        assertEquals(VoicePhase.REPLYING, e.session.phase)
        assertTrue("エラーにならない", e.listener.snaps.none { it.state == VoiceState.ERROR })
        // 遅れて done がホストに記録されても(購読していないので)何も起きない
        e.host.emitAll(evt("done") { })
        e.sim.pumpFor(100)
        e.player.finish(true)
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        assertEquals(listOf(false to "done"), e.listener.ended)
        assertEquals(1, e.player.plays)
        assertEquals("音声の取得も 1 回", 1, e.host.requests.count { it.path!!.endsWith("/replies/0/audio") })
        assertEquals("最後まで再接続しない", 1, e.host.eventRequests.size)
    }

    // =========================================================================================
    // S7 作成の応答が、離脱の後に届く → 録音せず直ちに DELETE

    @Test fun s7_createResponseArrivingAfterLeavingIsDeletedWithoutRecording() {
        val e = env(hold = true)
        e.session.start(StartReason.SWIPE, null, false)
        e.sim.pump()
        assertTrue("作成の応答はクライアントまで届いている", e.spy.createReturned.await(5, TimeUnit.SECONDS))
        e.session.cancel() // B を離れる(作成の結果はまだセッションに届いていない)
        e.sim.pumpFor(50)
        assertEquals(VoicePhase.IDLE, e.session.phase)
        e.spy.holdCreate!!.countDown() // いまセッションに届く
        e.sim.await("直ちに DELETE") { e.host.deletes.isNotEmpty() }
        assertEquals(listOf("/v1/voice/sessions/s-1"), e.host.deletes.toList())
        assertEquals("録音を始めない", 0, e.audio.startCount)
        assertTrue(e.listener.micOpenedAt.isEmpty())
        assertEquals(listOf(false to "cancelled"), e.listener.ended)
        assertEquals("音声もイベントも要求しない", 0, e.host.audioReqs.size)
        assertEquals(0, e.host.eventRequests.size)
    }

    @Test fun s7_leavingWhileCreateIsInFlightAbortsItAndNeverRecords() {
        val e = env()
        val gate = CountDownLatch(1)
        e.host.createGate = gate
        e.session.start(StartReason.SWIPE, null, false)
        e.sim.pump()
        e.sim.await("作成の要求がホストに届く") { e.host.requests.isNotEmpty() }
        e.session.cancel()
        e.sim.pumpFor(50)
        assertEquals(VoicePhase.IDLE, e.session.phase)
        gate.countDown() // ホストはいま応答を出せる(中断済みの接続には届かない)
        e.sim.pumpFor(900) // 応答が出せるようになったあとも
        assertEquals("録音を始めない", 0, e.audio.startCount)
        assertEquals("作成が中断されたので id は分からず、DELETE も出せない(ホスト側の idle_expire で破棄される)", 0, e.host.deletes.size)
        assertEquals(0, e.host.eventRequests.size)
        assertEquals(listOf(false to "cancelled"), e.listener.ended)
    }

    // =========================================================================================
    // S8 ended 後にホストが沈黙 → reply_timeout_s + 5 秒で timeout

    @Test fun s8_hostSilentAfterEndedTimesOutAtReplyTimeoutPlusFive() {
        val e = env()
        e.start()
        e.talk(200)
        e.host.ackState = "ended"
        e.talkUntil("応答待ち") { e.session.phase == VoicePhase.WAITING }
        val waitingSince = e.listener.timeOf { it.state == VoiceState.WAITING }
        e.host.holdEvents = true // ホストは何も言わない
        e.sim.advance(waitingSince + 50_000 - 1 - e.sim.nowMs())
        assertEquals("45 + 5 秒の直前まで待つ", VoicePhase.WAITING, e.session.phase)
        e.sim.advance(1)
        e.sim.pumpFor(50)
        assertEquals(VoicePhase.ERROR, e.session.phase)
        assertEquals(VoiceErrorCode.TIMEOUT, e.listener.last.error)
        assertEquals("timeout", e.listener.ended.single().second)
        e.sim.await("DELETE") { e.host.deletes.isNotEmpty() }
    }

    // =========================================================================================
    // S9 ホストが ended を返し続けない → 45 秒でマイクを止める

    @Test fun s9_micStopsAt45SecondsWhenHostNeverEnds() {
        val e = env()
        e.start()
        e.host.ackState = "speech"
        assertEquals(0L, e.sim.nowMs())
        e.talkUntil("45 秒でマイクが止まる", maxMs = 46_000, block = 500) { e.audio.stopTimes.isNotEmpty() }
        assertEquals("マイクを開いて 45 秒ちょうどで止める", 45_000L, e.audio.stopTimes.first())
        assertEquals(VoicePhase.WAITING, e.session.phase)
        e.sim.await("最後のチャンク") { e.host.audioReqs.any { it.final } }
        val chunks = e.host.audioReqs.toList()
        assertEquals("最後のチャンクだけ final", 1, chunks.count { it.final })
        assertTrue(chunks.last().final)
        val bytes = chunks.sumOf { it.bytes.toLong() }
        assertTrue("45 秒ぶんほぼ全部を送った($bytes バイト)", bytes in (44L * 32_000)..(46L * 32_000))
        assertTrue(chunks.all { it.bytes <= VoiceLimits.CHUNK_MAX_BYTES })
    }

    // =========================================================================================
    // S10 応答にホスト指定のパス・別ホストの URL・3xx → 使わない / 追従しない

    @Test fun s10_hostSuppliedUrlsAndPathsAreNeverUsed() {
        val evil = MockWebServer().apply { start() }.also { others += it }
        val e = env()
        val wav = e.host.replyWav
        e.host.createExtra = ",\"audio_url\":\"${evil.url("/a")}\",\"events_url\":\"${evil.url("/e")}\",\"path\":\"/x\""
        e.start()
        e.talk(200)
        e.host.ackState = "ended"
        e.talkUntil("応答待ち") { e.session.phase == VoicePhase.WAITING }
        e.host.emitAll(
            evt("reply") {
                put("index", 0).put("text", "返答").put("final", true)
                put("audio", JSONObject().put("format", "wav").put("sample_rate", 24_000).put("bytes", wav.size).put("duration_ms", 500).put("url", evil.url("/steal").toString()).put("path", "/other/path"))
                put("audio_url", evil.url("/steal2").toString())
            },
            evt("done") { },
        )
        e.sim.await("再生") { e.player.plays == 1 }
        e.player.finish(true)
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        assertEquals("別ホストには一度も接続しない", 0, evil.requestCount)
        val ok = Regex("^(POST|GET|DELETE) /v1/voice/sessions(/s-1(/(audio|events|replies/0/audio))?)?$")
        assertTrue(e.host.requests.map { it.method + " " + it.path }.all { ok.matches(it) })
    }

    @Test fun s10_malformedSessionIdInCreateResponseFailsTheCreation() {
        for (bad in listOf("../../etc", "a/b", "x".repeat(65), "http://evil/x")) {
            val e = env()
            e.host.createHandler = { MockResponse().setResponseCode(201).setBody(JSONObject().put("session_id", bad).toString()) }
            e.session.start(StartReason.SWIPE, null, false)
            e.sim.await("失敗") { e.listener.ended.isNotEmpty() }
            assertEquals("host_unreachable", e.listener.ended.single().second)
            assertEquals("録音しない", 0, e.audio.startCount)
            assertEquals("作成のあとに何も要求しない", 1, e.host.requests.size)
        }
    }

    @Test fun s10_redirectOnReplyAudioIsNotFollowed() {
        val evil = MockWebServer().apply { start() }.also { others += it }
        val e = env()
        e.host.replyAudioHandler = { MockResponse().setResponseCode(302).setHeader("Location", evil.url("/steal.wav").toString()) }
        e.start()
        e.talk(200)
        e.host.ackState = "ended"
        e.talkUntil("応答待ち") { e.session.phase == VoicePhase.WAITING }
        e.host.emitAll(evt("reply") { replyJson(e.host.replyWav.size) }, evt("done") { })
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        assertEquals("reply_audio_failed", e.listener.ended.single().second)
        assertEquals("テキストは残る", "こんにちは", e.listener.last.reply)
        assertEquals(VoiceErrorCode.REPLY_AUDIO_FAILED, e.listener.last.error)
        assertEquals(0, e.player.plays)
        assertEquals("追従しない(トークンも送らない)", 0, evil.requestCount)
    }

    @Test fun s10_redirectOnAChunkEndsTheSessionWithoutFollowing() {
        val evil = MockWebServer().apply { start() }.also { others += it }
        val e = env()
        e.start()
        e.host.onChunk = { MockResponse().setResponseCode(307).setHeader("Location", evil.url("/audio").toString()) }
        e.talkUntil("失敗") { e.listener.ended.isNotEmpty() }
        assertEquals("host_unreachable", e.listener.ended.single().second)
        assertFalse(e.audio.recording)
        assertEquals(0, evil.requestCount)
        assertEquals("307 は再送もしない", 1, e.host.audioReqs.count { it.seq == 0 })
    }

    // =========================================================================================
    // S11 reply.audio が null(tts_failed)→ テキストだけ表示して DONE

    @Test fun s11_audioNullShowsTextAndFinishes() {
        val e = env()
        e.start()
        e.talk(200)
        e.host.ackState = "ended"
        e.talkUntil("応答待ち") { e.session.phase == VoicePhase.WAITING }
        e.host.emitAll(
            evt("reply") { put("index", 0).put("text", "声は出せません").put("audio", JSONObject.NULL).put("audio_error", "tts_failed").put("final", true) },
            evt("done") { },
        )
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        assertEquals("reply_audio_failed", e.listener.ended.single().second)
        assertEquals("声は出せません", e.listener.last.reply)
        assertEquals(VoiceErrorCode.REPLY_AUDIO_FAILED, e.listener.last.error)
        assertEquals(0, e.player.plays)
        assertEquals("音声は取りに行かない", 0, e.host.requests.count { it.path!!.contains("/replies/") })
    }

    // =========================================================================================
    // S12 同じ client.id で新しいセッション(置き換えはホスト側の契約。Panel は client.id を固定で送る)

    @Test fun s12_panelAlwaysSendsTheSameClientId() {
        val e = env()
        e.start()
        e.session.cancel()
        e.sim.pumpFor(100)
        e.sim.await("1 つ目の DELETE") { e.host.deletes.size == 1 }
        e.start()
        assertEquals(VoicePhase.LISTENING, e.session.phase)
        e.sim.await("2 つ目の SSE") { e.host.requests.any { it.path == "/v1/voice/sessions/s-2/events" } }
        val creates = e.host.requests.filter { it.method == "POST" && it.path == "/v1/voice/sessions" }
        assertEquals(2, creates.size)
        for (c in creates) {
            val o = JSONObject(c.body.clone().readUtf8())
            assertEquals("echo-show-5", o.getJSONObject("client").getString("id"))
            assertEquals("0.3.0", o.getJSONObject("client").getString("app_version"))
        }
        assertEquals(listOf("/v1/voice/sessions/s-1"), e.host.deletes.toList())
    }

    // =========================================================================================
    // S13 ack が遅い(入力 6 回ぶん待つ)→ 送信キューがあふれず、まとめ送りで録音に追いつく。入力は欠けず・順番を変えずに届く

    @Test fun s13_slowAcksAreCoalescedAndNeverOverflow() {
        val e = env()
        e.start()
        e.host.ackState = "speech"
        // ack の解放をテストが制御する(実時間の sleep や通信の遅さに依存しない)
        val gate = java.util.concurrent.Semaphore(0)
        e.host.onChunk = {
            while (!gate.tryAcquire(20, TimeUnit.MILLISECONDS)) if (e.host.closed.count == 0L) break
            null // 既定の ack(speech)
        }
        val stepBytes = 1_600 // 50 ms
        val steps = 24 // 1.2 秒ぶん
        val ackEvery = 6 // 300 ms ぶんの入力ごとに 1 つの ack を返す
        val fed = java.io.ByteArrayOutputStream()
        var maxBacklog = 0
        repeat(steps) { i ->
            // 全体で通し番号の付いたサンプル(順番が入れ替わる・欠けるとバイト列が合わない)
            val pcm = ByteArray(stepBytes)
            for (k in 0 until stepBytes / 2) {
                val v = (fed.size() / 2 + k) % 30_000 + 1
                pcm[2 * k] = (v and 0xFF).toByte()
                pcm[2 * k + 1] = ((v shr 8) and 0xFF).toByte()
            }
            fed.write(pcm)
            e.audio.feedBytes(pcm)
            e.sim.advance(50)
            val backlog = fed.size() - e.spy.sentBytes.get() // まだ送り出していない(キューに溜まっている)バイト数
            maxBacklog = maxOf(maxBacklog, backlog)
            if ((i + 1) % ackEvery == 0) {
                val started = e.spy.sendStarted.get()
                gate.release() // 送信中のチャンクの ack を返す → 溜まった分が 1 つの要求になる
                e.sim.await("溜まった分を次の 1 チャンクで送る") { e.spy.sendStarted.get() == started + 1 }
                assertEquals("まとめ送りで滞留が空になる", 0, fed.size() - e.spy.sentBytes.get())
            }
        }
        assertTrue("滞留は ack 6 回ぶんの入力以内($maxBacklog バイト)で、増え続けない", maxBacklog <= (ackEvery + 1) * stepBytes)
        assertTrue("上限(2 秒分)よりずっと下", maxBacklog < VoiceLimits.QUEUE_MAX_BYTES / 4)
        gate.release(1_000) // 残りの ack を一気に返す
        e.sim.await("入力がすべてホストに届く") { e.host.audioReqs.sumOf { it.bytes } == fed.size() && e.spy.sendStarted.get() == e.spy.sendCompleted.get() }
        e.sim.pumpFor(50)
        assertEquals(VoicePhase.HEARING, e.session.phase)
        assertTrue("あふれて終わっていない", e.listener.ended.isEmpty())
        assertEquals("同時に 1 リクエストだけ(直列)", 1, e.host.maxConcurrentAudio.get())
        val reqs = e.host.audioReqs.toList()
        assertEquals("seq は連続で再送もない", (0 until reqs.size).toList(), reqs.map { it.seq })
        assertEquals("入力全体が同じバイト列・同じ順序で届く", fed.toByteArray().toList(), reqs.flatMap { it.body.toList() })
        assertTrue("まとめ送り: 要求の数(${reqs.size})は 50 ms の入力の数($steps)より少ない", reqs.size <= steps / 2)
        assertTrue("ack を待つ間に溜まった分が 1 つの要求になる", reqs.drop(1).any { it.bytes > 4 * stepBytes })
        assertTrue(reqs.all { it.bytes in VoiceLimits.CHUNK_MIN_BYTES..VoiceLimits.CHUNK_MAX_BYTES })
    }

    // =========================================================================================
    // S14 送信が詰まり 2 秒分を超える → host_unreachable で終了、マイクを止める

    @Test fun s14_stalledSendOverflowsTheQueueAndEndsWithHostUnreachable() {
        val e = env()
        e.start()
        e.host.onChunk = {
            e.host.waitFor(e.host.neverReleased) // 応答しない
            MockResponse().setResponseCode(500)
        }
        e.talkUntil("キューがあふれて終了", maxMs = 3_900, block = 500, settle = false) { e.listener.ended.isNotEmpty() }
        assertEquals("host_unreachable", e.listener.ended.single().second)
        assertEquals(VoiceErrorCode.HOST_UNREACHABLE, e.listener.last.error)
        assertFalse("マイクを止めた", e.audio.recording)
        assertTrue("チャンクのウォッチドッグ(4 秒)より前", e.sim.nowMs() < 4_000)
        e.sim.await("DELETE") { e.host.deletes.isNotEmpty() }
    }

    // =========================================================================================
    // S15 WAV が壊れている / 10 MB 超 / 120 秒超 → 破棄してテキストだけ表示

    private fun replyWithBody(body: ByteArray, type: String = "audio/wav") {
        val e = env()
        e.host.replyAudioHandler = { MockResponse().setHeader("Content-Type", type).setBody(okio.Buffer().write(body)) }
        e.start()
        e.talk(200)
        e.host.ackState = "ended"
        e.talkUntil("応答待ち") { e.session.phase == VoicePhase.WAITING }
        e.host.emitAll(evt("reply") { replyJson(2_000) }, evt("done") { })
        e.sim.await("DONE") { e.listener.ended.isNotEmpty() }
        assertEquals("reply_audio_failed", e.listener.ended.single().second)
        assertEquals("テキストだけ表示する", "こんにちは", e.listener.last.reply)
        assertEquals(VoiceErrorCode.REPLY_AUDIO_FAILED, e.listener.last.error)
        assertEquals("再生しない", 0, e.player.plays)
    }

    @Test fun s15_brokenWavIsDiscarded() {
        replyWithBody("RIFF....WAVEjunk, definitely not a wav".toByteArray())
    }

    @Test fun s15_truncatedWavIsDiscarded() {
        val w = WavFixtures.valid()
        replyWithBody(w.copyOf(w.size - 10))
    }

    @Test fun s15_wavOverTenMegabytesIsDiscarded() {
        replyWithBody(WavFixtures.wav(dataBytes = 0, declaredData = 11L * 1024 * 1024) + ByteArray(11 * 1024 * 1024))
    }

    @Test fun s15_wavOverOneHundredTwentySecondsIsDiscarded() {
        replyWithBody(WavFixtures.wav(sampleRate = 16_000, dataBytes = 125 * 16_000 * 2))
    }

    // =========================================================================================
    // 補助: 認証、トークンの漏れ

    @Test fun neitherTheBridgeTokenNorAnyRecognizedTextAppearsInSnapshotsOrDiagnostics() {
        val e = env()
        e.start()
        e.host.onChunk = { MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"bad token"}}""") }
        e.talkUntil("401 で終了") { e.listener.ended.isNotEmpty() }
        assertEquals("host_unreachable", e.listener.ended.single().second)
        e.assertOnlyBridgeBearer()
        assertFalse(e.listener.snaps.any { it.toString().contains(Env.TOKEN) || (it.diagnostics ?: "").contains(Env.TOKEN) })
        assertNotNull(e.listener.last.diagnostics)
    }

    // =========================================================================================
    // 土台

    /** 仮想時間で動く、スレッドセーフなスケジューラ。テストのスレッドが「セッションのスレッド」(post は他のスレッドからも安全)。 */
    class LiveSim : SessionScheduler, Clock {
        private class Task(val at: Long, val order: Long, val run: () -> Unit) {
            @Volatile var cancelled = false
        }

        private val lock = java.util.concurrent.locks.ReentrantLock()
        private val changed = lock.newCondition()
        private var now = 0L
        private var counter = 0L
        private val tasks = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.order }))

        override fun nowMs() = lock.withLock { now }

        override fun post(task: () -> Unit) {
            lock.withLock {
                tasks += Task(now, counter++, task)
                changed.signalAll()
            }
        }

        /** `postDelayed` で登録された遅延(ms)の記録。タイマーの登録が終わったことを、仮想時間を進める前に確かめるために使う */
        val postedDelays = CopyOnWriteArrayList<Long>()

        override fun postDelayed(task: () -> Unit, delayMs: Long): Cancelable {
            val t = lock.withLock {
                postedDelays += delayMs
                Task(now + maxOf(0L, delayMs), counter++, task).also {
                    tasks += it
                    changed.signalAll()
                }
            }
            return Cancelable { t.cancelled = true }
        }

        private fun runUntil(target: Long) {
            while (true) {
                val t = lock.withLock {
                    val p = tasks.peek()
                    if (p == null || p.at > target) null else tasks.poll().also { now = maxOf(now, it.at) }
                } ?: break
                if (!t.cancelled) t.run()
            }
        }

        private fun idle() {
            lock.withLock { changed.await(2, TimeUnit.MILLISECONDS) }
        }

        /** いまの時刻に実行できるものをすべて実行する */
        fun pump() = runUntil(nowMs())

        /** 仮想時間を進める(途中の期限も順に実行) */
        fun advance(ms: Long) {
            val target = nowMs() + ms
            runUntil(target)
            lock.withLock { now = maxOf(now, target) }
        }

        /** 仮想時間は進めず、実時間で `realMs` ほど、届いた結果を処理し続ける */
        fun pumpFor(realMs: Long) {
            val end = System.nanoTime() + realMs * 1_000_000
            while (System.nanoTime() < end) {
                pump()
                idle()
            }
            pump()
        }

        /**
         * 遅延 `delayMs` のタイマーが、これから(この呼び出しより後に)新しく 1 つ登録されるまで、届いた結果を処理しながら待つ。
         * 通信の終わり(EOF)の処理が済んでタイマーが登録されたことを保証してから、テストは仮想時間を進められる。
         */
        fun awaitDelayedPost(delayMs: Long, what: String, before: Int = postedDelays.count { it == delayMs }, then: () -> Unit) {
            await(what) { postedDelays.count { it == delayMs } > before }
            then()
        }

        /** 条件が成り立つまで(実時間 `timeoutMs` まで)、届いた結果を処理しながら待つ */
        fun await(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
            val end = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                pump()
                if (cond()) {
                    pump() // 条件が成り立つ直前に積まれた結果も処理する
                    return
                }
                if (System.nanoTime() > end) fail("待ちきれなかった: $what")
                idle()
            }
        }
    }

    /** `AudioRecord` の代わり。時刻は仮想時間。 */
    class LiveAudio(private val sim: LiveSim) : AudioSource {
        @Volatile private var rec = false
        private var onPcm: ((ByteArray) -> Unit)? = null
        var startCount = 0
        var stopCount = 0
        val stopTimes = mutableListOf<Long>()
        override val recording: Boolean get() = rec

        override fun start(onPcm: (ByteArray) -> Unit, onError: (VoiceErrorCode) -> Unit) {
            startCount++
            this.onPcm = onPcm
            rec = true
        }

        override fun stop() {
            stopCount++
            stopTimes += sim.nowMs()
            rec = false
        }

        fun feed(ms: Int, amp: Int = 1000) {
            if (rec) onPcm?.invoke(FakeAudio.pcm(ms, amp))
        }

        fun feedBytes(pcm: ByteArray) {
            if (rec) onPcm?.invoke(pcm)
        }
    }

    class LiveListener(private val sim: LiveSim) : VoiceSessionListener {
        val snaps = mutableListOf<VoiceSnapshot>()
        private val times = mutableListOf<Long>()

        /** 条件に合う最初のスナップショットの仮想時刻 */
        fun timeOf(pred: (VoiceSnapshot) -> Boolean): Long = times[snaps.indexOfFirst(pred)]
        val micOpenedAt = mutableListOf<Long>()
        val ended = mutableListOf<Pair<Boolean, String>>()
        val last: VoiceSnapshot get() = snaps.last()
        override fun onSnapshot(snapshot: VoiceSnapshot) {
            snaps += snapshot
            times += sim.nowMs()
        }
        override fun onMicOpened(autoStarted: Boolean) { micOpenedAt += 0 }
        override fun onEnded(autoStarted: Boolean, reason: String) { ended += autoStarted to reason }

        /** 状態の遷移の並び(連続した同じ値は 1 つに) */
        fun states(): List<VoiceState> = snaps.map { it.state }.fold(mutableListOf()) { acc, s ->
            if (acc.lastOrNull() != s) acc += s
            acc
        }
    }

    /** 本物の [BridgeClient] を包み、送信中の数を数える・作成の結果の受け渡しを保留する。 */
    class SpyBridge(private val inner: BridgeApi) : BridgeApi {
        val sendStarted = AtomicInteger()
        val sendCompleted = AtomicInteger()

        /** 送信 API(`sendAudio`)の呼び出しごとの seq(HTTP 要求の数と区別する) */
        val sendCalls = CopyOnWriteArrayList<Int>()

        /** 送信 API に渡したバイト数の合計 */
        val sentBytes = AtomicInteger()
        @Volatile var holdCreate: CountDownLatch? = null
        val createReturned = CountDownLatch(1)

        private fun enter(k: String) {
            kind.set(k)
            if (k == "send") sendStarted.incrementAndGet()
            entered.get()?.countDown()
        }

        override fun createSession(req: SessionRequest): SessionInfo {
            enter("create")
            val info = inner.createSession(req)
            holdCreate?.let {
                createReturned.countDown()
                it.await(10, TimeUnit.SECONDS)
            }
            return info
        }

        override fun sendAudio(id: String, seq: Int, pcm: ByteArray, final: Boolean): ChunkAck {
            sendCalls += seq
            sentBytes.addAndGet(pcm.size)
            enter("send")
            return inner.sendAudio(id, seq, pcm, final)
        }

        override fun events(id: String, lastEventId: Int?, onEvent: (BridgeEvent) -> Unit) {
            enter("events")
            inner.events(id, lastEventId, onEvent)
        }

        override fun fetchReplyAudio(id: String, index: Int): ByteArray {
            enter("fetch")
            return inner.fetchReplyAudio(id, index)
        }

        override fun cancel(id: String) {
            enter("cancel")
            inner.cancel(id)
        }

        override fun abortInFlight() = inner.abortInFlight()

        companion object {
            val kind = ThreadLocal<String?>()
            val entered = ThreadLocal<CountDownLatch?>()
        }
    }

    /**
     * 本番の [ExecutorSessionWorker] と同じだが、(1) 呼び出しが [SpyBridge] に入るまで submit を返さない(送信の数え落としを防ぐ)、
     * (2) 送信の完了(結果をセッションに積んだあと)を数える。
     */
    class TrackingWorker(private val spy: SpyBridge, private val sim: LiveSim, private val pool: java.util.concurrent.ExecutorService) : SessionWorker {
        override fun <T> run(call: () -> T, deliver: (Result<T>) -> Unit) {
            val entered = CountDownLatch(1)
            pool.execute {
                SpyBridge.kind.remove()
                SpyBridge.entered.set(entered)
                val r = runCatching(call)
                val wasSend = SpyBridge.kind.get() == "send"
                sim.post { deliver(r) }
                if (wasSend) spy.sendCompleted.incrementAndGet()
            }
            entered.await(2, TimeUnit.SECONDS)
        }
    }

    /** 契約どおりに振る舞い、シナリオごとにふるまいを差し替えられるホスト。要求はすべて記録する。 */
    class ScriptHost : Dispatcher() {
        class Chunk(val seq: Int, val final: Boolean, val body: ByteArray) {
            val bytes: Int get() = body.size
        }

        private class Ev(val seq: Int, val name: String, val data: String)

        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val audioReqs = CopyOnWriteArrayList<Chunk>()
        val deletes = CopyOnWriteArrayList<String>()
        val eventRequests = CopyOnWriteArrayList<String?>()
        val closed = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)
        val segmentsClosed = AtomicInteger()
        val maxConcurrentAudio = AtomicInteger()
        private val concurrentAudio = AtomicInteger()
        private val createCount = AtomicInteger()
        private val lock = Any()
        private val events = ArrayList<Ev>()
        private var nextSeq = 0

        @Volatile var ackState = "listening"
        @Volatile var ackDelayMs = 0L
        @Volatile var createDelayMs = 0L

        /** 作成の応答を、これが開くまで(最大 30 秒)返さない。実時間の待ちではなく条件で保留する(負荷でテストの進みが遅れても、応答が先に出ない) */
        @Volatile var createGate: CountDownLatch? = null
        @Volatile var createExtra = ""
        @Volatile var holdEvents = false
        @Volatile var replyWav: ByteArray = WavFixtures.valid(ms = 500, rate = 24_000)
        @Volatile var onChunk: ((Chunk) -> MockResponse?)? = null
        @Volatile var createHandler: ((Int) -> MockResponse)? = null
        @Volatile var replyAudioHandler: (() -> MockResponse)? = null

        /** ゲート(ラッチ)を、テストが終わる(`closed`)までのあいだ待つ */
        fun waitFor(latch: CountDownLatch) {
            while (!latch.await(20, TimeUnit.MILLISECONDS)) if (closed.count == 0L) return
        }

        fun emitAll(vararg items: Pair<String, JSONObject.() -> Unit>) {
            synchronized(lock) {
                for ((name, build) in items) {
                    val seq = nextSeq++
                    val o = JSONObject().put("seq", seq)
                    o.build()
                    events += Ev(seq, name, o.toString())
                }
            }
        }

        /** 直前のイベントと同じ seq・同じ内容の行をもう 1 つ足す(ホストが重複して流す状況) */
        fun addDuplicateOfLast() {
            synchronized(lock) { events += events.last().let { Ev(it.seq, it.name, it.data) } }
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.path!!
            return when {
                request.method == "POST" && path == "/v1/voice/sessions" -> create()
                request.method == "POST" && path.endsWith("/audio") -> audio(request)
                request.method == "GET" && path.endsWith("/events") -> events(request)
                request.method == "GET" && path.contains("/replies/") -> replyAudioHandler?.invoke()
                    ?: MockResponse().setHeader("Content-Type", "audio/wav").setBody(okio.Buffer().write(replyWav))
                request.method == "DELETE" -> {
                    deletes += path
                    MockResponse().setResponseCode(204)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun create(): MockResponse {
            val n = createCount.incrementAndGet()
            if (createDelayMs > 0) Thread.sleep(createDelayMs)
            createGate?.await(30, TimeUnit.SECONDS)
            createHandler?.let { return it(n) }
            return MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(
                """{"session_id":"s-$n","limits":{"no_speech_s":10,"max_utterance_s":30,"reply_timeout_s":45,"idle_expire_s":15,"retain_s":60}$createExtra}""",
            )
        }

        private fun audio(r: RecordedRequest): MockResponse {
            val seq = r.getHeader("X-Chunk-Seq")!!.toInt()
            val chunk = Chunk(seq, r.getHeader("X-Chunk-Final") == "1", r.body.clone().readByteArray())
            audioReqs += chunk
            val now = concurrentAudio.incrementAndGet()
            maxConcurrentAudio.accumulateAndGet(now, ::maxOf)
            try {
                onChunk?.invoke(chunk)?.let { return it }
                if (ackDelayMs > 0) Thread.sleep(ackDelayMs)
                return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"state":"$ackState","accepted_seq":$seq}""")
            } finally {
                concurrentAudio.decrementAndGet()
            }
        }

        private fun events(r: RecordedRequest): MockResponse {
            val header = r.getHeader("Last-Event-ID")
            eventRequests += header
            val last = header?.toIntOrNull() ?: -1
            while (true) {
                if (closed.count == 0L) return MockResponse().setResponseCode(503)
                val pending = synchronized(lock) { if (holdEvents) emptyList() else events.filter { it.seq > last } }
                if (pending.isNotEmpty()) {
                    val body = pending.joinToString("") { "id: ${it.seq}\nevent: ${it.name}\ndata: ${it.data}\n\n" }
                    segmentsClosed.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
                }
                Thread.sleep(5)
            }
        }
    }

    /** 一式。テストのスレッドがセッションのスレッド。 */
    class Env(val host: ScriptHost, guardMs: Long, sessionMaxMs: Long, hold: Boolean) {
        val server = MockWebServer().apply {
            dispatcher = host
            start()
        }
        val sim = LiveSim()
        val client = BridgeClient(server.url("/").toString().trimEnd('/'), TOKEN)
        val spy = SpyBridge(client).also { if (hold) it.holdCreate = CountDownLatch(1) }
        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "scenario-worker").apply { isDaemon = true } }
        val audio = LiveAudio(sim)
        val log = mutableListOf<String>()
        val player = FakePlayer(log)
        val listener = LiveListener(sim)
        val session = VoiceSession(
            audio, spy, player, sim, sim, TrackingWorker(spy, sim, pool), listener, clientId = "echo-show-5", appVersion = "0.3.0",
            startToneGuardMs = guardMs, sessionMaxMs = sessionMaxMs,
        )

        fun close() {
            host.closed.countDown()
            spy.holdCreate?.countDown()
            pool.shutdownNow()
            try {
                server.shutdown()
            } catch (_: Exception) {
            }
        }

        fun start() {
            session.start(StartReason.SWIPE, null, false)
            sim.await("LISTENING") { session.phase == VoicePhase.LISTENING }
        }

        /** 実際の通信が落ち着くまで待つ(仮想時間と通信の遅さを合わせて、キューがあふれないようにする) */
        fun settleIo() = sim.await("送信が落ち着く") { spy.sendStarted.get() == spy.sendCompleted.get() }

        fun talk(ms: Int, block: Int = 100, amp: Int = 1000, settle: Boolean = true) {
            var left = ms
            while (left > 0) {
                val b = minOf(block, left)
                audio.feed(b, amp)
                sim.advance(b.toLong())
                if (settle) settleIo()
                left -= b
            }
        }

        fun talkUntil(what: String, maxMs: Int = 20_000, block: Int = 100, settle: Boolean = true, cond: () -> Boolean) {
            var spent = 0
            while (!cond()) {
                if (spent >= maxMs) fail("録音を続けても成り立たなかった: $what")
                talk(block, block, settle = settle)
                spent += block
            }
        }

        fun assertOnlyBridgeBearer() {
            assertTrue(host.requests.isNotEmpty())
            for (r in host.requests) {
                assertEquals("Bearer $TOKEN", r.getHeader("Authorization"))
                assertFalse(r.headers.toString().contains(PANEL_TOKEN))
            }
        }

        companion object {
            const val TOKEN = "bridge-token-0123456789"
            const val PANEL_TOKEN = "panel-token-ABCDEFGHIJ"
        }
    }

    private fun evt(name: String, build: JSONObject.() -> Unit = {}): Pair<String, JSONObject.() -> Unit> = name to build

    private fun JSONObject.replyJson(wavBytes: Int) {
        put("index", 0).put("text", "こんにちは").put("final", true).put("avatar_pose", "talking")
        put("audio", JSONObject().put("format", "wav").put("sample_rate", 24_000).put("bytes", wavBytes).put("duration_ms", 500))
        put("audio_error", JSONObject.NULL)
    }
}
