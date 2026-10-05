package com.munin.app.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri

/** Decodes an image URI at no more than [maxSide] px on its longest edge, upright. */
object BitmapLoader {
    fun decode(context: Context, uri: Uri, rotationDegrees: Int = 0, maxSide: Int = 3200): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Could not decode image")
        if (rotationDegrees % 360 == 0) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true).also { if (it !== raw) raw.recycle() }
    }
}
