package com.cutedog.app.daily

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

data class DailyQuote(val id: Int, val text: String, val date: String, val favorite: Boolean)

/** The archive stores viewed days; favorites use stable quote IDs independently of the archive. */
class DailyRepository(context: Context) {
    private val db = DailyDatabase.get(context).writableDatabase
    private val quotes = loadQuotes(context)

    fun today(): DailyQuote = synchronized(lock) {
        val date = LocalDate.now().toString()
        val id = DailyRules.quoteId(LocalDate.parse(date), quotes.size)
        db.insertWithOnConflict("history", null, ContentValues().apply {
            put("day", date)
            put("quote_id", id)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        DailyQuote(id, quotes[id], date, isFavorite(id))
    }

    fun history(): List<DailyQuote> = synchronized(lock) {
        db.rawQuery("SELECT h.quote_id, h.day, CASE WHEN f.quote_id IS NULL THEN 0 ELSE 1 END FROM history h LEFT JOIN favorites f ON f.quote_id = h.quote_id ORDER BY h.day DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    if (id in quotes.indices) add(DailyQuote(id, quotes[id], cursor.getString(1), cursor.getInt(2) != 0))
                }
            }
        }
    }

    fun favorites(): List<DailyQuote> = synchronized(lock) {
        db.rawQuery("SELECT f.quote_id, COALESCE(MAX(h.day), '') FROM favorites f LEFT JOIN history h ON h.quote_id = f.quote_id GROUP BY f.quote_id ORDER BY f.added_at DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    if (id in quotes.indices) add(DailyQuote(id, quotes[id], cursor.getString(1), true))
                }
            }
        }
    }

    fun toggleFavorite(id: Int) = synchronized(lock) {
        require(id in quotes.indices)
        if (isFavorite(id)) db.delete("favorites", "quote_id = ?", arrayOf(id.toString()))
        else db.insertOrThrow("favorites", null, ContentValues().apply {
            put("quote_id", id)
            put("added_at", System.currentTimeMillis())
        })
        Unit
    }

    internal fun wasGreeted(date: String): Boolean = db.rawQuery(
        "SELECT 1 FROM greetings WHERE day = ?", arrayOf(date)
    ).use { it.moveToFirst() }

    internal fun markGreeted(date: String) {
        db.insertWithOnConflict("greetings", null, ContentValues().apply { put("day", date) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun isFavorite(id: Int): Boolean = db.rawQuery(
        "SELECT 1 FROM favorites WHERE quote_id = ?", arrayOf(id.toString())
    ).use { it.moveToFirst() }

    companion object {
        internal val lock = Any()
        @Volatile private var cachedQuotes: List<String>? = null
        private fun loadQuotes(context: Context): List<String> = cachedQuotes ?: synchronized(lock) {
            cachedQuotes ?: context.assets.open("quotes.txt").bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLines().map(String::trim).filter(String::isNotEmpty).also {
                    check(it.size >= DailyRules.MINIMUM_QUOTE_COUNT) { "每日短句资源不足 366 条" }
                    check(it.distinct().size == it.size) { "每日短句存在重复" }
                    cachedQuotes = it
                }
            }
        }
    }
}

private class DailyDatabase(context: Context) : SQLiteOpenHelper(context, "daily.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(day TEXT PRIMARY KEY NOT NULL, quote_id INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE favorites(quote_id INTEGER PRIMARY KEY, added_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE greetings(day TEXT PRIMARY KEY NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        @Volatile private var instance: DailyDatabase? = null
        fun get(context: Context): DailyDatabase = instance ?: synchronized(this) {
            instance ?: DailyDatabase(context.applicationContext).also { instance = it }
        }
    }
}
