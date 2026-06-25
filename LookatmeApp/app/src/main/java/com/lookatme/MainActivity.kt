package com.lookatme

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var saveBtn: Button
    private lateinit var testBtn: Button
    private lateinit var authBtn: Button

    private lateinit var prefs: SharedPreferences
    private lateinit var syncWorker: SyncWorker
    private val handler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 10000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("lookatme", MODE_PRIVATE)
        syncWorker = SyncWorker(this)

        serverUrlInput = findViewById(R.id.serverUrl)
        statusText = findViewById(R.id.statusText)
        countText = findViewById(R.id.countText)
        saveBtn = findViewById(R.id.saveBtn)
        testBtn = findViewById(R.id.testBtn)
        authBtn = findViewById(R.id.authBtn)

        // Load saved server URL
        val savedUrl = prefs.getString("server_url", "") ?: ""
        serverUrlInput.setText(savedUrl)
        syncWorker.setServerUrl(savedUrl)

        saveBtn.setOnClickListener {
            val url = serverUrlInput.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("server_url", url).apply()
            syncWorker.setServerUrl(url)
            Toast.makeText(this, "地址已保存", Toast.LENGTH_SHORT).show()
        }

        testBtn.setOnClickListener {
            val url = serverUrlInput.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            syncWorker.setServerUrl(url)
            testBtn.isEnabled = false
            testBtn.text = "连接中..."
            Thread {
                val online = syncWorker.isServerOnline()
                runOnUiThread {
                    testBtn.isEnabled = true
                    testBtn.text = "测试连接"
                    if (online) {
                        statusText.text = "🟢 服务器在线"
                        statusText.setTextColor(0xFF00B894.toInt())
                        Toast.makeText(this, "连接成功！", Toast.LENGTH_SHORT).show()
                    } else {
                        statusText.text = "🔴 无法连接"
                        statusText.setTextColor(0xFFE17055.toInt())
                        Toast.makeText(this, "连接失败，请检查地址", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        authBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                startActivity(intent)
            } else {
                Toast.makeText(this, "需要 Android 8.0+ 支持", Toast.LENGTH_SHORT).show()
            }
        }

        checkPermission()
    }

    override fun onResume() {
        super.onResume()
        checkPermission()
        handler.post(syncRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(syncRunnable)
    }

    private fun checkPermission() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            authBtn.text = "✅ 已授权通知监听"
            authBtn.isEnabled = false
        } else {
            authBtn.text = "🔑 授权通知监听权限"
            authBtn.isEnabled = true
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(ComponentName(this, WeChatListenerService::class.java).flattenToString())
    }

    private fun updateStatus() {
        val db = LocalDatabase(this)
        val pendingCount = db.getCount()

        runOnUiThread {
            countText.text = "待同步: $pendingCount 条"
        }

        if (syncWorker.getServerUrl().isNotBlank()) {
            Thread {
                val online = syncWorker.isServerOnline()
                if (online && pendingCount > 0) {
                    val synced = syncWorker.sync()
                    if (synced > 0) {
                        val newCount = LocalDatabase(this).getCount()
                        runOnUiThread {
                            countText.text = "待同步: $newCount 条"
                            statusText.text = "🟢 已同步 $synced 条消息"
                            statusText.setTextColor(0xFF00B894.toInt())
                        }
                    }
                }
            }.start()
        }
    }
}
