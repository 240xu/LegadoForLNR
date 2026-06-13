package io.legado.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * LNR 插件发现广播接收器
 * 当 LNR 宿主应用发送发现广播时，返回插件元数据
 */
class PluginDiscoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val replyIntent = Intent("io.nightfish.lightnovelreader.PLUGIN_DISCOVERY_RESPONSE").apply {
            putExtra("package_name", context.packageName)
            putExtra("plugin_class", "io.legado.plugin.LegadoJsonPlugin")
            setPackage(intent.`package`)
        }
        try {
            context.sendBroadcast(replyIntent)
        } catch (_: Exception) {}
    }
}
