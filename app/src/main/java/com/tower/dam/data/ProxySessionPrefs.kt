package com.tower.dam.data

import android.content.Context

/**
 * 与 MainActivity 共用 [PREF_NAME]，用于系统重启前台服务后从磁盘恢复参数。
 */
object ProxySessionPrefs {
    private const val PREF_NAME = "dam_config"
    private const val KEY_SESSION_ACTIVE = "proxy_session_active"
    private const val KEY_LAST_IP = "last_ip"
    private const val KEY_LAST_PORT = "last_port"

    fun setSessionActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SESSION_ACTIVE, active)
            .apply()
    }

    fun isSessionActive(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SESSION_ACTIVE, false)

    fun loadLastEndpoint(context: Context): Pair<String, Int> {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val ip = p.getString(KEY_LAST_IP, "0.0.0.0") ?: "0.0.0.0"
        val port = p.getInt(KEY_LAST_PORT, 8889)
        return Pair(ip, port)
    }
}
