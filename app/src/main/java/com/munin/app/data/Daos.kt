package com.munin.app.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class StatusCount(val status: String, val count: Int)

/** A processed item for the progress screen: the first chunk's text stands in as a snippet. */
data class RecentItem(
    val id: Long,
    val uri: String,
    val displayName: String,
    val status: String,
    val error: String?,
    val ocrMs: Long?,
    val embedMs: Long?,
    val snippet: String?,
)

data class ExistingItem(val id: Long, val uri: String, val modifiedAt: Long, val sizeBytes: Long)

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Query("SELECT * FROM items WHERE status = 'PENDING' ORDER BY addedAt DESC, id DESC LIMIT 1")
    suspend fun nextPending(): ItemEntity?

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: Long): ItemEntity?

    @Query("SELECT COUNT(*) FROM items WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM items")
    suspend fun totalCount(): Int

    @Query("SELECT id, uri, modifiedAt, sizeBytes FROM items")
    suspend fun allExisting(): List<ExistingItem>

    @Query("SELECT COUNT(*) FROM items WHERE contentHash = :hash AND id != :excludeId AND status IN ('INDEXED', 'NO_TEXT')")
    suspend fun countIndexedWithHash(hash: String, excludeId: Long): Int

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM items")
    suspend fun deleteAll()

    @Query("UPDATE items SET status = 'PENDING', error = NULL WHERE status = 'FAILED'")
    suspend fun retryFailed()

    @Query("SELECT status, COUNT(*) AS count FROM items GROUP BY status")
    fun statusCounts(): Flow<List<StatusCount>>

    @Query("SELECT ocrMs FROM items WHERE ocrMs IS NOT NULL ORDER BY ocrMs")
    fun ocrTimes(): Flow<List<Long>>

    @Query("SELECT embedMs FROM items WHERE embedMs IS NOT NULL ORDER BY embedMs")
    fun embedTimes(): Flow<List<Long>>

    @Query("SELECT displayName FROM items WHERE status = 'PENDING' ORDER BY addedAt DESC, id DESC LIMIT 1")
    fun nextPendingName(): Flow<String?>

    @Query(
        """SELECT i.id, i.uri, i.displayName, i.status, i.error, i.ocrMs, i.embedMs, c.text AS snippet
           FROM items i LEFT JOIN chunks c ON c.itemId = i.id AND c.ordinal = 0
           WHERE i.status != 'PENDING' ORDER BY i.indexedAt DESC, i.id DESC LIMIT :limit""",
    )
    fun recent(limit: Int): Flow<List<RecentItem>>
}

/** A chunk with the item it belongs to, for showing a search result. */
data class ChunkWithItem(val chunkId: Long, val itemId: Long, val text: String, val uri: String, val displayName: String)

@Dao
interface ChunkDao {
    @Query(
        """SELECT c.id AS chunkId, c.itemId AS itemId, c.text AS text, i.uri AS uri, i.displayName AS displayName
           FROM chunks c JOIN items i ON i.id = c.itemId WHERE c.id IN (:ids)""",
    )
    suspend fun withItems(ids: List<Long>): List<ChunkWithItem>

    @Insert
    suspend fun insertAll(chunks: List<ChunkEntity>): List<Long>

    @Query("SELECT id FROM chunks WHERE itemId IN (:itemIds)")
    suspend fun idsForItems(itemIds: List<Long>): List<Long>

    @Query("SELECT id FROM chunks")
    suspend fun allIds(): List<Long>

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun count(): Int
}

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EmbeddingEntity>)

    @Query("SELECT COUNT(*) FROM embeddings")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM embeddings WHERE modelVersion != :version")
    suspend fun countOtherVersions(version: String): Int
}

@Dao
interface FactDao {
    @Insert
    suspend fun insertAll(facts: List<FactEntity>)

    @Query("DELETE FROM facts WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: Long)

    @Query("SELECT * FROM facts WHERE itemId IN (:itemIds) AND name = :type ORDER BY lineIndex, id")
    suspend fun forItems(itemIds: List<Long>, type: String): List<FactEntity>

    @Query("SELECT * FROM facts WHERE itemId = :itemId ORDER BY lineIndex, id")
    suspend fun allForItem(itemId: Long): List<FactEntity>

    @Query("SELECT COUNT(*) FROM facts")
    suspend fun count(): Int

    /** Items whose facts are missing or were extracted by older rules; their saved text is re-read, no OCR needed. */
    @Query("SELECT id FROM items WHERE status = 'INDEXED' AND factsVersion < :version")
    suspend fun itemsNeedingFacts(version: Int): List<Long>

    @Query("SELECT text FROM chunks WHERE itemId = :itemId ORDER BY ordinal")
    suspend fun chunkTexts(itemId: Long): List<String>

    @Query("UPDATE items SET factsVersion = :version WHERE id = :itemId")
    suspend fun markExtracted(itemId: Long, version: Int)
}

/** A stored payment with where it came from, for the ledger screen. */
data class PaymentWithItem(@Embedded val payment: PaymentEntity, val uri: String, val displayName: String)

@Dao
interface PaymentDao {
    @Insert
    suspend fun insert(payment: PaymentEntity)

    @Query("SELECT * FROM payments WHERE itemId = :itemId")
    suspend fun byItem(itemId: Long): PaymentEntity?

    @Query("DELETE FROM payments WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: Long)

    @Query("UPDATE payments SET userDecision = :decision WHERE id = :id")
    suspend fun setDecision(id: Long, decision: String?)

    @Query("SELECT p.*, i.uri AS uri, i.displayName AS displayName FROM payments p JOIN items i ON i.id = p.itemId")
    fun all(): Flow<List<PaymentWithItem>>

    @Query("SELECT p.*, i.uri AS uri, i.displayName AS displayName FROM payments p JOIN items i ON i.id = p.itemId")
    suspend fun allNow(): List<PaymentWithItem>

    @Query("SELECT COUNT(*) FROM payments")
    suspend fun count(): Int
}
