package com.munin.app.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * ML Kit text recognition with the *bundled* models (the unbundled ones download through Play services,
 * which would need the network).
 *
 * The Devanagari recognizer also reads Latin and gave identical output to the Latin recognizer on the English
 * samples, so it runs first. Only when it is unsure (low mean confidence) is the Latin recognizer tried as well,
 * and the more confident result wins. Low-confidence lines are then dropped ([OcrLines]).
 */
class MlKitOcrEngine(private val context: Context) : OcrEngine {
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagari by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    override suspend fun warmUp() {
        val blank = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val image = InputImage.fromBitmap(blank, 0)
        devanagari.process(image).await()
        latin.process(image).await()
        blank.recycle()
    }

    override suspend fun recognize(uri: String, rotationDegrees: Int): OcrResult {
        val bitmap = withContext(Dispatchers.IO) { decode(Uri.parse(uri)) }
        try {
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            val primary = lines(devanagari, image)
            var best = "mlkit-devanagari" to primary
            if (OcrLines.meanConfidence(primary) < LATIN_FALLBACK_BELOW) {
                val secondary = lines(latin, image)
                if (OcrLines.meanConfidence(secondary) > OcrLines.meanConfidence(primary)) best = "mlkit-latin" to secondary
            }
            return OcrResult(OcrLines.keep(best.second).joinToString("\n") { it.text }, best.first)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun lines(recognizer: TextRecognizer, image: InputImage): List<OcrLine> =
        recognizer.process(image).await().textBlocks.flatMap { it.lines }.map { OcrLine(it.text, it.confidence) }

    /** Decodes at no more than [MAX_SIDE] px on the longest edge. */
    private fun decode(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Could not decode image")
    }

    private companion object {
        const val MAX_SIDE = 3200
        const val LATIN_FALLBACK_BELOW = 0.75f
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
