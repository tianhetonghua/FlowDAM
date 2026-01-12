package com.tower.dam.core

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

class SingBoxVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var process: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val uids = intent?.getIntegerArrayListExtra("uids") ?: arrayListOf()
        val configPath = intent?.getStringExtra("config_path") ?: ""

        // 1. 建立虚拟网卡
        val builder = Builder()
            .setSession("DamProxy")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)

        // 2. 只允许勾选的 App 流量进入 VPN
        uids.forEach { uid ->
            val pkg = packageManager.getNameForUid(uid)
            if (pkg != null) builder.addAllowedApplication(pkg)
        }

        vpnInterface = builder.establish()

        // 3. 启动 sing-box 进程 (非 root 方式启动)
        if (vpnInterface != null) {
            startSingBox(configPath)
        }

        return START_STICKY
    }

    private fun startSingBox(configPath: String) {
        val binPath = File(filesDir, "sing-box").absolutePath
        // 注意：VPN 模式下 sing-box 作为普通子进程运行，不需要 su
        process = Runtime.getRuntime().exec("$binPath run -c $configPath")
    }

    private fun stopVpn() {
        process?.destroy()
        vpnInterface?.close()
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}