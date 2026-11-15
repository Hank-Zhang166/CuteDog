package com.cutedog.app.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Local-only history and explicitly curated memory. No automatic extraction or upload. */
class ChatRepository private constructor(context: Context, databaseName: String, @Suppress("UNUSED_PARAMETER") isolated: Boolean) {
    constructor(context: Context) : this(context, "companion.db", false)
    internal constructor(context: Context, databaseName: String) : this(context, databaseName, true)
    private val helper = Store.get(context.applicationContext, databaseName)

    fun messages(): List<ChatMessage> = buildList {
        helper.readableDatabase.query("messages", null, null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) add(ChatMessage(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("role")),
                c.getString(c.getColumnIndexOrThrow("content")),
                c.getLong(c.getColumnIndexOrThrow("created_at")),
            ))
        }
    }

    fun add(role: String, content: String): ChatMessage {
        require(role == "user" || role == "assistant") { "不支持的消息类型" }
        val trimmed = content.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 20_000) { "消息需为 1 至 20000 个字符" }
        val time = System.currentTimeMillis()
        val id = helper.writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("role", role); put("content", trimmed); put("created_at", time)
        })
        return ChatMessage(id, role, trimmed, time)
    }

    fun clear() { helper.writableDatabase.delete("messages", null, null) }

    fun deleteMessage(id: Long) {
        helper.writableDatabase.delete("messages", "id = ?", arrayOf(id.toString()))
    }

    fun exportConversation(): String = JSONObject().apply {
        put("format", "CuteDog conversation"); put("version", 1)
        put("exportedAt", System.currentTimeMillis())
        put("messages", JSONArray().apply {
            messages().forEach { message -> put(JSONObject()
                .put("id", message.id).put("role", message.role)
                .put("content", message.content).put("createdAt", message.createdAt)) }
        })
    }.toString(2)

    fun memories(): List<Memory> = buildList {
        helper.readableDatabase.query("memories", null, null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) add(Memory(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("text")),
            ))
        }
    }

    fun saveMemory(id: Long = 0, text: String): Long {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 2_000) { "每条记忆需为 1 至 2000 个字符" }
        val values = ContentValues().apply { put("text", trimmed) }
        val db = helper.writableDatabase
        if (id > 0) {
            require(db.update("memories", values, "id = ?", arrayOf(id.toString())) == 1) { "记忆不存在" }
            return id
        }
        return db.insertOrThrow("memories", null, values)
    }

    fun deleteMemory(id: Long) { helper.writableDatabase.delete("memories", "id = ?", arrayOf(id.toString())) }
    fun clearMemories() { helper.writableDatabase.delete("memories", null, null) }

    fun exportMemories(): String = JSONObject().apply {
        put("format", "CuteDog memories"); put("version", 1)
        put("exportedAt", System.currentTimeMillis())
        put("memories", JSONArray().apply {
            memories().forEach { put(JSONObject().put("id", it.id).put("text", it.text)) }
        })
    }.toString(2)

    private class Store(context: Context, databaseName: String) : SQLiteOpenHelper(context, databaseName, null, 1) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL CHECK(role IN ('user','assistant')), content TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Missing non-destructive migration from $oldVersion to $newVersion")
        }
        companion object {
            private val instances = mutableMapOf<String, Store>()
            fun get(context: Context, databaseName: String): Store = synchronized(this) {
                instances.getOrPut(databaseName) { Store(context, databaseName) }
            }
        }
    }
}
