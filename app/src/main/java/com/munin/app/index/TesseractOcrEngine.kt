package com.munin.app.index

import android.content.Context
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Tesseract 5 (LSTM, `tessdata_fast`) with Telugu and English, bundled in the APK. ML Kit has no Telugu model, so this is the only way
 * to read Telugu text in images. Slower and less accurate than ML Kit on Latin and Devanagari, which is why it is a second opinion
 * ([LayeredOcrEngine]) rather than the default. Tesseract is not thread-safe, so calls are serialised.
 */
class TesseractOcrEngine(private val context: Context, private val languages: String = "tel+eng") : OcrEngine {
    private val lock = Mutex()
    private var api: TessBaseAPI? = null

    private fun ensureApi(): TessBaseAPI {
        api?.let { return it }
        val root = File(context.filesDir, "tess")
        val dir = File(root, "tessdata").also { it.mkdirs() }
        for (lang in languages.split('+')) {
            val target = File(dir, "$lang.traineddata")
            val size = context.assets.openFd("tessdata/$lang.traineddata").use { it.length }
            if (!target.exists() || target.length() != size) context.assets.open("tessdata/$lang.traineddata").use { src -> target.outputStream().use { src.copyTo(it) } }
        }
        val t = TessBaseAPI()
        check(t.init(root.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)) { "Tesseract could not load '$languages'" }
        t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        return t.also { api = it }
    }

    override suspend fun warmUp() = lock.withLock { withContext(Dispatchers.Default) { ensureApi(); Unit } }

    override suspend fun recognize(uri: String, rotationDegrees: Int): OcrResult = lock.withLock {
        withContext(Dispatchers.Default) {
            val bitmap = BitmapLoader.decode(context, Uri.parse(uri), rotationDegrees, MAX_SIDE)
            try {
                val t = ensureApi()
                t.setImage(bitmap)
                val text = t.getUTF8Text().orEmpty()
                val lines = TeluguScript.normalize(text).lines().map { it.trim() }.filter { it.isNotEmpty() }
                OcrResult(lines.joinToString("\n"), "tesseract-$languages", t.meanConfidence() / 100f)
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun close() { api?.recycle(); api = null }

    private companion object {
        /** Tesseract is slow on large images; 2000 px on the long side is plenty for screenshots and phone photos of documents. */
        const val MAX_SIDE = 2000
    }
}
