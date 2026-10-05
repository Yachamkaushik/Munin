package com.munin.app.index

import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Tesseract (tel+eng) on real images, on a device: does it read Telugu, and does it stay quiet on English? */
class TesseractOcrTest {
    private fun asset(name: String): String {
        val f = File(target.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { src -> f.outputStream().use { src.copyTo(it) } }
        return Uri.fromFile(f).toString()
    }

    @Test fun readsATeluguReceipt() = runBlocking {
        val r = engine.recognize(asset("sample_te_hostel_fee.png"), 0)
        Log.i("MuninTess", "telugu sample -> conf=${r.confidence} :: ${r.text.replace('\n', '|')}")
        assertTrue("expected Telugu letters, got: ${r.text}", TeluguScript.count(r.text) >= 10)
        assertTrue(LayeredOcrEngine.looksTelugu(r.text))
    }

    @Test fun anEnglishReceiptIsNotMistakenForTelugu() = runBlocking {
        val r = engine.recognize(asset("sample_en_hostel_fee.png"), 0)
        Log.i("MuninTess", "english sample -> conf=${r.confidence} telugu chars=${TeluguScript.count(r.text)} :: ${r.text.replace('\n', '|')}")
        assertTrue("an English receipt must not look Telugu: ${r.text}", !LayeredOcrEngine.looksTelugu(r.text))
    }

    companion object {
        private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var engine: TesseractOcrEngine
        @BeforeClass @JvmStatic fun open() { engine = TesseractOcrEngine(target) }
        @AfterClass @JvmStatic fun close() = engine.close()
    }
}
