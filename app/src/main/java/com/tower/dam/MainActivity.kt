package com.tower.dam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tower.dam.core.ProxyConstants
import com.tower.dam.core.ProxyForegroundService
import com.tower.dam.data.DataManager
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private var isRunning = false

    private val prefName = "dam_config"
    private val keyIp = "last_ip"
    private val keyPort = "last_port"
    private val keyBatteryPrompted = "battery_opt_prompted"

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var btnStart: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recoveryDialog: AlertDialog? = null

    private val proxyStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ProxyConstants.ACTION_PROXY_STATE) return
            val running = intent.getBooleanExtra(ProxyConstants.EXTRA_PROXY_RUNNING, false)
            val success = intent.getBooleanExtra(ProxyConstants.EXTRA_PROXY_SUCCESS, false)
            val op = intent.getStringExtra(ProxyConstants.EXTRA_PROXY_OPERATION) ?: return
            btnStart.isEnabled = true
            isRunning = running
            applyRunningUi()
            val app = application as DamApp
            val wasAutoRecovery = app.awaitingAutoRecoveryResult
            when {
                op == ProxyConstants.OP_START && success -> {
                    app.markKernelActive(DataManager.getUids(this@MainActivity))
                    val ip = etIp.text.toString().trim()
                    val port = etPort.text.toString().trim().toIntOrNull() ?: 8888
                    saveConfigToPrefs(ip, port)
                    if (wasAutoRecovery) {
                        app.awaitingAutoRecoveryResult = false
                        dismissRecoveryDialog()
                        Toast.makeText(this@MainActivity, R.string.toast_proxy_recovered, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "服务已启动", Toast.LENGTH_SHORT).show()
                    }
                }
                op == ProxyConstants.OP_START && !success -> {
                    if (wasAutoRecovery) {
                        app.awaitingAutoRecoveryResult = false
                        dismissRecoveryDialog()
                    }
                    Toast.makeText(this@MainActivity, R.string.toast_proxy_start_failed, Toast.LENGTH_SHORT).show()
                }
                op == ProxyConstants.OP_STOP && success -> {
                    app.markKernelClean()
                    Toast.makeText(this@MainActivity, "服务已停止", Toast.LENGTH_SHORT).show()
                }
                op == ProxyConstants.OP_STOP && !success ->
                    Toast.makeText(this@MainActivity, R.string.toast_proxy_stop_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initConfigFile()

        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        btnStart = findViewById(R.id.btnStart)
        val btnSelectApps = findViewById<Button>(R.id.btnSelectApps)

        loadConfigToUI(etIp, etPort)
        checkServiceStatus()

        btnSelectApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        btnStart.setOnClickListener {
            val ip = etIp.text.toString().trim()
            val portString = etPort.text.toString().trim()
            val port = portString.toIntOrNull() ?: 8888
            val uids = DataManager.getUids(this)

            if (!isRunning) {
                btnStart.isEnabled = false
                ProxyForegroundService.startService(this, ip, port, uids)
            } else {
                btnStart.isEnabled = false
                ProxyForegroundService.stopService(this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ProxyConstants.ACTION_PROXY_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(proxyStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(proxyStateReceiver, filter)
        }
        (application as DamApp).ensureIptablesProbed {
            checkServiceStatus()
            tryAutoRecoverInterruptedSession()
            mainHandler.postDelayed({ maybePromptBatteryOptimization() }, 900)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(proxyStateReceiver)
    }

    override fun onDestroy() {
        val app = application as DamApp
        if (app.awaitingAutoRecoveryResult) {
            app.awaitingAutoRecoveryResult = false
            app.autoRecoveryAttemptedThisProcess = false
        }
        dismissRecoveryDialog()
        super.onDestroy()
    }

    private fun dismissRecoveryDialog() {
        recoveryDialog?.dismiss()
        recoveryDialog = null
    }

    /**
     * 内核里仍是我们的 DAM_* 规则，但前台服务已不在：自动用上次 ip/port 与已同步的 UID 拉起 sing-box。
     */
    private fun tryAutoRecoverInterruptedSession() {
        val app = application as DamApp
        if (!app.kernelRulesActive) return
        if (ProxyForegroundService.isServiceRunning(this)) return
        if (app.autoRecoveryAttemptedThisProcess) return

        app.autoRecoveryAttemptedThisProcess = true
        app.awaitingAutoRecoveryResult = true

        val prefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val ip = prefs.getString(keyIp, "0.0.0.0") ?: "0.0.0.0"
        val port = prefs.getInt(keyPort, 8889)
        val uids = DataManager.getUids(this)

        recoveryDialog = AlertDialog.Builder(this)
            .setTitle(R.string.recovery_dialog_title)
            .setMessage(R.string.recovery_dialog_message)
            .setCancelable(false)
            .create()
        recoveryDialog?.show()

        btnStart.isEnabled = false
        ProxyForegroundService.startService(this, ip, port, uids)
        checkServiceStatus()
    }

    private fun maybePromptBatteryOptimization() {
        if (recoveryDialog?.isShowing == true) return
        val app = application as DamApp
        if (app.awaitingAutoRecoveryResult) return
        if (app.batteryOptimizationDialogShownThisProcess) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val prefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)
        if (prefs.getBoolean(keyBatteryPrompted, false)) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean(keyBatteryPrompted, true).apply()
            return
        }
        app.batteryOptimizationDialogShownThisProcess = true
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_opt_title)
            .setMessage(R.string.battery_opt_message)
            .setPositiveButton(R.string.battery_opt_open) { _, _ ->
                prefs.edit().putBoolean(keyBatteryPrompted, true).apply()
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton(R.string.battery_opt_later, null)
            .show()
    }

    private fun checkServiceStatus() {
        isRunning = ProxyForegroundService.isServiceRunning(this)
        applyRunningUi()
    }

    private fun applyRunningUi() {
        if (isRunning) {
            btnStart.text = "STOP"
            btnStart.setBackgroundColor(Color.RED)
        } else {
            btnStart.text = "START"
            btnStart.setBackgroundColor(Color.parseColor("#4CAF50"))
        }
    }

    private fun initConfigFile() {
        val configFile = File(filesDir, ProxyConstants.CONFIG_NAME)
        if (!configFile.exists()) {
            try {
                assets.open("config_template.json").use { input ->
                    FileOutputStream(configFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveConfigToPrefs(ip: String, port: Int) {
        getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().apply {
            putString(keyIp, ip)
            putInt(keyPort, port)
            apply()
        }
    }

    private fun loadConfigToUI(etIp: EditText, etPort: EditText) {
        val prefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val savedIp = prefs.getString(keyIp, "0.0.0.0")
        val savedPort = prefs.getInt(keyPort, 8889)
        etIp.setText(savedIp)
        etPort.setText(savedPort.toString())
    }
}
