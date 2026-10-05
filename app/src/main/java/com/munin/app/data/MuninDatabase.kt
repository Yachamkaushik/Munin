package com.munin.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.munin.app.extract.PaymentExtractor
import com.munin.app.extract.FactExtractor

@Database(
    entities = [ItemEntity::class, ChunkEntity::class, EmbeddingEntity::class, FactEntity::class, PaymentEntity::class, NotificationEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class MuninDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun chunks(): ChunkDao
    abstract fun embeddings(): EmbeddingDao
    abstract fun facts(): FactDao
    abstract fun payments(): PaymentDao
    abstract fun notifications(): NotificationDao

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

    /**
     * Re-reads [text] and replaces the item's facts and its payment record, recording which rule version did it.
     * A payment the user included or excluded keeps that decision.
     */
    suspend fun replaceFacts(itemId: Long, text: String, version: Int = FactExtractor.VERSION) = withTransaction {
        facts().deleteForItem(itemId)
        facts().insertAll(
            FactExtractor.extract(text).map {
                FactEntity(itemId = itemId, name = it.type.name, value = it.value, confidence = it.confidence, raw = it.raw, label = it.label, lineIndex = it.line)
            },
        )
        val previousDecision = payments().byItem(itemId)?.userDecision
        payments().deleteForItem(itemId)
        PaymentExtractor.extract(text)?.let { p ->
            payments().insert(
                PaymentEntity(
                    itemId = itemId, app = p.app, outcome = p.outcome.name, direction = p.direction.name, amountPaise = p.amountPaise,
                    amountConfidence = p.amountConfidence, amountRaw = p.amountRaw, payee = p.payee, upiId = p.upiId, paidDate = p.date,
                    paidTime = p.time, reference = p.reference, problem = p.problem, userDecision = previousDecision,
                ),
            )
        }
        facts().markExtracted(itemId, version)
    }

    /** Extracts facts for indexed items that have none yet (e.g. indexed before this feature). Returns how many. */
    suspend fun backfillFacts(): Int {
        val ids = facts().itemsNeedingFacts(FactExtractor.VERSION)
        for (id in ids) replaceFacts(id, facts().chunkTexts(id).joinToString("\n"))
        return ids.size
    }

    fun matchCount(match: String) = KeywordIndex.matchCount(sql, match)

    companion object {
        /** v1 -> v2: facts gain their source text, label and line; items remember which extraction rules were applied. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN factsVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE facts ADD COLUMN raw TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE facts ADD COLUMN label TEXT")
                db.execSQL("ALTER TABLE facts ADD COLUMN lineIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 -> v3: the payments table behind the ledger. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER NOT NULL, " +
                        "`app` TEXT, `outcome` TEXT NOT NULL, `direction` TEXT NOT NULL, `amountPaise` INTEGER, `amountConfidence` REAL NOT NULL, " +
                        "`amountRaw` TEXT, `payee` TEXT, `upiId` TEXT, `paidDate` TEXT, `paidTime` TEXT, `reference` TEXT, `problem` TEXT, " +
                        "`userDecision` TEXT, FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payments_itemId` ON `payments` (`itemId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_paidDate` ON `payments` (`paidDate`)")
            }
        }

        /** v3 -> v4: the optional notification history. Only adds a table; nothing existing is touched. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, " +
                        "`appLabel` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `postedAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_postedAt` ON `notifications` (`postedAt`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notifications_packageName_postedAt_title_text` ON `notifications` (`packageName`, `postedAt`, `title`, `text`)")
            }
        }

        fun create(context: Context, name: String? = "munin.db"): MuninDatabase {
            val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, MuninDatabase::class.java)
            else Room.databaseBuilder(context, MuninDatabase::class.java, name)
            lateinit var db: MuninDatabase
            db = builder.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).addCallback(object : Callback() {
                override fun onOpen(db0: SupportSQLiteDatabase) {
                    db.keywordEngine = KeywordIndex.ensure(db0)
                }
            }).build()
            return db
        }
    }
}
