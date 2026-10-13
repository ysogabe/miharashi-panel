package io.github.ysogabe.miharashi.panel

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 操作盤。全画面(immersive)、常時点灯、横固定(設計書 §5.6)。
 * 同期(`KioskSync`)は onResume で作って始め、onPause で止めて捨てる。
 */
class PanelActivity : Activity() {
    private lateinit var view: PanelView
    private lateinit var settings: Settings
    private var sync: KioskSync? = null
    private var lastEndpoint: Pair<String, String>? = null
    private lateinit var connectivity: ConnectivityManager

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
            onKey = { key, done -> sync?.send(key, done) ?: done("未接続") },
            onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // コールバックは変化したときしか来ないので、今の状態を先に読む
        view.wifi = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        connectivity.registerDefaultNetworkCallback(wifiCallback)
        val endpoint = settings.url to settings.token
        // 接続先が変わったら、前のキオスクの状態は捨てる
        if (endpoint != lastEndpoint) view.state = PanelState()
        lastEndpoint = endpoint
        view.configured = settings.configured
        if (settings.configured) {
            sync = KioskSync(KioskClient(settings.url, settings.token), view.state) { view.state = it }
                .also { it.start() }
        }
    }

    override fun onPause() {
        sync?.stop()
        sync = null
        runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** immersive sticky: バーを隠し、端からのスワイプで一時的にだけ出す */
    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
