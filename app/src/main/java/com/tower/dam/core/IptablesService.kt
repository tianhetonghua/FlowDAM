package com.tower.dam.core

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class IptablesService {

    fun start(
        context: Context,
        ip: String,
        port: Int,
        uids: Set<Int>,
        callback: (Boolean) -> Unit
    ) {
        thread {
            try {
                val binFile = File(context.filesDir, ProxyConstants.BIN_NAME)
                extractSingBoxBinary(context, binFile)

                val configFile = File(context.filesDir, ProxyConstants.CONFIG_NAME)
                if (configFile.exists()) {
                    val json = JSONObject(configFile.readText())
                    val outbound = json.getJSONArray("outbounds").getJSONObject(0)
                    outbound.put("server", ip)
                    outbound.put("server_port", port)
                    configFile.writeText(json.toString(2))
                }

                val logPath = File(context.filesDir, "log.txt").absolutePath
                val natChain = ProxyConstants.IPTABLES_NAT_CHAIN
                val filterChain = ProxyConstants.IPTABLES_FILTER_CHAIN

                val cmds = mutableListOf<String>()
                cmds.add("pkill -9 ${ProxyConstants.BIN_NAME} 2>/dev/null || true")

                cmds.add("iptables -t nat -N $natChain 2>/dev/null || true")
                cmds.add("iptables -t nat -F $natChain")
                cmds.add("iptables -t nat -D OUTPUT -j $natChain 2>/dev/null || true")
                cmds.add("iptables -t nat -I OUTPUT 1 -j $natChain")
                cmds.add("iptables -t nat -A $natChain -p udp --dport 53 -j RETURN")
                uids.forEach { uid ->
                    cmds.add(
                        "iptables -t nat -A $natChain -p tcp -m owner --uid-owner $uid " +
                            "-j REDIRECT --to-ports 12345"
                    )
                }

                cmds.add("iptables -t filter -N $filterChain 2>/dev/null || true")
                cmds.add("iptables -t filter -F $filterChain")
                cmds.add("iptables -t filter -D OUTPUT -j $filterChain 2>/dev/null || true")
                cmds.add("iptables -t filter -I OUTPUT 1 -j $filterChain")
                cmds.add("iptables -t filter -A $filterChain -p udp --dport 443 -j DROP")

                val startBox =
                    "nohup ${binFile.absolutePath} run -c ${configFile.absolutePath} > $logPath 2>&1 &"
                cmds.add(startBox)

                val success = runAsRoot(cmds)
                callback(success)
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                callback(false)
            }
        }
    }

    fun stop(context: Context, callback: (Boolean) -> Unit) {
        thread {
            val natChain = ProxyConstants.IPTABLES_NAT_CHAIN
            val filterChain = ProxyConstants.IPTABLES_FILTER_CHAIN
            val cmds = listOf(
                "iptables -t filter -D OUTPUT -j $filterChain 2>/dev/null || true",
                "iptables -t filter -F $filterChain 2>/dev/null || true",
                "iptables -t filter -X $filterChain 2>/dev/null || true",
                "iptables -t nat -D OUTPUT -j $natChain 2>/dev/null || true",
                "iptables -t nat -F $natChain 2>/dev/null || true",
                "iptables -t nat -X $natChain 2>/dev/null || true",
                "pkill -9 ${ProxyConstants.BIN_NAME}"
            )
            callback(runAsRoot(cmds))
        }
    }

    /**
     * 通过 root 读取本应用使用的自定义链与 OUTPUT 跳转，解析 `--uid-owner`。
     * 用于进程被杀后根据内核状态恢复 AppList 勾选与提示用户恢复 sing-box。
     */
    fun probeKernelState(): KernelIptablesState {
        val natChain = ProxyConstants.IPTABLES_NAT_CHAIN
        val filterChain = ProxyConstants.IPTABLES_FILTER_CHAIN
        val script = """
            set +e
            echo ___NATOUT___
            iptables -t nat -S OUTPUT 2>/dev/null
            echo ___NATDAM___
            iptables -t nat -S $natChain 2>/dev/null
            echo ___FILTEROUT___
            iptables -t filter -S OUTPUT 2>/dev/null
            echo ___FILTERDAM___
            iptables -t filter -S $filterChain 2>/dev/null
            exit 0
        """.trimIndent()
        val raw = execSuCaptureStdout(script)
        return parseKernelProbe(raw, natChain, filterChain)
    }

    private fun execSuCaptureStdout(shellScript: String): String {
        return try {
            val p = Runtime.getRuntime().exec("su")
            var stdoutText = ""
            var stderrText = ""
            val outThread = thread {
                stdoutText = p.inputStream.bufferedReader().readText()
            }
            val errThread = thread {
                stderrText = p.errorStream.bufferedReader().readText()
            }
            p.outputStream.use { out ->
                out.write((shellScript + "\nexit\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
            p.waitFor()
            outThread.join()
            errThread.join()
            if (stderrText.isNotEmpty()) {
                Log.d(TAG, "probe su stderr: $stderrText")
            }
            stdoutText
        } catch (e: Exception) {
            Log.e(TAG, "probe su failed", e)
            ""
        }
    }

    private fun parseKernelProbe(raw: String, natChain: String, filterChain: String): KernelIptablesState {
        val natOut = sliceBetweenMarkers(raw, "___NATOUT___", "___NATDAM___")
        val natDam = sliceBetweenMarkers(raw, "___NATDAM___", "___FILTEROUT___")
        val filterOut = sliceBetweenMarkers(raw, "___FILTEROUT___", "___FILTERDAM___")
        val natJump = natOut.lineSequence().any { it.contains("-j $natChain") }
        val filterJump = filterOut.lineSequence().any { it.contains("-j $filterChain") }
        val uids = UID_OWNER_REGEX.findAll(natDam).map { it.groupValues[1].toInt() }.toSet()
        val rulesActive = natJump && filterJump && natDam.isNotBlank()
        return KernelIptablesState(rulesActive, uids)
    }

    private fun sliceBetweenMarkers(src: String, start: String, end: String): String {
        val i = src.indexOf(start)
        if (i < 0) return ""
        val from = i + start.length
        val j = src.indexOf(end, from)
        if (j < 0) return src.substring(from).trim()
        return src.substring(from, j).trim()
    }

    /**
     * sing-box 正在运行时直接覆盖 [binFile] 会 ETXTBSY。先 pkill、再 unlink，必要时经临时文件替换。
     */
    private fun extractSingBoxBinary(context: Context, binFile: File) {
        val tmpFile = File(context.filesDir, "${ProxyConstants.BIN_NAME}.new")
        killSingBoxProcessBestEffort()
        sleepQuiet(200)
        tmpFile.delete()
        if (!binFile.delete()) {
            Log.d(TAG, "sing-box delete returned false (may be absent or busy inode)")
        }
        try {
            writeSingBoxFromAssets(context, tmpFile)
            swapSingBoxTempToFinal(tmpFile, binFile)
        } catch (e: Exception) {
            if (!isErrnoTextBusy(e)) throw e
            Log.w(TAG, "sing-box ETXTBSY, retry after kill+rm", e)
            killSingBoxProcessBestEffort()
            sleepQuiet(400)
            runAsRoot(listOf("rm -f ${shellSingleQuoted(binFile.absolutePath)}"))
            tmpFile.delete()
            writeSingBoxFromAssets(context, tmpFile)
            swapSingBoxTempToFinal(tmpFile, binFile)
        }
        binFile.setExecutable(true)
    }

    private fun writeSingBoxFromAssets(context: Context, dest: File) {
        dest.delete()
        context.assets.open("bin/arm64-v8a/sing-box").use { input ->
            FileOutputStream(dest).use { input.copyTo(it) }
        }
        dest.setExecutable(true)
    }

    /** 先写到从未执行过的 .new，再替换最终路径，避免覆盖正在 mmap 的可执行文件。 */
    private fun swapSingBoxTempToFinal(tmpFile: File, binFile: File) {
        if (!tmpFile.renameTo(binFile)) {
            binFile.delete()
            if (!tmpFile.renameTo(binFile)) {
                tmpFile.inputStream().use { input ->
                    FileOutputStream(binFile).use { input.copyTo(it) }
                }
                tmpFile.delete()
            }
        }
    }

    private fun killSingBoxProcessBestEffort() {
        runAsRoot(listOf("pkill -9 ${ProxyConstants.BIN_NAME} 2>/dev/null || true"))
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun isErrnoTextBusy(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is ErrnoException && t.errno == OsConstants.ETXTBSY) return true
            if (t.message?.contains("ETXTBSY") == true) return true
            t = t.cause
        }
        return false
    }

    private fun shellSingleQuoted(path: String): String {
        return "'${path.replace("'", "'\\''")}'"
    }

    private fun runAsRoot(cmds: List<String>): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su")
            var stderrText = ""
            var stdoutText = ""
            val outThread = thread {
                stdoutText = p.inputStream.bufferedReader().readText()
            }
            val errThread = thread {
                stderrText = p.errorStream.bufferedReader().readText()
            }
            p.outputStream.use { out ->
                val script = cmds.joinToString("\n", postfix = "\nexit\n")
                out.write(script.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val exitCode = p.waitFor()
            outThread.join()
            errThread.join()
            if (stderrText.isNotEmpty()) {
                Log.e(TAG, "su stderr: $stderrText")
            }
            if (stdoutText.isNotEmpty()) {
                Log.d(TAG, "su stdout: $stdoutText")
            }
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root failed", e)
            false
        }
    }

    private companion object {
        private const val TAG = "IptablesService"
        private val UID_OWNER_REGEX = Regex("""--uid-owner\s+(\d+)""")
    }
}
