package com.tower.dam.core

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tower.dam.R
import com.tower.dam.data.DataManager
import com.tower.dam.data.ProxySessionPrefs
import kotlin.concurrent.thread

class ProxyForegroundService : Service() {
    private val channelId = "proxy_service_channel"
    private val notificationId = 1

    private val iptablesService = IptablesService()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * 用户从「最近任务」划掉与代理同一任务时触发，走与界面 STOP 相同的清理（iptables + sing-box）。
     * 与 [android:stopWithTask]="true" 配合，使代理随任务结束而结束，无需再进 App 点 STOP。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            startService(
                Intent(this, ProxyForegroundService::class.java).apply { action = ACTION_STOP }
            )
        } catch (e: Exception) {
            Log.w(TAG, "onTaskRemoved stop failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> {
                thread {
                    iptablesService.stop(this) { ok ->
                        if (ok) {
                            ProxySessionPrefs.setSessionActive(this, false)
                            sendProxyStateBroadcast(
                                running = false,
                                success = true,
                                operation = ProxyConstants.OP_STOP
                            )
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            sendProxyStateBroadcast(
                                running = true,
                                success = false,
                                operation = ProxyConstants.OP_STOP
                            )
                        }
                    }
                }
            }
            intent?.action == ACTION_START -> {
                val ip = intent.getStringExtra(EXTRA_IP) ?: "0.0.0.0"
                val port = intent.getIntExtra(EXTRA_PORT, 8889)
                val uids = intent.getIntArrayExtra(EXTRA_UIDS)?.toSet() ?: emptySet()
                beginStart(ip, port, uids)
            }
            intent == null -> {
                if (!ProxySessionPrefs.isSessionActive(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val (ip, port) = ProxySessionPrefs.loadLastEndpoint(this)
                val uids = DataManager.getUids(this)
                beginStart(ip, port, uids)
            }
            else -> {
                Log.w(TAG, "Unexpected onStartCommand action=${intent?.action} flags=$flags")
                abortForegroundServiceStartBecauseInvalidIntent()
            }
        }
        return START_STICKY
    }

    /**
     * 已通过 startForegroundService 拉起，但 Intent 未命中任何分支时必须尽快 startForeground，
     * 否则会触发 ForegroundServiceDidNotStartInTimeException 导致进程被系统结束。
     */
    private fun abortForegroundServiceStartBecauseInvalidIntent() {
        try {
            startForegroundWithType(
                buildNotification(
                    getString(R.string.proxy_notification_starting_title),
                    getString(R.string.fgs_unexpected_intent),
                    ongoing = false
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "abort FGS: startForeground failed", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun beginStart(ip: String, port: Int, uids: Set<Int>) {
        try {
            startForegroundWithType(
                buildNotification(
                    getString(R.string.proxy_notification_starting_title),
                    getString(R.string.proxy_notification_starting_text),
                    ongoing = true
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "beginStart: startForeground failed completely", e)
            sendProxyStateBroadcast(
                running = false,
                success = false,
                operation = ProxyConstants.OP_START
            )
            stopSelf()
            return
        }
        thread {
            iptablesService.start(this, ip, port, uids) { ok ->
                if (ok) {
                    ProxySessionPrefs.setSessionActive(this, true)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(
                        notificationId,
                        buildNotification(
                            getString(R.string.proxy_notification_running_title),
                            getString(R.string.proxy_notification_running_text),
                            ongoing = true
                        )
                    )
                    sendProxyStateBroadcast(
                        running = true,
                        success = true,
                        operation = ProxyConstants.OP_START
                    )
                } else {
                    sendProxyStateBroadcast(
                        running = false,
                        success = false,
                        operation = ProxyConstants.OP_START
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                ServiceCompat.startForeground(
                    this,
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                Log.w(TAG, "typed startForeground(dataSync) failed, fallback to legacy", e)
                startForeground(notificationId, notification)
            }
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun sendProxyStateBroadcast(running: Boolean, success: Boolean, operation: String) {
        sendBroadcast(
            Intent(ProxyConstants.ACTION_PROXY_STATE).apply {
                setPackage(packageName)
                putExtra(ProxyConstants.EXTRA_PROXY_RUNNING, running)
                putExtra(ProxyConstants.EXTRA_PROXY_SUCCESS, success)
                putExtra(ProxyConstants.EXTRA_PROXY_OPERATION, operation)
            }
        )
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(ongoing)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Proxy Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Maintains proxy connection"
                setShowBadge(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ProxyForegroundService"
        const val ACTION_START = "com.tower.dam.START"
        const val ACTION_STOP = "com.tower.dam.STOP"
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_UIDS = "uids"

        fun startService(context: Context, ip: String, port: Int, uids: Set<Int>) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_UIDS, uids.toIntArray())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isServiceRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val self = ProxyForegroundService::class.java.name
            @Suppress("DEPRECATION")
            return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == self }
        }
    }
}
