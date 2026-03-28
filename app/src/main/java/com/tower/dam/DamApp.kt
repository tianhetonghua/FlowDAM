package com.tower.dam

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.tower.dam.core.IptablesService
import com.tower.dam.data.DataManager
import kotlin.concurrent.thread

class DamApp : Application() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeLock = Any()
    private var probeFinished = false
    private var probeStarted = false
    private val pendingAfterProbe = mutableListOf<() -> Unit>()

    @Volatile
    var kernelRulesActive: Boolean = false
        private set

    @Volatile
    var kernelRedirectUids: Set<Int> = emptySet()
        private set

    /** 本进程内是否已对「规则在、服务不在」尝试过自动拉起（失败则不再弹窗，需用户手动 START）。 */
    @Volatile
    var autoRecoveryAttemptedThisProcess: Boolean = false

    /** 正在等待自动恢复流程的 OP_START 结果，用于关闭弹窗与区分 Toast 文案。 */
    @Volatile
    var awaitingAutoRecoveryResult: Boolean = false

    /** 本进程内电池优化说明是否已弹出过（避免每次进首页都打扰）。 */
    @Volatile
    var batteryOptimizationDialogShownThisProcess: Boolean = false

    override fun onCreate() {
        super.onCreate()
        ensureIptablesProbed { }
    }

    fun ensureIptablesProbed(done: () -> Unit) {
        synchronized(probeLock) {
            if (probeFinished) {
                mainHandler.post(done)
                return
            }
            pendingAfterProbe.add(done)
            if (probeStarted) return
            probeStarted = true
        }
        thread {
            val state = IptablesService().probeKernelState()
            synchronized(probeLock) {
                kernelRulesActive = state.rulesActive
                kernelRedirectUids = state.redirectUids
                if (state.rulesActive && state.redirectUids.isNotEmpty()) {
                    DataManager.saveUids(this@DamApp, state.redirectUids)
                }
                probeFinished = true
                val callbacks = pendingAfterProbe.toList()
                pendingAfterProbe.clear()
                mainHandler.post {
                    callbacks.forEach { it() }
                }
            }
        }
    }

    fun markKernelClean() {
        kernelRulesActive = false
        kernelRedirectUids = emptySet()
        autoRecoveryAttemptedThisProcess = false
    }

    fun markKernelActive(uids: Set<Int>) {
        kernelRulesActive = true
        kernelRedirectUids = uids
    }
}
