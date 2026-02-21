package com.tower.dam.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tower.dam.R
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class ProxyForegroundService : Service() {
    private val BIN_NAME = "sing-box"
    private val CONFIG_NAME = "config.json"
    private val CHANNEL_ID = "proxy_service_channel"
    private val NOTIFICATION_ID = 1
    
    private var isRunning = false
    private val iptablesService = IptablesService()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            ACTION_START -> {
                val ip = intent.getStringExtra(EXTRA_IP) ?: "0.0.0.0"
                val port = intent.getIntExtra(EXTRA_PORT, 8889)
                val uids = intent.getIntArrayExtra(EXTRA_UIDS)?.toSet() ?: emptySet()
                
                startProxy(ip, port, uids)
            }
            ACTION_STOP -> {
                stopProxy()
            }
        }

        return START_STICKY
    }

    private fun startProxy(ip: String, port: Int, uids: Set<Int>) {
        thread {
            iptablesService.start(this, ip, port, uids) {
                if (it) {
                    isRunning = true
                    startForeground(NOTIFICATION_ID, createNotification())
                }
            }
        }
    }

    private fun stopProxy() {
        thread {
            iptablesService.stop(this) {
                if (it) {
                    isRunning = false
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Maintains proxy connection"
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DAM Proxy")
            .setContentText("Running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.tower.dam.START"
        const val ACTION_STOP = "com.tower.dam.STOP"
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_UIDS = "uids"

        fun startService(context: Context, ip: String, port: Int, uids: Set<Int>) {
            val intent = Intent(context, ProxyForegroundService::class.java)
            intent.action = ACTION_START
            intent.putExtra(EXTRA_IP, ip)
            intent.putExtra(EXTRA_PORT, port)
            intent.putExtra(EXTRA_UIDS, uids.toIntArray())
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }

        fun isServiceRunning(context: Context): Boolean {
            val iptablesService = IptablesService()
            return try {
                // 检测sing-box进程是否在运行
                val process = Runtime.getRuntime().exec("su -c ps | grep sing-box")
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                output.contains("sing-box")
            } catch (e: Exception) {
                false
            }
        }
    }
}
