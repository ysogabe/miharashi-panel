package io.github.ysogabe.miharashi.panel

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 操作盤。全画面(immersive)、常時点灯、横固定(設計書 §5.6)。
 * 同期(`KioskSync`)は onResume で作って始め、onPause で止めて捨てる。
 */
class PanelActivity : Activity() {
    private lateinit var view: PanelView
    private lateinit var daidaiView: DaidaiView
    private lateinit var pager: PagerLayout
    private val pages = PageController()
    private lateinit var settings: Settings
    private lateinit var daidai: DaidaiController
    private lateinit var voice: DaidaiVoice
    private var sync: KioskSync? = null
    private var lastEndpoint: Pair<String, String>? = null
    private lateinit var connectivity: ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private lateinit var light: LightSensorReader
    private var darkness = DarknessPolicy(Settings.DEFAULT_DARK_LUX)
    private var lux: Float? = null
    private var sleeping = false
    private var active = false
    private var automaticRequest: AtomicBoolean? = null
    private val darknessDeadline = Runnable { checkDarkness() }

    /** B に落ち着いている間だけ、1 秒ごとに無操作を確かめる(A へ戻す)。 */
    private val pageTick = object : Runnable {
        override fun run() {
            pages.tick(SystemClock.elapsedRealtime())
            if (pages.settledPage == Page.B) main.postDelayed(this, PAGE_TICK_MS)
        }
    }

    private fun cancelAutomaticRequest() {
        automaticRequest?.set(true)
        automaticRequest = null
        main.removeCallbacks(darknessDeadline)
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val on = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            runOnUiThread { view.wifi = on }
        }

        override fun onLost(network: Network) {
            runOnUiThread { view.wifi = false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        connectivity = getSystemService(ConnectivityManager::class.java)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 起動直後に出るロック画面(スワイプだけのもの)より手前に出す。画面ロックが設定してあれば解除は求めない
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        view = PanelView(
            this,
            onAction = { action, done ->
                if (action is Action.Display) {
                    cancelAutomaticRequest()
                    darkness.handled()
                }
                sync?.send(action, done) ?: done("未接続")
            },
            onSettings = {
                voice.cancel() // 設定画面を開く: マイクは必ず止める
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        )
        view.onSleepChanged = {
            sleeping = it
            if (it) voice.cancel() // 待機(時計)に入る: B 表示中は入らないが、念のためマイクを止める
            pager.dotsVisible = !it
            setBacklight(force = true)
        }
        daidaiView = DaidaiView(this)
        // ページは常に A から始める。Intent の extras からページは指定できない(設計書 §4.1 の不変条件)
        pager = PagerLayout(this, view, daidaiView, pages, gestureSwallowed = { view.gestureSwallowed })
        pages.addListener(object : PageListener {
            // B が少しでも見えている間は、A の無操作判定(待機時計)を保留する
            override fun onBVisibleChanged(visible: Boolean) { view.idleSuspended = visible }

            override fun onSettled(page: Page, reason: PageReason) {
                main.removeCallbacks(pageTick)
                if (page == Page.B) main.postDelayed(pageTick, PAGE_TICK_MS)
            }
        })
        // B の表示は DaidaiController(通知カード・未設定の案内)→ DaidaiVoice(音声の状態を重ねる)→ DaidaiView の順
        daidai = DaidaiController(pages, { settings.daidai }, { voice.onBaseUi(it) }, view::wake)
        voice = DaidaiVoice(
            pages, daidai.policy, { settings.daidai },
            testVoiceRuntimeFactory ?: VoiceRuntimeFactory { s, l -> AndroidVoiceRuntime(applicationContext, s, l) },
            HandlerScheduler(main), daidaiView::render, testStartSound ?: ToneStartSound(), SystemClock::elapsedRealtime,
        )
        daidai.entryListener = voice
        daidaiView.onErrorTap = voice::onErrorTapped // 再試行は利用者のタップだけ
        light = LightSensorReader(this) { value ->
            lux = value
            if (active) {
                daidai.onLux(value)
                // 通信待ちの間に暗闇でなくなったら、まだ送っていない消灯も取り消す。
                if (value == null || value > darkness.thresholdLux) cancelAutomaticRequest()
                darkness.observe(value, SystemClock.elapsedRealtime())
                setBacklight()
                checkDarkness()
            }
        }
        setContentView(pager)
    }

    override fun onResume() {
        super.onResume()
        active = true
        // 常に A から始める。A の無操作判定の保留も、ここで外す
        pages.idleMs = settings.daidai.idleSeconds * 1000L
        pages.onResume(SystemClock.elapsedRealtime())
        lux = null
        sleeping = false
        if (darkness.thresholdLux != settings.darkLux) darkness = DarknessPolicy(settings.darkLux)
        hideSystemBars()
        view.setForegroundActive(true)
        // コールバックは変化したときしか来ないので、今の状態を先に読む
        view.wifi = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        connectivity.registerDefaultNetworkCallback(wifiCallback)
        val endpoint = settings.url to settings.token
        // 接続先が変わったら、前のキオスクの状態は捨てる
        if (endpoint != lastEndpoint) {
            view.state = PanelState()
            darkness = DarknessPolicy(settings.darkLux)
        }
        lastEndpoint = endpoint
        view.configured = settings.configured
        // 通知の基準は、KioskSync を始める前に取り直す(最初の status が基準になる)。B の表示もここで組み直す
        daidai.start(view.state, endpoint)
        // セッションは始めない(B の確定でだけ始まる)。接続先(URL・トークン)が変わっていたら古いセッションを捨てる
        voice.onResume()
        // 設定から戻ったときも、ここから数え直す
        view.sleepTimeoutMs = settings.sleepMinutes * 60_000L
        view.wake()
        if (settings.configured) {
            sync = KioskSync(KioskClient(settings.url, settings.token), view.state, ::onKioskState)
                .also { it.start() }
        }
        setBacklight()
        if (settings.autoBrightness || settings.darkOff || settings.daidai.autoSwitch) light.start()
    }

    override fun onPause() {
        active = false
        voice.stop() // 背面: マイクを止め、セッションのスレッドも片づける
        // ページを A に戻す(B のタイマーと、A の無操作判定の保留も止まる)
        main.removeCallbacks(pageTick)
        pages.onPause(SystemClock.elapsedRealtime())
        cancelAutomaticRequest()
        daidai.stop()
        darkness.stopObserving()
        light.stop()
        lux = null
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        view.setForegroundActive(false)
        sync?.stop()
        sync = null
        runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun setBacklight(force: Boolean = false) {
        if (!active) return
        val contrast = AmbientContrast.strength(lux, settings.autoBrightness)
        view.contrastStrength = contrast
        daidaiView.contrastStrength = contrast
        val value = AmbientBrightness.value(lux, settings.autoBrightness, sleeping)
        val old = window.attributes.screenBrightness
        // 小さな揺れでウィンドウ設定を書き換え続けない。モード切替と5%への移行は必ず反映。
        if (old == value || (old >= 0 && value >= 0 && !force && value != 0.05f && abs(old - value) < 0.02f)) return
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun checkDarkness() {
        main.removeCallbacks(darknessDeadline)
        if (!active || !settings.darkOff) return
        val now = SystemClock.elapsedRealtime()
        val remaining = darkness.remainingMs(now) ?: return
        if (remaining > 0) {
            main.postDelayed(darknessDeadline, remaining)
            return
        }
        val source = sync ?: return
        if (!darkness.takeRequest(view.state, now)) return
        voice.cancel() // モニター消灯: マイクは必ず止める
        val validUntilMs = view.state.lastOkAtMs!! + PanelState.FRESH_MS
        val cancelled = AtomicBoolean(false)
        automaticRequest = cancelled
        source.send(Action.Display("force_off"), { error ->
            if (active && sync === source && !cancelled.get()) {
                automaticRequest = null
                Toast.makeText(this,
                    if (error == null) "暗闇を検出してモニターを消灯しました"
                    else "暗闇での消灯に失敗: $error", Toast.LENGTH_LONG).show()
            }
        }, isCurrent = { !cancelled.get() && SystemClock.elapsedRealtime() <= validUntilMs })
    }

    private fun onKioskState(state: PanelState) {
        view.state = state
        daidai.onKioskState(state)
        if (automaticRequest != null && !darkness.canTurnOff(state, SystemClock.elapsedRealtime())) {
            cancelAutomaticRequest()
        }
        checkDarkness()
    }

    override fun onDestroy() {
        voice.stop()
        super.onDestroy()
    }

    companion object {
        private const val PAGE_TICK_MS = 1_000L

        /** 試験用の差し込み口(本番では null)。Intent からは触れない。セッション一式とスピーカーを偽物にする */
        internal var testVoiceRuntimeFactory: VoiceRuntimeFactory? = null
        internal var testStartSound: StartSound? = null
    }

    /** immersive sticky: バーを隠し、端からのスワイプで一時的にだけ出す */
    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
