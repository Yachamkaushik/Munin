package com.munin.app

import android.app.Application
import com.munin.app.data.MuninDatabase
import com.munin.app.index.MlKitOcrEngine
import com.munin.app.index.OcrEngine
import com.munin.app.ml.E5Embedder

/** Process-wide singletons. The embedder is loaded on first use (about 5 s) and then kept. */
class MuninApp : Application() {
    val database: MuninDatabase by lazy { MuninDatabase.create(this) }
    val ocrEngine: OcrEngine by lazy { MlKitOcrEngine(this) }
    val embedder: E5Embedder by lazy { E5Embedder.load(this) }
}
