package com.tower.dam.core

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class VpnProxyService : IProxyService {
    private val CONFIG_VPN = "config_vpn.json"

    override fun start(context: Context, ip: String, port: Int, uids: Set<Int>, callback: (Boolean) -> Unit) {
        try {
            // 1. 初始化专门的 VPN 配置文件
            val vpnFile = File(context.filesDir, CONFIG_VPN)
            val template = context.assets.open("config_vpn_template.json").bufferedReader().use { it.readText() }
            val json = JSONObject(template)

            // 修改代理服务器地址
            val outbound = json.getJSONArray("outbounds").getJSONObject(0)
            outbound.put("server", ip)
            outbound.put("server_port", port)
            vpnFile.writeText(json.toString(2))

            // 2. 启动 VpnService
            val intent = Intent(context, SingBoxVpnService::class.java).apply {
                putIntegerArrayListExtra("uids", ArrayList(uids))
                putExtra("config_path", vpnFile.absolutePath)
            }
            context.startService(intent)
            callback(true)
        } catch (e: Exception) {
            callback(false)
        }
    }

    override fun stop(context: Context, callback: (Boolean) -> Unit) {
        val intent = Intent(context, SingBoxVpnService::class.java).apply { action = "STOP" }
        context.startService(intent)
        callback(true)
    }
}