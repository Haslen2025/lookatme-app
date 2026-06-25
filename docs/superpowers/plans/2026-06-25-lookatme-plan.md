# Lookatme 微信监护监测系统 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 Android APK + Flask 后端系统，监听微信通知消息并同步到局域网服务器，监护人通过 Web 页面查看。

**Architecture:** APK 通过 NotificationListenerService 捕获微信通知文本，本地 SQLite 缓存，检测到服务器在线后批量 POST 同步。Flask 后端提供 REST API 存储消息和 Web 页面展示。

**Tech Stack:** Python Flask + SQLite（后端）、Kotlin + Android SDK（APK）、GitHub Actions（APK 构建）

---

### Task 1: Flask 后端 — 数据模型和 API

**Files:**
- Create: `D:\PythonProject\Lookatme\server\models.py`
- Create: `D:\PythonProject\Lookatme\server\app.py`
- Create: `D:\PythonProject\Lookatme\server\requirements.txt`

- [ ] **Step 1: Create requirements.txt**

```
flask==3.1.1
```

- [ ] **Step 2: Create models.py**

```python
import sqlite3
import os
from datetime import datetime


DB_PATH = os.path.join(os.path.dirname(__file__), 'messages.db')


def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA journal_mode=WAL')
    return conn


def init_db():
    conn = get_db()
    conn.execute('''
        CREATE TABLE IF NOT EXISTS messages (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            sender      TEXT NOT NULL,
            content     TEXT NOT NULL,
            msg_type    TEXT DEFAULT 'text',
            room_name   TEXT DEFAULT '',
            msg_time    DATETIME NOT NULL,
            sync_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
            msg_id      TEXT UNIQUE
        )
    ''')
    conn.execute('CREATE INDEX IF NOT EXISTS idx_msg_time ON messages(msg_time DESC)')
    conn.execute('CREATE INDEX IF NOT EXISTS idx_msg_id ON messages(msg_id)')
    conn.commit()
    conn.close()


def insert_messages(messages):
    conn = get_db()
    inserted = 0
    for msg in messages:
        try:
            conn.execute('''
                INSERT OR IGNORE INTO messages (sender, content, msg_type, room_name, msg_time, msg_id)
                VALUES (?, ?, ?, ?, ?, ?)
            ''', (
                msg['sender'],
                msg['content'],
                msg.get('msg_type', 'text'),
                msg.get('room_name', ''),
                msg['msg_time'],
                msg['msg_id'],
            ))
            if conn.total_changes > 0:
                inserted += 1
        except Exception:
            pass
    conn.commit()
    conn.close()
    return inserted


def query_messages(page=1, per_page=50, q=None, date=None):
    conn = get_db()
    conditions = []
    params = []

    if q:
        conditions.append('(sender LIKE ? OR content LIKE ?)')
        params.extend([f'%{q}%', f'%{q}%'])
    if date:
        conditions.append('date(msg_time) = ?')
        params.append(date)

    where = 'WHERE ' + ' AND '.join(conditions) if conditions else ''
    total = conn.execute(f'SELECT COUNT(*) FROM messages {where}', params).fetchone()[0]

    offset = (page - 1) * per_page
    rows = conn.execute(
        f'SELECT * FROM messages {where} ORDER BY msg_time DESC LIMIT ? OFFSET ?',
        params + [per_page, offset]
    ).fetchall()

    conn.close()
    return total, [dict(r) for r in rows]
```

- [ ] **Step 3: Create app.py**

```python
#!/usr/bin/env python3
from flask import Flask, request, jsonify, render_template, Response
from models import init_db, insert_messages, query_messages
from datetime import datetime
import os

app = Flask(__name__)
app.template_folder = os.path.join(os.path.dirname(__file__), 'templates')


@app.before_request
def init():
    if not hasattr(app, '_db_inited'):
        init_db()
        app._db_inited = True


@app.route('/api/health')
def health():
    return jsonify({'status': 'ok', 'time': datetime.now().isoformat()})


@app.route('/api/upload', methods=['POST'])
def upload():
    data = request.get_json(silent=True)
    if not data or 'messages' not in data or not isinstance(data['messages'], list):
        return jsonify({'error': 'invalid format'}), 400

    count = insert_messages(data['messages'])
    return jsonify({'synced': count})


@app.route('/api/messages')
def get_messages():
    page = request.args.get('page', 1, type=int)
    per_page = request.args.get('per_page', 50, type=int)
    q = request.args.get('q')
    date = request.args.get('date')

    total, messages = query_messages(page=page, per_page=per_page, q=q, date=date)
    return jsonify({
        'total': total,
        'page': page,
        'per_page': per_page,
        'messages': messages,
    })


@app.route('/')
def index():
    return render_template('index.html')


if __name__ == '__main__':
    print(' * Lookatme server starting on http://0.0.0.0:5000')
    app.run(host='0.0.0.0', port=5000, debug=True)
```

- [ ] **Step 4: Test the Flask server**

Run: `cd D:/PythonProject/Lookatme/server && pip install flask && python app.py`
Expected: Server starts on port 5000

- [ ] **Step 5: Test API endpoints**

Run in another terminal:
```bash
# health check
curl http://localhost:5000/api/health
# Expected: {"status":"ok","time":"..."}

# upload test message
curl -X POST http://localhost:5000/api/upload \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"sender":"测试","content":"你好","msg_type":"text","room_name":"测试","msg_time":"2026-06-25 18:00:00","msg_id":"test001"}]}'
# Expected: {"synced":1}

# query messages
curl http://localhost:5000/api/messages
# Expected: {"total":1,"messages":[{...}]}
```

- [ ] **Step 6: Install Flask**

```bash
pip install flask
```

---

### Task 2: Flask 后端 — Web 查看页面

**Files:**
- Create: `D:\PythonProject\Lookatme\server\templates\index.html`
- Create: `D:\PythonProject\Lookatme\server\static\style.css`

- [ ] **Step 1: Create templates/index.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Lookatme - 消息监护</title>
    <link rel="stylesheet" href="/static/style.css">
</head>
<body>
    <header>
        <h1>📋 Lookatme</h1>
        <div class="status" id="status">🟢 服务器运行中</div>
    </header>

    <div class="toolbar">
        <input type="text" id="searchBox" placeholder="搜索发送人或消息内容..." oninput="doSearch()">
        <input type="date" id="dateFilter" onchange="doSearch()">
        <button onclick="clearDate()">清除日期</button>
        <span class="count" id="count">共 0 条</span>
    </div>

    <div id="messages"></div>
    <div class="pagination" id="pagination"></div>

    <script>
        var currentPage = 1;
        var currentQ = '';
        var currentDate = '';

        function loadMessages(page) {
            currentPage = page;
            var params = 'page=' + page + '&per_page=50';
            if (currentQ) params += '&q=' + encodeURIComponent(currentQ);
            if (currentDate) params += '&date=' + currentDate;

            fetch('/api/messages?' + params)
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    document.getElementById('count').textContent = '共 ' + data.total + ' 条';
                    var html = '';
                    var lastDate = '';
                    data.messages.forEach(function(m) {
                        var d = m.msg_time.substring(0, 10);
                        if (d !== lastDate) {
                            html += '<div class="date-group">' + d + '</div>';
                            lastDate = d;
                        }
                        var avatar = m.sender.charAt(0);
                        html += '<div class="msg-card">' +
                            '<div class="avatar">' + avatar + '</div>' +
                            '<div class="body">' +
                                '<div class="sender">' + escapeHtml(m.sender) + '</div>' +
                                '<div class="content">' + escapeHtml(m.content) + '</div>' +
                                '<div class="time">' + m.msg_time + '</div>' +
                            '</div>' +
                        '</div>';
                    });
                    if (data.messages.length === 0) {
                        html = '<div class="empty">暂无消息</div>';
                    }
                    document.getElementById('messages').innerHTML = html;
                    renderPagination(data.total, data.page);
                });
        }

        function renderPagination(total, page) {
            var totalPages = Math.ceil(total / 50);
            if (totalPages <= 1) {
                document.getElementById('pagination').innerHTML = '';
                return;
            }
            var html = '';
            if (page > 1) html += '<button onclick="loadMessages(' + (page-1) + ')">‹ 上一页</button>';
            html += '<span class="page-info">第 ' + page + ' / ' + totalPages + ' 页</span>';
            if (page < totalPages) html += '<button onclick="loadMessages(' + (page+1) + ')">下一页 ›</button>';
            document.getElementById('pagination').innerHTML = html;
        }

        function doSearch() {
            currentQ = document.getElementById('searchBox').value;
            currentDate = document.getElementById('dateFilter').value;
            loadMessages(1);
        }

        function clearDate() {
            document.getElementById('dateFilter').value = '';
            doSearch();
        }

        function escapeHtml(s) {
            if (!s) return '';
            return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
        }

        // Auto refresh every 5 seconds
        setInterval(function() {
            loadMessages(currentPage);
        }, 5000);

        loadMessages(1);
    </script>
</body>
</html>
```

- [ ] **Step 2: Create static/style.css**

```css
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif; background: #f5f6fa; color: #2d3436; }

header { background: #0984e3; color: #fff; padding: 16px 24px; display: flex; align-items: center; gap: 16px; }
header h1 { font-size: 20px; }
.status { font-size: 12px; opacity: 0.8; }

.toolbar { display: flex; align-items: center; gap: 8px; padding: 12px 24px; background: #fff; border-bottom: 1px solid #dfe6e9; }
.toolbar input[type="text"] { flex: 1; max-width: 300px; padding: 6px 12px; border: 1px solid #dfe6e9; border-radius: 6px; font-size: 14px; }
.toolbar input[type="date"] { padding: 6px 12px; border: 1px solid #dfe6e9; border-radius: 6px; font-size: 14px; }
.toolbar button { padding: 6px 12px; border: 1px solid #dfe6e9; border-radius: 6px; background: #fff; cursor: pointer; font-size: 13px; }
.toolbar button:hover { background: #f0f0f0; }
.count { margin-left: auto; font-size: 13px; color: #636e72; }

#messages { max-width: 720px; margin: 0 auto; padding: 16px; }

.date-group { font-size: 12px; color: #b2bec3; padding: 12px 0 8px; font-weight: 600; letter-spacing: 1px; }

.msg-card { display: flex; gap: 12px; padding: 12px 16px; background: #fff; border-radius: 10px; margin-bottom: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.msg-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.1); }

.avatar { width: 40px; height: 40px; border-radius: 50%; background: #0984e3; color: #fff; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 600; flex-shrink: 0; }

.body { flex: 1; min-width: 0; }
.sender { font-size: 14px; font-weight: 600; color: #2d3436; margin-bottom: 2px; }
.content { font-size: 15px; color: #2d3436; line-height: 1.5; word-break: break-all; }
.time { font-size: 11px; color: #b2bec3; margin-top: 4px; }

.empty { text-align: center; color: #b2bec3; padding: 40px 0; font-size: 14px; }

.pagination { text-align: center; padding: 16px; }
.pagination button { padding: 6px 16px; border: 1px solid #dfe6e9; border-radius: 6px; background: #fff; cursor: pointer; font-size: 13px; }
.pagination button:hover { background: #f0f0f0; }
.page-info { margin: 0 12px; font-size: 13px; color: #636e72; }

@media (max-width: 600px) {
    header { padding: 12px 16px; }
    .toolbar { flex-wrap: wrap; padding: 8px 16px; }
    .toolbar input[type="text"] { max-width: 100%; flex: 1 1 100%; }
    #messages { padding: 12px; }
}
```

- [ ] **Step 3: Test the Web page**

Run: `cd D:/PythonProject/Lookatme/server && python app.py`
Open browser: `http://localhost:5000`
Expected: See the Web page with "暂无消息" and uploaded test message from Task 1

---

### Task 3: Android APK — 项目脚手架

**Files:**
- Create: `D:\PythonProject\Lookatme\LookatmeApp\settings.gradle.kts`
- Create: `D:\PythonProject\Lookatme\LookatmeApp\build.gradle.kts`
- Create: `D:\PythonProject\Lookatme\LookatmeApp\app\build.gradle.kts`
- Create: `D:\PythonProject\Lookatme\LookatmeApp\gradle\wrapper\gradle-wrapper.properties`
- Create: `D:\PythonProject\Lookatme\LookatmeApp\app\src\main\AndroidManifest.xml`
- Create: `D:\PythonProject\Lookatme\LookatmeApp\app\src\main\res\values\strings.xml`
- Create: `D:\PythonProject\Lookatme\LookatmeApp\app\src\main\res\drawable\ic_launcher.xml`

- [ ] **Step 1: Create settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Lookatme"
include(":app")
```

- [ ] **Step 2: Create root build.gradle.kts**

```kotlin
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
```

- [ ] **Step 3: Create app/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lookatme"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lookatme"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
```

- [ ] **Step 4: Create gradle-wrapper.properties**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 5: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".WeChatListenerService"
            android:exported="true"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>

    </application>
</manifest>
```

- [ ] **Step 6: Create res/values/strings.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Lookatme</string>
</resources>
```

- [ ] **Step 7: Create res/drawable/ic_launcher.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <path
        android:fillColor="#0984E3"
        android:pathData="M24,4C12.95,4 4,12.95 4,24s8.95,20 20,20 20,-8.95 20,-20S35.05,4 24,4z"/>
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M24,14c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6zM16,30c0,-2.67 5.33,-4 8,-4s8,1.33 8,4v2H16v-2z"/>
</vector>
```

---

### Task 4: Android APK — 本地数据库

**Files:**
- Create: `D:\PythonProject\Lookatme\LookatmeApp\app\src\main\java\com\lookatme\LocalDatabase.kt`

- [ ] **Step 1: Create LocalDatabase.kt**

```kotlin
package com.lookatme

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalDatabase(context: Context) : SQLiteOpenHelper(
    context, "lookatme_cache.db", null, 1
) {
    companion object {
        const val TABLE = "pending_messages"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                sender      TEXT NOT NULL,
                content     TEXT NOT NULL,
                msg_type    TEXT DEFAULT 'text',
                room_name   TEXT DEFAULT '',
                msg_time    TEXT NOT NULL,
                msg_id      TEXT UNIQUE
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insertMessage(
        sender: String,
        content: String,
        msgType: String = "text",
        roomName: String = "",
        msgTime: String,
        msgId: String
    ): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("sender", sender)
            put("content", content)
            put("msg_type", msgType)
            put("room_name", roomName)
            put("msg_time", msgTime)
            put("msg_id", msgId)
        }
        val result = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return result != -1L
    }

    fun getPendingMessages(limit: Int = 20): List<Map<String, String>> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE ORDER BY id ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        val messages = mutableListOf<Map<String, String>>()
        while (cursor.moveToNext()) {
            messages.add(mapOf(
                "id" to cursor.getLong(0).toString(),
                "sender" to cursor.getString(1),
                "content" to cursor.getString(2),
                "msg_type" to cursor.getString(3),
                "room_name" to cursor.getString(4),
                "msg_time" to cursor.getString(5),
                "msg_id" to cursor.getString(6)
            ))
        }
        cursor.close()
        return messages
    }

    fun deleteMessages(ids: List<String>) {
        val db = writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        db.execSQL("DELETE FROM $TABLE WHERE id IN ($placeholders)", ids.toTypedArray())
    }

    fun getCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()
        return count
    }
}
```

---

### Task 5: Android APK — 通知监听服务

**Files:**
- Create: `D:\PythonProject\Lookatme\LookatmeApp\app\src\main\java\com\lookatme\WeChatListenerService.kt`

- [ ] **Step 1: Create WeChatListenerService.kt**

```kotlin
package com.lookatme

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeChatListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "Lookatme"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val MAX_CONTENT_LENGTH = 500

        // 群聊关键词（通知标题或内容中包含这些视为群聊）
        private val GROUP_KEYWORDS = listOf("群聊", "群", "群组", "群消息", "微信团队")

        var lastSyncCount = 0
            private set
    }

    override fun onListenerConnected() {
        Log.d(TAG, "通知监听服务已连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 只处理微信的消息
        if (sbn.packageName != WECHAT_PACKAGE) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 获取通知标题和内容
        val title = extras.getString(Notification.EXTRA_TITLE, "")
        val text = extras.getString(Notification.EXTRA_TEXT, "")
            ?: extras.getString(Notification.EXTRA_BIG_TEXT, "")
            ?: ""

        if (title.isBlank() || text.isBlank()) return

        // 过滤群聊
        if (isGroupChat(title, text)) {
            Log.d(TAG, "过滤群聊消息: $title")
            return
        }

        // 过滤非文本类型的通知
        if (text.startsWith("[") || isMediaMessage(text)) return

        // 裁剪过长内容
        val content = if (text.length > MAX_CONTENT_LENGTH) {
            text.substring(0, MAX_CONTENT_LENGTH) + "..."
        } else text

        val msgId = sbn.key
        val msgTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(sbn.postTime))

        // 存入本地数据库
        val db = LocalDatabase(this)
        val inserted = db.insertMessage(
            sender = title,
            content = content,
            msgType = "text",
            roomName = title,
            msgTime = msgTime,
            msgId = msgId
        )

        if (inserted) {
            Log.d(TAG, "捕获消息: [$title] $content")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 不需要处理
    }

    private fun isGroupChat(title: String, text: String): Boolean {
        val combined = "$title $text"
        return GROUP_KEYWORDS.any { combined.contains(it) }
    }

    private fun isMediaMessage(text: String): Boolean {
        val mediaPrefixes = listOf("[图片]", "[表情]", "[语音]", "[视频]", "[文件]", "[位置]", "[红包]",
            "[转让]", "[转账]", "[动画表情]", "[链接]", "[小程序]", "[卡片]")
        return mediaPrefixes.any { text.startsWith(it) }
    }
}
```

---

### Task 6: Android APK — 同步服务

**Files:**
- Create: `D:\PythonProject\Lookatme\LookatmeApp\app\src\main\java\com\lookatme\SyncWorker.kt`

- [ ] **Step 1: Create SyncWorker.kt**

```kotlin
package com.lookatme

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SyncWorker(private val context: Context) {

    companion object {
        private const val TAG = "Lookatme_Sync"
        private const val HEALTH_TIMEOUT = 5000
        private const val UPLOAD_TIMEOUT = 10000
        private const val MAX_BATCH = 20
    }

    private var serverUrl: String = ""

    fun setServerUrl(url: String) {
        serverUrl = url.trimEnd('/')
    }

    fun getServerUrl(): String = serverUrl

    /**
     * 检测服务器是否在线
     */
    fun isServerOnline(): Boolean {
        if (serverUrl.isBlank()) return false
        return try {
            val url = URL("$serverUrl/api/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = HEALTH_TIMEOUT
            conn.readTimeout = HEALTH_TIMEOUT
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            Log.d(TAG, "服务器不在线: ${e.message}")
            false
        }
    }

    /**
     * 同步待发送消息
     * 返回值: 成功同步的数量
     */
    fun sync(): Int {
        if (serverUrl.isBlank()) return 0

        val db = LocalDatabase(context)
        val pending = db.getPendingMessages(MAX_BATCH)
        if (pending.isEmpty()) return 0

        try {
            val jsonArray = JSONArray()
            for (msg in pending) {
                val obj = JSONObject().apply {
                    put("sender", msg["sender"])
                    put("content", msg["content"])
                    put("msg_type", msg["msg_type"])
                    put("room_name", msg["room_name"])
                    put("msg_time", msg["msg_time"])
                    put("msg_id", msg["msg_id"])
                }
                jsonArray.put(obj)
            }

            val body = JSONObject().apply {
                put("messages", jsonArray)
            }

            val url = URL("$serverUrl/api/upload")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = UPLOAD_TIMEOUT
            conn.readTimeout = UPLOAD_TIMEOUT
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val response = JSONObject(responseStr)
                val synced = response.optInt("synced", 0)

                if (synced > 0) {
                    // 删除已同步的本地记录
                    val syncedIds = pending.take(synced).mapNotNull { it["id"] }
                    db.deleteMessages(syncedIds)
                    Log.d(TAG, "同步成功: $synced 条")
                }

                conn.disconnect()
                return synced
            }

            conn.disconnect()
            return 0
        } catch (e: Exception) {
            Log.e(TAG, "同步失败: ${e.message}")
            return 0
        }
    }
}
```

---

### Task 7: Android APK — 主界面

**Files:**
- Create: `D:\PythonProject\Lookatme\LookatmeApp\app\src\main\java\com\lookatme\MainActivity.kt`
- Create: `D:\PythonProject\Lookatme\LookatmeApp\app\src\main\res\layout\activity_main.xml`
- Create: `D:\PythonProject\Lookatme\LookatmeApp\app\src\main\res\values\colors.xml`

- [ ] **Step 1: Create activity_main.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="20dp"
    android:background="#F5F6FA">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Lookatme"
        android:textSize="24sp"
        android:textColor="#0984E3"
        android:textStyle="bold"
        android:layout_marginBottom="4dp"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="微信消息监护服务"
        android:textSize="14sp"
        android:textColor="#636E72"
        android:layout_marginBottom="24dp"/>

    <!-- 服务器设置 -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="服务器地址"
        android:textSize="14sp"
        android:textColor="#2D3436"
        android:layout_marginBottom="4dp"/>

    <EditText
        android:id="@+id/serverUrl"
        android:layout_width="match_parent"
        android:layout_height="44dp"
        android:hint="例如: http://192.168.1.100:5000"
        android:textSize="14sp"
        android:background="@drawable/edit_bg"
        android:padding="12dp"
        android:inputType="textUri"
        android:layout_marginBottom="16dp"/>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <Button
            android:id="@+id/saveBtn"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_weight="1"
            android:text="保存地址"
            android:textSize="14sp"
            android:backgroundTint="#0984E3"
            android:layout_marginEnd="8dp"/>

        <Button
            android:id="@+id/testBtn"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_weight="1"
            android:text="测试连接"
            android:textSize="14sp"
            android:backgroundTint="#00B894"
            android:layout_marginStart="8dp"/>
    </LinearLayout>

    <!-- 状态信息 -->
    <TextView
        android:id="@+id/statusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="⚪ 未开始"
        android:textSize="16sp"
        android:textColor="#636E72"
        android:gravity="center"
        android:layout_marginTop="24dp"
        android:padding="16dp"
        android:background="#FFFFFF"
        android:layout_marginBottom="16dp"/>

    <TextView
        android:id="@+id/countText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="待同步: 0 条"
        android:textSize="14sp"
        android:textColor="#636E72"
        android:gravity="center"
        android:layout_marginBottom="16dp"/>

    <!-- 授权按钮 -->
    <Button
        android:id="@+id/authBtn"
        android:layout_width="match_parent"
        android:layout_height="48dp"
        android:text="授权通知监听权限"
        android:textSize="14sp"
        android:backgroundTint="#E17055"
        android:layout_marginBottom="8dp"/>

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="需要先授权才能监听微信消息通知"
        android:textSize="11sp"
        android:textColor="#B2BEC3"
        android:gravity="center"/>

</LinearLayout>
```

- [ ] **Step 2: Create res/values/colors.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="primary">#0984E3</color>
    <color name="primary_dark">#0765B3</color>
    <color name="accent">#00B894</color>
    <color name="background">#F5F6FA</color>
    <color name="text_primary">#2D3436</color>
    <color name="text_secondary">#636E72</color>
</resources>
```

- [ ] **Step 3: Create drawable/edit_bg.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#FFFFFF"/>
    <stroke android:width="1dp" android:color="#DFE6E9"/>
    <corners android:radius="6dp"/>
</shape>
```

- [ ] **Step 4: Create MainActivity.kt**

```kotlin
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
            handler.postDelayed(this, 10000) // 每10秒更新状态
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

        // 加载已保存的服务器地址
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

        // 检查是否已授权
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
```

---

### Task 8: Android APK — GitHub Actions 构建

由于当前开发环境没有 JDK 和 Android SDK，我们使用 GitHub Actions 自动构建 APK。

**Files:**
- Create: `D:\PythonProject\Lookatme\.github\workflows\build-apk.yml`

- [ ] **Step 1: Create GitHub Actions workflow**

```yaml
name: Build APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: LookatmeApp

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build APK
        run: ./gradlew assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: lookatme-apk
          path: LookatmeApp/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: 创建 gradlew wrapper 脚本**

项目还需要 gradlew 和 gradlew.bat 脚本。这些可以从 Android 项目模板中复制，或者通过 gradle init 生成。在 GitHub Actions 中会使用 gradle wrapper。

---

### Task 9: 编写部署说明

**Files:**
- Create: `D:\PythonProject\Lookatme\README.md`

- [ ] **Step 1: Create README.md**

```markdown
# Lookatme - 微信消息监护系统

监听平板微信通知消息，同步到局域网服务器，监护人通过网页查看。

## 系统架构

平板 (APK) → 局域网 HTTP → 电脑 (Flask 服务器) → 浏览器查看

## 部署步骤

### 1. 启动服务器（电脑端）

```bash
cd server
pip install flask
python app.py
```

浏览器打开 `http://localhost:5000` 即可查看消息。

### 2. 安装 APK（平板端）

1. 从 GitHub Actions 下载 APK 文件
2. 在平板上安装 APK
3. 打开 App，输入电脑的局域网 IP 地址（如 `http://192.168.1.100:5000`）
4. 点击「授权通知监听权限」并开启
5. 保存地址即可自动运行

### 3. 查看消息

电脑浏览器打开 `http://localhost:5000` 或同局域网其他设备访问 `http://电脑IP:5000`

## 查找电脑局域网 IP

Windows: 打开命令提示符，输入 `ipconfig`，找到 IPv4 地址
```

---

## 执行顺序

1. **Task 1-2:** Flask 后端（可先在本地测试）
2. **Task 3-7:** Android APK 源码
3. **Task 8:** GitHub Actions 构建配置
4. **Task 9:** README 说明
