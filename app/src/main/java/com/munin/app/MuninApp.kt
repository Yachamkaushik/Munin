package com.munin.app

import android.app.Application
import com.munin.app.data.MuninDatabase
import com.munin.app.index.LayeredOcrEngine
import com.munin.app.index.MlKitOcrEngine
import com.munin.app.index.OcrSettings
import com.munin.app.index.TesseractOcrEngine
import com.munin.app.index.OcrEngine
import com.munin.app.ml.E5Embedder

/** Process-wide singletons. The embedder is loaded on first use (about 5 s) and then kept. */
class MuninApp : Application() {
    val database: MuninDatabase by lazy { MuninDatabase.create(this) }
    /** ML Kit first; Tesseract (Telugu) only as a second opinion and only if the user switched it on. Tesseract is loaded on first use. */
    val ocrEngine: OcrEngine by lazy { LayeredOcrEngine(MlKitOcrEngine(this), TesseractOcrEngine(this)) { OcrSettings.policy(this) } }
    val embedder: E5Embedder by lazy { E5Embedder.load(this) }
}
