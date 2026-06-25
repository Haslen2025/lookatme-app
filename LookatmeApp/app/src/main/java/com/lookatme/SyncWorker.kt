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
     * Check if the server is online via health endpoint
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
     * Upload pending messages to the server
     * Returns: number of successfully synced messages
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
                    // Delete successfully synced records from local DB
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
