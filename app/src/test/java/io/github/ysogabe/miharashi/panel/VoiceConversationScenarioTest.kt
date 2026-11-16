package io.github.ysogabe.miharashi.panel

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 複数往復の会話の通信シナリオ(段階 2a-P の AC3。設計書(段階 2)§5.3・§5.4・§6.1)。**本物の** [BridgeClient](MockWebServer への HTTP)+
 * [DeleteOnCloseBridge] + [VoiceSession] + [DaidaiVoice] + [PageController] を、`VoiceBridgeScenarioTest` と同じ流儀(通信は実時間、時間は仮想)で組む。
 * 本番の [AndroidVoiceRuntime] と同じ作り(会話の終わりをスレッドプールで送る、`close` で回収する)のテスト用 runtime を載せる。
 * 外部のホストへは通信しない(相手は同じ JVM の MockWebServer だけ)。
 */
class VoiceConversationScenarioTest {
    private val envs = mutableListOf<Env>()

    @Before fun clear() = AutoSwitchDiagnostics.clear()

    @After fun tearDown() {
        envs.forEach { it.close() }
        AutoSwitchDiagnostics.clear()
    }

    private fun env(configure: ConvHost.() -> Unit = {}) = Env(ConvHost().apply(configure)).also { envs += it }

    // ---- 3 往復

    @Test fun threeTurnsCarryTheHandleAndEndTheConversationOnceWithTheHandleInTheBody() {
        val e = env()
        e.enter()
        e.awaitMic(1)
        assertEquals("最初の往復の開始音", 1, e.tones)
        e.speakAndHostReplies(1)
        e.playbackEnds(1)
        e.advanceEchoWait()
        e.awaitMic(2)
        assertEquals("続きの開始音", 1, e.followTones)
        e.speakAndHostReplies(2)
        e.playbackEnds(2)
        e.advanceEchoWait()
        e.awaitMic(3)
        assertEquals(2, e.followTones)
        e.hostNoSpeech(3) // 3 往復目は静かに終わる
        e.sim.await("会話が終わる") { e.host.conversationEnds.isNotEmpty() }
        e.sim.pumpFor(200)

        val bodies = e.host.createBodies
        assertEquals(3, bodies.size)
        assertEquals("往復ごとに新しい session_id", listOf("s-1", "s-2", "s-3"), e.host.sessionsCreated.toList())
        assertEquals("1 往復目は conversation: {}", 0, bodies[0].getJSONObject("conversation").length())
        val handle = e.host.issuedHandle(1)
        assertEquals("2 往復目は 201 のハンドル", handle, bodies[1].getJSONObject("conversation").getString("handle"))
        assertEquals(handle, bodies[2].getJSONObject("conversation").getString("handle"))
        assertEquals(VoiceLimits.NO_SPEECH_DEFAULT_S, bodies[0].getJSONObject("limits").getLong("no_speech_s"))
        assertEquals("続きの本文の limits.no_speech_s は 6", 6L, bodies[1].getJSONObject("limits").getLong("no_speech_s"))
        assertEquals(6L, bodies[2].getJSONObject("limits").getLong("no_speech_s"))
        assertEquals("reason は会話の最初の値", "swipe", bodies[1].getString("reason"))
        assertEquals("会話の終わりは 1 回(本文にハンドル)", listOf(handle), e.host.conversationEnds.toList())
        assertEquals("URL にハンドルが無い", "/v1/voice/conversations/end", e.host.conversationEndPaths.single())
        assertTrue("ハンドルはどの URL にも出ない", e.host.requests.none { it.path!!.contains(handle) })
        assertEquals("往復ごとのセッションの DELETE", setOf("/v1/voice/sessions/s-1", "/v1/voice/sessions/s-2", "/v1/voice/sessions/s-3"), e.host.deletes.toSet())
        assertTrue("ERROR を出さない", e.states.none { it == VoiceState.ERROR })
        e.assertNoStateRegression()
        e.assertOnlyBridgeBearer()
        assertEquals("もう作らない", 3, bodies.size)
    }

    @Test fun anOldHostWithoutAConversationRunsSeveralTurnsWithEmptyConversationAndNoEnd() {
        val e = env { omitConversation = true }
        e.enter()
        e.awaitMic(1)
        e.speakAndHostReplies(1)
        e.playbackEnds(1)
        e.advanceEchoWait()
        e.awaitMic(2)
        e.speakAndHostReplies(2)
        e.playbackEnds(2)
        e.advanceEchoWait()
        e.awaitMic(3)
        assertEquals(3, e.host.createBodies.size)
        for (b in e.host.createBodies) assertEquals("続きも {} のまま", 0, b.getJSONObject("conversation").length())
        e.leave()
        e.sim.pumpFor(300)
        assertTrue("旧いホストには終わりの通知を送らない", e.host.conversationEnds.isEmpty())
    }

    @Test fun hostContinueFalseStopsTheNextTurnAndEndsTheConversation() {
        val e = env { continueFlag = false }
        e.enter()
        e.awaitMic(1)
        e.speakAndHostReplies(1)
        e.playbackEnds(1)
        e.advanceEchoWait()
        e.sim.await("会話の終わり") { e.host.conversationEnds.isNotEmpty() }
        e.sim.pumpFor(300)
        assertEquals("次の往復は作られない", 1, e.host.createBodies.size)
        assertEquals(1, e.host.conversationEnds.size)
    }

    @Test fun aFollowCreateFailureWithAsrUnavailableShowsAnErrorAndEndsTheConversation() {
        val e = env {
            createHandler = { n ->
                if (n == 2) MockResponse().setResponseCode(503).setBody("""{"error":{"code":"asr_unavailable","message":"x"}}""") else null
            }
        }
        e.enter()
        e.awaitMic(1)
        e.speakAndHostReplies(1)
        e.playbackEnds(1)
        e.advanceEchoWait()
        e.sim.await("ERROR を出す") { e.states.contains(VoiceState.ERROR) }
        e.sim.await("会話の終わり") { e.host.conversationEnds.isNotEmpty() }
        assertEquals("1 往復目で受け取ったハンドルを終える", listOf(e.host.issuedHandle(1)), e.host.conversationEnds.toList())
        e.sim.pumpFor(300)
        assertEquals("自動で作り直さない", 2, e.host.createBodies.size)
        assertEquals(1, e.audio.startCount)
    }

    // ---- 競合(HTTP 越し。遅い 201 の後始末の細部は DaidaiVoiceFollowSessionTest)

    @Test fun leavingDuringTheEchoWaitSendsOneEndAndCreatesNothing() {
        val e = env()
        e.enter()
        e.awaitMic(1)
        e.speakAndHostReplies(1)
        e.playbackEnds(1)
        e.sim.advance(100) // 残響の待ちの途中
        e.leave()
        e.sim.await("会話の終わり") { e.host.conversationEnds.isNotEmpty() }
        e.sim.advance(10_000)
        e.sim.pumpFor(200)
        assertEquals("続きの作成 0 回", 1, e.host.createBodies.size)
        assertEquals(1, e.audio.startCount)
        assertEquals(1, e.host.conversationEnds.size)
    }

    @Test fun leavingWhileTheFollowCreateRequestIsInFlightNeverOpensTheMicAndEndsTheConversationOnce() {
        val e = env { createDelayMs = { n -> if (n == 2) 700L else 0L } }
        e.enter()
        e.awaitMic(1)
        e.speakAndHostReplies(1)
        e.playbackEnds(1)
        e.advanceEchoWait()
        e.sim.await("作成の要求がホストに届く") { e.host.createBodies.size == 2 }
        e.leave() // 201 が返る前に離脱(作成の要求は送った後)
        e.sim.await("会話の終わり") { e.host.conversationEnds.isNotEmpty() }
        e.sim.pumpFor(1_200) // 遅れた応答が来ても来なくても
        e.sim.advance(10_000)
        e.sim.pumpFor(200)
        assertEquals("取り消した会話の録音 0 回", 1, e.audio.startCount)
        assertFalse(e.audio.recording)
        assertEquals("会話の終わりの通知は 1 ハンドルにつき 1 回", 1, e.host.conversationEnds.size)
        assertEquals(2, e.host.createBodies.size)
        assertFalse("続きの往復の開始音は鳴らない", e.followTones > 0)
    }

    @Test fun leavingAndReEnteringRightAwayStartsAFreshConversationWithAnEmptyConversation() {
        val e = env()
        e.enter()
        e.awaitMic(1)
        e.speakAndHostReplies(1)
        e.playbackEnds(1)
        e.sim.advance(100)
        e.leave()
        e.sim.await("古い会話の終わり") { e.host.conversationEnds.isNotEmpty() }
        e.enter() // すぐ再入場
        e.awaitMic(2)
        e.sim.advance(1_000) // 古い予約(残響の待ち)の時刻を過ぎても、新しい会話以外は始まらない
        e.sim.pumpFor(200)
        assertEquals("古い予約は動かない。新しい会話の 1 往復目だけ", 2, e.host.createBodies.size)
        assertEquals("新しい会話は {} から", 0, e.host.createBodies[1].getJSONObject("conversation").length())
        assertEquals("新しい往復の本文の待ちは最初の往復の値", VoiceLimits.NO_SPEECH_DEFAULT_S, e.host.createBodies[1].getJSONObject("limits").getLong("no_speech_s"))
        assertEquals("古い会話の終わりの通知は 1 回(新しい会話のハンドルは終わらせない)", listOf(e.host.issuedHandle(1)), e.host.conversationEnds.toList())
        assertEquals("最初の往復の開始音のまま(続きの音ではない)", 0, e.followTones)
        assertTrue("新しい会話のマイクは開いたまま", e.audio.recording)
    }

    // ---- 土台

    /** 契約どおりに振る舞い、シナリオごとに差し替えられるホスト(往復ごとのセッション。会話のハンドルは作成の本文から決まる) */
    class ConvHost : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val createBodies = CopyOnWriteArrayList<JSONObject>()
        val sessionsCreated = CopyOnWriteArrayList<String>()
        val deletes = CopyOnWriteArrayList<String>()
        val conversationEnds = CopyOnWriteArrayList<String>()
        val conversationEndPaths = CopyOnWriteArrayList<String>()
        private val audioCounts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val closed = CountDownLatch(1)

        @Volatile var omitConversation = false
        @Volatile var continueFlag = true
        @Volatile var createHandler: ((Int) -> MockResponse?)? = null

        /** 作成の応答を返す前の遅れ(実時間 ms)。n は 1 から */
        @Volatile var createDelayMs: (Int) -> Long = { 0L }
        @Volatile var replyWav: ByteArray = WavFixtures.valid(ms = 500, rate = 24_000)

        private class Ev(val seq: Int, val name: String, val data: String)

        private val events = java.util.concurrent.ConcurrentHashMap<String, MutableList<Ev>>()
        private val lock = Any()
        private val createCount = AtomicInteger()
        private val handles = java.util.concurrent.ConcurrentHashMap<Int, String>()

        /** n 回目の作成で発行したハンドル(作成の本文が `{}` のとき) */
        fun issuedHandle(n: Int): String = handles.getValue(n)

        fun chunks(sid: String) = audioCounts[sid]?.get() ?: 0

        fun emit(sid: String, vararg items: Pair<String, JSONObject.() -> Unit>) {
            synchronized(lock) {
                val list = events.getOrPut(sid) { mutableListOf() }
                for ((name, build) in items) {
                    val seq = list.size
                    val o = JSONObject().put("seq", seq)
                    o.build()
                    list += Ev(seq, name, o.toString())
                }
            }
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.path!!
            return when {
                request.method == "POST" && path == "/v1/voice/sessions" -> create(request)
                request.method == "POST" && path == "/v1/voice/conversations/end" -> {
                    conversationEndPaths += path
                    conversationEnds += JSONObject(request.body.clone().readUtf8()).getString("handle")
                    MockResponse().setResponseCode(204)
                }
                request.method == "POST" && path.endsWith("/audio") -> {
                    audioCounts.getOrPut(path.split("/")[4]) { AtomicInteger() }.incrementAndGet()
                    MockResponse().setHeader("Content-Type", "application/json").setBody("""{"state":"listening","accepted_seq":${request.getHeader("X-Chunk-Seq")}}""")
                }
                request.method == "GET" && path.endsWith("/events") -> events(path.split("/")[4], request.getHeader("Last-Event-ID")?.toIntOrNull() ?: -1)
                request.method == "GET" && path.contains("/replies/") ->
                    MockResponse().setHeader("Content-Type", "audio/wav").setBody(okio.Buffer().write(replyWav))
                request.method == "DELETE" -> {
                    deletes += path
                    MockResponse().setResponseCode(204)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun create(request: RecordedRequest): MockResponse {
            val n = createCount.incrementAndGet()
            val body = JSONObject(request.body.clone().readUtf8())
            createBodies += body
            val sid = "s-$n"
            sessionsCreated += sid
            val delay = createDelayMs(n)
            if (delay > 0) Thread.sleep(delay)
            createHandler?.invoke(n)?.let { return it }
            val conv = body.optJSONObject("conversation")
            val json = JSONObject().put("session_id", sid).put(
                "limits",
                JSONObject().put("no_speech_s", minOf(10L, body.getJSONObject("limits").getLong("no_speech_s"))).put("max_utterance_s", 30)
                    .put("reply_timeout_s", 45).put("idle_expire_s", 15).put("retain_s", 60),
            )
            if (!omitConversation && conv != null) {
                val handle = if (conv.has("handle")) conv.getString("handle") else "Conv${n}".padEnd(22, 'x').also { handles[n] = it }
                json.put("conversation", JSONObject().put("handle", handle).put("context", true).put("continue", continueFlag))
            }
            return MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(json.toString())
        }

        private fun events(sid: String, last: Int): MockResponse {
            while (true) {
                if (closed.count == 0L) return MockResponse().setResponseCode(503)
                val pending = synchronized(lock) { events[sid]?.filter { it.seq > last } ?: emptyList() }
                if (pending.isNotEmpty()) {
                    val body = pending.joinToString("") { "id: ${it.seq}\nevent: ${it.name}\ndata: ${it.data}\n\n" }
                    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
                }
                Thread.sleep(5)
            }
        }
    }

    /** 本番の [AndroidVoiceRuntime] と同じ作り。マイク・再生・時計は仮想(通信だけが実物) */
    private class ScenarioRuntime(settings: DaidaiSettings, listener: VoiceSessionListener, val env: Env) : VoiceRuntime {
        private val dob = DeleteOnCloseBridge(BridgeClient(settings.url, settings.token))
        private val spy = VoiceBridgeScenarioTest.SpyBridge(dob)
        private val session = VoiceSession(
            env.audio, spy, env.player, env.sim, env.sim, VoiceBridgeScenarioTest.TrackingWorker(spy, env.sim, env.pool), listener,
            clientId = "echo-show-5", appVersion = "0.3.0",
        )

        override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) = session.start(reason, noticeId, autoStarted)
        override fun retry() = session.retry()
        override fun start(request: TurnRequest) = session.start(request)
        override fun retry(request: TurnRequest) = session.retry(request)
        override fun endConversation(handle: String) = quietly { dob.endConversation(handle) }
        override fun cancel() = session.cancel()
        override fun stopMicNow() = session.stopMicNow()
        override fun close() {
            session.cancel()
            session.stopMicNow()
            val orphans = dob.close()
            for (id in orphans.sessionIds) quietly { dob.cancel(id) }
            for (h in orphans.handles) quietly { dob.endConversation(h) }
        }

        private fun quietly(task: () -> Unit) {
            try {
                env.pool.execute { try { task() } catch (_: Exception) { } }
            } catch (_: RuntimeException) {
            }
        }
    }

    private inner class Env(val host: ConvHost) {
        val server = MockWebServer().apply {
            dispatcher = host
            start()
        }
        val sim = VoiceBridgeScenarioTest.LiveSim()
        val audio = VoiceBridgeScenarioTest.LiveAudio(sim)
        val log = mutableListOf<String>()
        val player = FakePlayer(log)
        val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "conv-worker").apply { isDaemon = true } }
        val settings = DaidaiSettings(url = server.url("/").toString().trimEnd('/'), token = TOKEN)
        val pages = PageController()
        val states = CopyOnWriteArrayList<VoiceState>()
        var tones = 0
        var followTones = 0
        private var plays = 0
        lateinit var voice: DaidaiVoice
        val controller = DaidaiController(pages, { settings }, { voice.onBaseUi(it) }, { }, { sim.nowMs() })

        init {
            voice = DaidaiVoice(
                pages, controller.policy, { settings },
                VoiceRuntimeFactory { s, l -> ScenarioRuntime(s, l, this) },
                sim, { states += it.voice },
                object : StartSound {
                    override fun play() { tones++ }
                    override fun playFollow() { followTones++ }
                },
                { sim.nowMs() }, { GateVerdict(Presence.PRESENT, AmbientLightGate.REASON_BRIGHT) },
            )
            controller.entryListener = voice
            controller.start(PanelState(), "ep")
        }

        fun enter() {
            assertTrue(pages.beginDrag(sim.nowMs()))
            pages.endDrag(Page.B, sim.nowMs())
            pages.animationEnded(sim.nowMs())
            sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        }

        fun leave() {
            assertTrue(pages.beginDrag(sim.nowMs()))
            pages.endDrag(Page.A, sim.nowMs())
            sim.pump()
            pages.animationEnded(sim.nowMs())
            sim.pump()
        }

        fun awaitMic(n: Int) = sim.await("マイクが $n 回目に開く") { audio.startCount == n && audio.recording }

        fun advanceEchoWait() = sim.advance(DaidaiVoice.ECHO_WAIT_MS)

        /** 開始音の保護を過ぎて話し、ホストが返答する(`sid` は往復の番号) */
        fun speakAndHostReplies(turn: Int) {
            val sid = "s-$turn"
            val wav = host.replyWav
            sim.advance(450)
            audio.feed(200)
            sim.advance(200)
            sim.await("チャンクがホストに届く") { host.chunks(sid) >= 1 }
            host.emit(
                sid,
                "state" to { put("state", "speech") },
                "transcript" to { put("text", "今日の天気は").put("final", true) },
                "state" to { put("state", "waiting") },
                "reply" to {
                    put("index", 0).put("text", "こんにちは").put("final", true).put("avatar_pose", "talking")
                    put("audio", JSONObject().put("format", "wav").put("sample_rate", 24_000).put("bytes", wav.size).put("duration_ms", 500))
                    put("audio_error", JSONObject.NULL)
                },
                "done" to { },
            )
        }

        /** 返答の再生が始まり、完了する */
        fun playbackEnds(turn: Int) {
            sim.await("再生が始まる") { player.plays == turn }
            player.finish(true)
            sim.pump()
        }

        fun hostNoSpeech(turn: Int) {
            sim.advance(450)
            host.emit("s-$turn", "error" to { put("code", "no_speech").put("message", "no speech") })
            sim.await("続きの無発話で会話が終わる") { states.isNotEmpty() && !pages.sessionActive }
        }

        /** 往復ごとに、状態が後退しない(LISTENING → HEARING → WAITING → REPLYING の順。IDLE で往復が区切られる) */
        fun assertNoStateRegression() {
            val rank = mapOf(VoiceState.IDLE to 0, VoiceState.LISTENING to 1, VoiceState.HEARING to 2, VoiceState.WAITING to 3, VoiceState.REPLYING to 4)
            var prev = 0
            for (s in states) {
                val r = rank[s] ?: error("想定外の状態 $s")
                if (r != 0) assertTrue("状態の後退: $s(直前の順位 $prev)", r >= prev)
                prev = if (r == 0) 0 else r
            }
        }

        fun assertOnlyBridgeBearer() {
            assertTrue(host.requests.isNotEmpty())
            for (r in host.requests) assertEquals("Bearer $TOKEN", r.getHeader("Authorization"))
        }

        fun close() {
            host.closed.countDown()
            pool.shutdownNow()
            try {
                server.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val TOKEN = "bridge-token-0123456789"
    }
}
