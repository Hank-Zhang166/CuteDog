package com.cutedog.app.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Local-only history and explicitly curated memory. No automatic extraction or upload. */
class ChatRepository private constructor(context: Context, databaseName: String, @Suppress("UNUSED_PARAMETER") isolated: Boolean) {
    constructor(context: Context) : this(context, "companion.db", false)
    internal constructor(context: Context, databaseName: String) : this(context, databaseName, true)
    private val helper = Store.get(context.applicationContext, databaseName)

    fun messages(): List<ChatMessage> = buildList {
        // The API 26 WAL reader can retain pre-migration column names for SELECT *.
        val columns = arrayOf("id", "role", "content", "created_at", "voice")
        helper.readableDatabase.query("messages", columns, null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) add(ChatMessage(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("role")),
                c.getString(c.getColumnIndexOrThrow("content")),
                c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getInt(c.getColumnIndexOrThrow("voice")) != 0,
            ))
        }
    }

    fun observeMessages(): Flow<List<ChatMessage>> =
        helper.messageRevision.map { messages() }.flowOn(Dispatchers.IO)

    internal fun canReplyTo(userMessageId: Long) = questionVoice(helper.readableDatabase, userMessageId) != null

    private fun questionVoice(db: SQLiteDatabase, userMessageId: Long): Boolean? {
        val id = userMessageId.toString()
        return db.rawQuery(
            "SELECT voice FROM messages WHERE id=? AND role='user' AND NOT EXISTS (SELECT 1 FROM messages WHERE reply_to=?)",
            arrayOf(id, id),
        ).use { if (it.moveToFirst()) it.getInt(0) != 0 else null }
    }

    fun add(role: String, content: String, voice: Boolean = false): ChatMessage {
        require(role == "user" || role == "assistant") { "不支持的消息类型" }
        return insert(helper.writableDatabase, role, content, voice).also { helper.notifyMessagesChanged() }
    }

    /** A retry or a late response from a second window cannot duplicate a reply or recreate a deleted turn. */
    fun saveReply(userMessageId: Long, text: String): ChatMessage? {
        val db = helper.writableDatabase
        db.beginTransaction()
        val reply = try {
            val voice = questionVoice(db, userMessageId)
            val saved = voice?.let { insert(db, "assistant", text, it, userMessageId) }
            db.setTransactionSuccessful()
            saved
        } finally { db.endTransaction() }
        if (reply != null) helper.notifyMessagesChanged()
        return reply
    }

    private fun insert(db: SQLiteDatabase, role: String, content: String, voice: Boolean, replyTo: Long? = null): ChatMessage {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 20_000) { "消息需为 1 至 20000 个字符" }
        val time = System.currentTimeMillis()
        val id = db.insertOrThrow("messages", null, ContentValues().apply {
            put("role", role); put("content", trimmed); put("created_at", time)
            put("voice", voice)
            if (replyTo != null) put("reply_to", replyTo)
        })
        return ChatMessage(id, role, trimmed, time, voice)
    }

    fun clear() {
        if (helper.writableDatabase.delete("messages", null, null) > 0) helper.notifyMessagesChanged()
    }

    fun deleteMessage(id: Long) {
        if (helper.writableDatabase.delete("messages", "id = ?", arrayOf(id.toString())) > 0) helper.notifyMessagesChanged()
    }

    fun exportConversation(): String = JSONObject().apply {
        put("format", "CuteDog conversation"); put("version", 1)
        put("exportedAt", System.currentTimeMillis())
        put("messages", JSONArray().apply {
            messages().forEach { message -> put(JSONObject()
                .put("id", message.id).put("role", message.role)
                .put("content", message.content).put("createdAt", message.createdAt)
                .apply { if (message.voice) put("voice", true) }) }
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

    private class Store(context: Context, databaseName: String) : SQLiteOpenHelper(context, databaseName, null, 2) {
        val messageRevision = MutableStateFlow(0L)
        fun notifyMessagesChanged() = messageRevision.update { it + 1 }
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL CHECK(role IN ('user','assistant')), content TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL)")
            addVoiceColumns(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion == 1 && newVersion == 2) { "Missing migration from $oldVersion to $newVersion" }
            addVoiceColumns(db)
        }
        private fun addVoiceColumns(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN voice INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_to INTEGER")
            db.execSQL("CREATE UNIQUE INDEX messages_reply_to ON messages(reply_to) WHERE reply_to IS NOT NULL")
        }
        companion object {
            private val instances = mutableMapOf<String, Store>()
            fun get(context: Context, databaseName: String): Store = synchronized(this) {
                instances.getOrPut(databaseName) { Store(context, databaseName) }
            }
        }
    }
}
