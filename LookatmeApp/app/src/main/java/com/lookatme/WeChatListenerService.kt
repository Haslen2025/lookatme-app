package com.lookatme

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

        // Group chat keywords to filter out
        private val GROUP_KEYWORDS = listOf("群聊", "群", "群组", "群消息", "微信团队")

        var lastSyncCount = 0
            private set
    }

    override fun onListenerConnected() {
        Log.d(TAG, "通知监听服务已连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Only process WeChat notifications
        if (sbn.packageName != WECHAT_PACKAGE) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Get notification title and content
        val title = extras.getString(Notification.EXTRA_TITLE, "")
        val text = extras.getString(Notification.EXTRA_TEXT, "")
            ?: extras.getString(Notification.EXTRA_BIG_TEXT, "")
            ?: ""

        if (title.isBlank() || text.isBlank()) return

        // Filter group chats
        if (isGroupChat(title, text)) {
            Log.d(TAG, "过滤群聊消息: $title")
            return
        }

        // Filter non-text notifications (images, voice, etc.)
        if (text.startsWith("[") || isMediaMessage(text)) return

        // Truncate long content
        val content = if (text.length > MAX_CONTENT_LENGTH) {
            text.substring(0, MAX_CONTENT_LENGTH) + "..."
        } else text

        val msgId = sbn.key
        val msgTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(sbn.postTime))

        // Store to local database
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
        // Not needed
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
