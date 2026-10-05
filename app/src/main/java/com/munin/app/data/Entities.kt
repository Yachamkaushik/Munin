package com.munin.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object ItemKind {
    const val IMAGE = "image"
    const val PDF = "pdf"
}

object ItemStatus {
    const val PENDING = "PENDING"
    const val INDEXED = "INDEXED"
    /** Looked at, but OCR found no readable text. Kept so it is not re-scanned. */
    const val NO_TEXT = "NO_TEXT"
    /** Byte-identical to an item that is already indexed. */
    const val DUPLICATE = "DUPLICATE"
    const val FAILED = "FAILED"
}

/** One row per file. We store a reference (content URI), never a copy of the file. */
@Entity(
    tableName = "items",
    indices = [Index("uri", unique = true), Index("status"), Index("contentHash")],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val kind: String,
    val displayName: String,
    val sizeBytes: Long,
    /** MediaStore DATE_MODIFIED, seconds. Used to notice edited files. */
    val modifiedAt: Long,
    /** MediaStore DATE_ADDED, seconds. Indexing order is newest first. */
    val addedAt: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val contentHash: String? = null,
    val docType: String? = null,
    val status: String = ItemStatus.PENDING,
    val error: String? = null,
    val indexedAt: Long? = null,
    val ocrMs: Long? = null,
    val embedMs: Long? = null,
    val textLength: Int? = null,
    /** [com.munin.app.extract.FactExtractor.VERSION] the facts were extracted with; 0 = never. */
    @ColumnInfo(defaultValue = "0") val factsVersion: Int = 0,
)

@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(ItemEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("itemId")],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val ordinal: Int,
    val text: String,
)

/** One vector per chunk, stored as little-endian float32 ([VectorCodec]). */
@Entity(
    tableName = "embeddings",
    foreignKeys = [ForeignKey(ChunkEntity::class, ["id"], ["chunkId"], onDelete = ForeignKey.CASCADE)],
)
data class EmbeddingEntity(
    @PrimaryKey val chunkId: Long,
    /** A different model version means these vectors are incompatible and must be re-indexed. */
    val modelVersion: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
) {
    override fun equals(other: Any?) = other is EmbeddingEntity && chunkId == other.chunkId &&
        modelVersion == other.modelVersion && vector.contentEquals(other.vector)

    override fun hashCode() = 31 * (31 * chunkId.hashCode() + modelVersion.hashCode()) + vector.contentHashCode()
}

/** A value read from an item's text (an amount, date, phone number or address); see [com.munin.app.extract.ExtractedFact]. */
@Entity(
    tableName = "facts",
    foreignKeys = [ForeignKey(ItemEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("itemId")],
)
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    /** A [com.munin.app.extract.FactType] name. */
    val name: String,
    /** Normalized value (plain decimal, ISO date, +91 phone, joined address). */
    val value: String,
    val confidence: Float,
    @ColumnInfo(defaultValue = "''") val raw: String = "",
    val label: String? = null,
    @ColumnInfo(defaultValue = "0") val lineIndex: Int = 0,
)
