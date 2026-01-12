package com.tower.dam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.tower.dam.core.IptablesService
import com.tower.dam.data.DataManager
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private var isRunning = false
    private val iptablesService = IptablesService()

    private val PREF_NAME = "dam_config"
    private val KEY_IP = "last_ip"
    private val KEY_PORT = "last_port"

    // 对应 IptablesService 里的文件名
    private val CONFIG_NAME = "config.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        initConfigFile()

        val etIp = findViewById<EditText>(R.id.etIp)
        val etPort = findViewById<EditText>(R.id.etPort)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnSelectApps = findViewById<Button>(R.id.btnSelectApps)

        loadConfigToUI(etIp, etPort)

        btnSelectApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        btnStart.setOnClickListener {
            val ip = etIp.text.toString().trim()
            val portString = etPort.text.toString().trim()
            val port = portString.toIntOrNull() ?: 8888
            val uids = DataManager.getUids(this)

            if (!isRunning) {
                iptablesService.start(this, ip, port, uids) { success ->
                    runOnUiThread {
                        if (success) {
                            isRunning = true
                            saveConfigToPrefs(ip, port)
                            btnStart.text = "STOP"
                            btnStart.setBackgroundColor(android.graphics.Color.RED)
                        } else {
                            Toast.makeText(this, "启动失败：请检查 Root 权限或日志", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                iptablesService.stop(this) { success ->
                    runOnUiThread {
                        isRunning = false
                        btnStart.text = "START"
                        btnStart.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                    }
                }
            }
        }
    }


    private fun initConfigFile() {
        val configFile = File(filesDir, CONFIG_NAME)
        if (!configFile.exists()) {
            try {
                // 假设你的 assets 根目录下有一个默认的 config_template.json 或 config.json
                // 这里必须确保 asset 文件名正确
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
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_IP, ip)
            putInt(KEY_PORT, port)
            apply()
        }
    }

    private fun loadConfigToUI(etIp: EditText, etPort: EditText) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedIp = prefs.getString(KEY_IP, "0.0.0.0")
        val savedPort = prefs.getInt(KEY_PORT, 8889)

        etIp.setText(savedIp)
        etPort.setText(savedPort.toString())
    }
}