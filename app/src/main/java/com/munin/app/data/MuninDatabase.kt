package com.munin.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ItemEntity::class, ChunkEntity::class, EmbeddingEntity::class, FactEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MuninDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun chunks(): ChunkDao
    abstract fun embeddings(): EmbeddingDao
    abstract fun facts(): FactDao

    /** FTS5 or FTS4, decided when the database is opened. */
    @Volatile var keywordEngine: KeywordIndex.Engine = KeywordIndex.Engine.FTS4
        private set

    private val sql: SupportSQLiteDatabase get() = openHelper.writableDatabase

    /** Deletes items and everything derived from them, including their full-text rows. */
    suspend fun deleteItems(ids: List<Long>) {
        if (ids.isEmpty()) return
        withTransaction {
            ids.chunked(500).forEach { batch ->
                KeywordIndex.delete(sql, chunks().idsForItems(batch))
                items().deleteByIds(batch)
            }
        }
    }

    suspend fun clearIndex() = withTransaction {
        KeywordIndex.clear(sql)
        items().deleteAll()
    }

    /** Inserts chunks, their vectors and full-text rows atomically. Returns the chunk ids. */
    suspend fun insertChunks(chunkRows: List<ChunkEntity>, vectors: List<ByteArray>, modelVersion: String): List<Long> =
        withTransaction {
            val ids = chunks().insertAll(chunkRows)
            embeddings().insertAll(ids.indices.map { EmbeddingEntity(ids[it], modelVersion, vectors[it]) })
            ids.indices.forEach { KeywordIndex.insert(sql, ids[it], chunkRows[it].text) }
            ids
        }

    fun matchCount(match: String) = KeywordIndex.matchCount(sql, match)

    companion object {
        fun create(context: Context, name: String? = "munin.db"): MuninDatabase {
            val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, MuninDatabase::class.java)
            else Room.databaseBuilder(context, MuninDatabase::class.java, name)
            lateinit var db: MuninDatabase
            db = builder.addCallback(object : Callback() {
                override fun onOpen(db0: SupportSQLiteDatabase) {
                    db.keywordEngine = KeywordIndex.ensure(db0)
                }
            }).build()
            return db
        }
    }
}
