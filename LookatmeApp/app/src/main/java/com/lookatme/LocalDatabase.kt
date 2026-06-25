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
