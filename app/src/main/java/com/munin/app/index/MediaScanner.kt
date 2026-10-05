package com.munin.app.index

import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import com.munin.app.data.ItemEntity
import com.munin.app.data.ItemKind
import com.munin.app.data.MuninDatabase

data class ScanResult(val seen: Int, val added: Int, val changed: Int, val removed: Int, val skippedSmall: Int)

/**
 * Lists images from MediaStore (newest first, capped) and reconciles them with the database:
 * new files become PENDING items, edited files are re-queued, and files that have been deleted are dropped.
 */
class MediaScanner(private val context: Context, private val db: MuninDatabase) {

    suspend fun scan(access: MediaAccessState, maxItems: Int = DEFAULT_MAX_ITEMS): ScanResult {
        if (access == MediaAccessState.NONE) return ScanResult(0, 0, 0, 0, 0)
        val found = query(maxItems)
        val existing = db.items().allExisting().associateBy { it.uri }

        var added = 0
        var changed = 0
        for (item in found.items) {
            val old = existing[item.uri]
            when {
                old == null -> if (db.items().insertIgnore(item) != -1L) added++
                old.modifiedAt != item.modifiedAt || old.sizeBytes != item.sizeBytes -> {
                    // The file changed: drop what we derived from the old content and queue it again.
                    db.deleteItems(listOf(old.id))
                    if (db.items().insertIgnore(item) != -1L) changed++
                }
            }
        }

        // Only a full-access scan sees everything, so only then does "missing from MediaStore" mean "deleted".
        var removed = 0
        if (access == MediaAccessState.FULL && found.items.size < maxItems) {
            val present = found.items.mapTo(HashSet()) { it.uri }
            val gone = existing.values.filter { it.uri !in present }.map { it.id }
            db.deleteItems(gone)
            removed = gone.size
        }
        return ScanResult(found.items.size, added, changed, removed, found.skippedSmall)
    }

    private class Found(val items: List<ItemEntity>, val skippedSmall: Int)

    private fun query(maxItems: Int): Found {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.ORIENTATION,
        )
        val args = Bundle().apply {
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val items = ArrayList<ItemEntity>()
        var skipped = 0
        context.contentResolver.query(collection, projection, args, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val w = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val h = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val rot = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            while (c.moveToNext() && items.size < maxItems) {
                val width = c.getInt(w)
                val height = c.getInt(h)
                if (minOf(width, height) < MIN_SIDE_PX) { skipped++; continue }
                items += ItemEntity(
                    uri = ContentUris.withAppendedId(collection, c.getLong(id)).toString(),
                    kind = ItemKind.IMAGE, displayName = c.getString(name) ?: "image",
                    sizeBytes = c.getLong(size), modifiedAt = c.getLong(mod), addedAt = c.getLong(added),
                    width = width, height = height, rotation = c.getInt(rot),
                )
            }
        }
        return Found(items, skipped)
    }

    companion object {
        /** The demo indexes a few hundred recent items; raise this for a full library. */
        const val DEFAULT_MAX_ITEMS = 1000
        /** Anything this small (icons, stickers, thumbnails) has no readable text worth indexing. */
        const val MIN_SIDE_PX = 200
    }
}
