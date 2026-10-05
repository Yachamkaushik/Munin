package com.munin.app.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Full-text index over chunk text (`chunk_fts`, rowid = chunk id).
 *
 * Room only validates FTS3/FTS4 entities, so this table is managed with raw SQL: FTS5 when the device's
 * SQLite has it (it has a built-in `bm25()`), otherwise FTS4 (ranking then needs `matchinfo`, see step 3).
 *
 * The default `unicode61` tokenizer splits words at Indic vowel signs and viramas, because they are
 * combining marks rather than letters. Those marks (and ZWJ/ZWNJ) are therefore declared token characters.
 */
object KeywordIndex {
    const val TABLE = "chunk_fts"

    enum class Engine { FTS5, FTS4 }

    /** Creates the table if missing and reports which engine backs it. */
    fun ensure(db: SupportSQLiteDatabase): Engine {
        db.query("SELECT sql FROM sqlite_master WHERE name = '$TABLE'").use {
            if (it.moveToFirst()) return if (it.getString(0).contains("fts5", ignoreCase = true)) Engine.FTS5 else Engine.FTS4
        }
        val chars = tokenChars()
        return try {
            db.execSQL("CREATE VIRTUAL TABLE $TABLE USING fts5(text, tokenize = \"unicode61 remove_diacritics 0 tokenchars '$chars'\")")
            Engine.FTS5
        } catch (e: Exception) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            db.execSQL("CREATE VIRTUAL TABLE $TABLE USING fts4(text, tokenize=unicode61 \"remove_diacritics=0\" \"tokenchars=$chars\")")
            Engine.FTS4
        }
    }

    fun insert(db: SupportSQLiteDatabase, chunkId: Long, text: String) {
        db.execSQL("INSERT INTO $TABLE(rowid, text) VALUES (?, ?)", arrayOf<Any>(chunkId, text))
    }

    fun delete(db: SupportSQLiteDatabase, chunkIds: List<Long>) {
        chunkIds.chunked(500).forEach { batch ->
            db.execSQL("DELETE FROM $TABLE WHERE rowid IN (${batch.joinToString(",")})")
        }
    }

    fun clear(db: SupportSQLiteDatabase) = db.execSQL("DELETE FROM $TABLE")

    fun matchCount(db: SupportSQLiteDatabase, match: String): Int =
        db.query("SELECT COUNT(*) FROM $TABLE WHERE $TABLE MATCH ?", arrayOf(match)).use { it.moveToFirst(); it.getInt(0) }

    /** Combining marks and joiners in Devanagari and Telugu that occur inside words. */
    internal fun tokenChars(): String = buildString {
        val marks = setOf(
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(), Character.FORMAT.toInt(),
        )
        for (cp in (0x0900..0x097F) + (0x0C00..0x0C7F) + listOf(0x200C, 0x200D)) {
            if (Character.getType(cp) in marks) appendCodePoint(cp)
        }
    }
}
