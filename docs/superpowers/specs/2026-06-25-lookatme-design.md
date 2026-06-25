# Lookatme 微信监护监测系统 — 设计文档

> **Goal:** 在 Android/HarmonyOS 平板上监听微信通知消息，同步至局域网 Flask 服务器，供监护人 Web 查看。

**场景：** 家长监测小孩微信聊天记录，防止不良信息。纯局域网部署，数据不出门。

**核心约束：**
- 不 root、不破解微信、不反编译
- 使用 Android 系统官方 NotificationListenerService API
- 仅捕获文本消息内容
- 群聊消息不保存
- 平板离线时本地缓存，服务器上线后批量同步并清除本地数据

---

## 系统架构

```
┌─────────────────────────────────────────────────┐
│ 平板 (Android / HarmonyOS)                       │
│                                                 │
│  微信 ──→ 系统通知 ──→ Lookatme APK               │
│                          │                       │
│                    ┌─────▼──────┐               │
│                    │ 本地 SQLite │  (暂存未同步)  │
│                    └─────┬──────┘               │
│                          │ POST /api/upload      │
│                          │ (检测到服务器在线时)    │
└──────────────────────────┼──────────────────────┘
                           │ 局域网 HTTP
┌──────────────────────────┼──────────────────────┐
│ 家里电脑 (Windows)        │                       │
│                     ┌────▼──────┐               │
│                     │ Flask API  │  :5000       │
│                     └────┬──────┘               │
│                     ┌────▼──────┐               │
│                     │ SQLite DB  │               │
│                     └────┬──────┘               │
│                     ┌────▼──────┐               │
│                     │ Web 页面   │ 浏览器查看     │
│                     └───────────┘               │
└─────────────────────────────────────────────────┘
```

**数据流：**
1. 微信收到消息 → 系统发出通知
2. APK 的 NotificationListenerService 捕获通知 → 解析出发送人、内容、时间
3. 写入平板本地 SQLite（msg_id 去重）
4. SyncWorker 定时（30 秒）检测 `GET /api/health`
5. 服务器在线 → 批量 POST 未同步消息
6. 服务器返回 `{"synced": N}` → APK 删除本地已同步记录

---

## 组件设计

### 1. Flask 后端 (server/)

**文件结构：**
```
server/
├── app.py               # Flask 主入口，API + Web 页面
├── models.py            # 数据库模型
├── requirements.txt     # 依赖
└── messages.db          # SQLite 数据库（自动创建）
```

**API 接口：**

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/health` | 心跳检测，返回 `{"status": "ok"}` |
| `POST` | `/api/upload` | 批量上传消息 |
| `GET` | `/` | Web 查看页面 |
| `GET` | `/api/messages` | 分页查询消息（支持搜索） |

**POST /api/upload 请求体：**
```json
{
  "messages": [
    {
      "sender": "小明妈妈",
      "content": "放学记得买文具",
      "msg_type": "text",
      "room_name": "小明妈妈",
      "msg_time": "2026-06-25 14:30:00",
      "msg_id": "wx_msg_id_xxx"
    }
  ]
}
```
响应：`{"synced": 3}`

**GET /api/messages 查询参数：**
- `page` — 页码（默认 1）
- `per_page` — 每页条数（默认 50）
- `q` — 搜索关键词（匹配 sender 或 content）
- `date` — 按日期筛选（YYYY-MM-DD）

**数据库表结构：**
```sql
CREATE TABLE messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    sender      TEXT NOT NULL,
    content     TEXT NOT NULL,
    msg_type    TEXT DEFAULT 'text',
    room_name   TEXT DEFAULT '',
    msg_time    DATETIME NOT NULL,
    sync_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
    msg_id      TEXT UNIQUE
);
```

**Web 页面功能：**
- 消息列表按时间倒序，按日期分组
- 搜索框（发送人/内容），实时搜索
- 自动刷新（每 5 秒）
- 消息卡片显示：头像首字、发送人、内容、时间
- 响应式布局，手机/桌面都可用

### 2. Android APK (LookatmeApp/)

**文件结构：**
```
LookatmeApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/lookatme/
│   │   │   ├── MainActivity.kt
│   │   │   ├── WeChatListenerService.kt
│   │   │   ├── LocalDatabase.kt
│   │   │   └── SyncWorker.kt
│   │   ├── AndroidManifest.xml
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       ├── values/strings.xml
│   │       └── drawable/ic_launcher.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
    └── wrapper/
```

**核心组件：**

| 组件 | 作用 |
|------|------|
| `MainActivity.kt` | 主界面 — 显示服务状态/开关，引导授权 |
| `WeChatListenerService.kt` | NotificationListenerService — 监听通知，过滤群聊，提取消息 |
| `LocalDatabase.kt` | SQLite 封装 — 增删查，去重 |
| `SyncWorker.kt` | 后台定时任务 — 心跳检测 + 批量同步 |
| `AndroidManifest.xml` | 声明服务、权限 |

**权限需求：**
- `android.permission.INTERNET`
- `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`（系统授权）

**服务器地址配置：**
在 MainActivity 中配置服务器 IP（手动输入，平板上操作）：
```kotlin
var serverUrl = "http://192.168.1.100:5000"
```

**交互流程：**
1. 安装 APK → 打开 App
2. 输入服务器 IP 地址 → 点击保存
3. 点击"授权通知监听"→ 跳转系统设置
4. 开启后，后台自动运行，无需再打开 App

### 3. 鸿蒙兼容性

- HarmonyOS 完全兼容 Android NotificationListenerService
- APK 可直接在鸿蒙平板上安装运行
- 无需额外适配

---

## 数据同步策略

1. **平板离线（服务器关机）：** 消息存入本地 SQLite，msg_id 去重
2. **服务器上线：** SyncWorker 每 30 秒 `GET /api/health`，检测到 200 后开始同步
3. **批量上传：** 每次最多 20 条，携带 msg_id 防重复
4. **确认删除：** 收到 `{"synced": N}` 后，删除本地对应记录
5. **断网恢复：** 网络中断后自动重试，不丢数据

---

## YAGNI

以下功能本次不实现：
- 用户登录 / 权限管理（局域网环境不需要）
- HTTPS（局域网环境不需要）
- 消息推送 / 告警
- 图片/语音/文件保存
- 多设备同步
- 远程访问
