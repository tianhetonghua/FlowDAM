package com.tower.dam.core

import android.content.Context
import org.json.JSONObject // 必须引入
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class IptablesService : IProxyService {
    private val BIN_NAME = "sing-box"
    private val CONFIG_NAME = "config.json"

    override fun start(context: Context, ip: String, port: Int, uids: Set<Int>, callback: (Boolean) -> Unit) {
        thread {
            try {
                // 1. 释放 sing-box 二进制文件
                val binFile = File(context.filesDir, BIN_NAME)
                context.assets.open("bin/arm64-v8a/sing-box").use { input ->
                    FileOutputStream(binFile).use { input.copyTo(it) }
                }
                binFile.setExecutable(true)

                // 2. 【核心修改】直接修改私有目录下的 config.json
                val configFile = File(context.filesDir, CONFIG_NAME)
                if (configFile.exists()) {
                    // 读取现有文件内容
                    val content = configFile.readText()
                    val json = JSONObject(content)

                    // 精准定位并修改 IP 和 端口
                    val outbound = json.getJSONArray("outbounds").getJSONObject(0)
                    outbound.put("server", ip)
                    outbound.put("server_port", port)

                    // 写回文件，实现真正持久化修改
                    configFile.writeText(json.toString(2))
                }

                val logPath = File(context.filesDir, "log.txt").absolutePath

                // 3. 构建命令 (逻辑不变)
                val cmds = mutableListOf<String>()
                cmds.add("pkill -9 $BIN_NAME")
                cmds.add("iptables -t nat -F OUTPUT")

                // 屏蔽 QUIC
                cmds.add("iptables -t filter -I OUTPUT -p udp --dport 443 -j REJECT")

                uids.forEach { uid ->
                    cmds.add("iptables -t nat -A OUTPUT -p tcp -m owner --uid-owner $uid -j REDIRECT --to-ports 12345")
                }

                cmds.add("iptables -t nat -I OUTPUT -p udp --dport 53 -j RETURN")

                val startBox = "nohup ${binFile.absolutePath} run -c ${configFile.absolutePath} > $logPath 2>&1 &"
                cmds.add(startBox)

                val success = runAsRoot(cmds)
                callback(success)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(false)
            }
        }
    }

    override fun stop(context: Context, callback: (Boolean) -> Unit) {
        thread {
            val cmds = listOf(
                "pkill -9 $BIN_NAME",
                "iptables -t nat -F OUTPUT",
                "iptables -t filter -D OUTPUT -p udp --dport 443 -j REJECT 2>/dev/null"
            )
            callback(runAsRoot(cmds))
        }
    }

    private fun runAsRoot(cmds: List<String>): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su")
            p.outputStream.bufferedWriter().use { w ->
                for (cmd in cmds) {
                    w.write(cmd + "\n")
                }
                w.write("exit\n")
                w.flush()
            }
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}