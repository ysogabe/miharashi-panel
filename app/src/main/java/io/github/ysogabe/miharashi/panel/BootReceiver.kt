package io.github.ysogabe.miharashi.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 端末の起動時とアプリの更新時に操作盤を前面に出す。
 * Android 10 以降は「他のアプリの上に表示」の許可が無いと黙って無視される(scripts/install.sh が許可する)。
 * ランチャー(HOME)に指定してあれば、この許可が無くても起動時に操作盤が出る。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> context.startActivity(
                Intent(context, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
